package com.github.aiyingshi.osc.tv.module;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import okio.Buffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModuleDownloaderTest {

    private MockWebServer server;
    private File tempRoot;
    private byte[] content;

    @Before
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("module-downloader-test-").toFile();
        content = new byte[200 * 1024];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i % 251);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // mockwebserver 5.4.0（mockwebserver3）的 RecordedRequest 没有 getPath()：
                // 按官方 5.x 文档用 getUrl().encodedPath()；本测试的 URL 无 query，与旧 getPath() 等价。
                HttpUrl requestUrl = request.getUrl();
                String path = requestUrl != null ? requestUrl.encodedPath() : "";
                if ("/not-found.zip".equals(path)) {
                    return new MockResponse.Builder().code(404).build();
                }
                if ("/ignore-range.zip".equals(path)) {
                    // 服务端忽略 Range 返回 200 全量：下载器应从头写，不虚增进度。
                    return new MockResponse.Builder().code(200)
                            .body(new Buffer().write(content)).build();
                }
                // 5.4.0 的 RecordedRequest 没有 getHeader(String)：
                // 用 getHeaders().get(name)，语义相同（首个值，不存在则 null）。
                String range = request.getHeaders().get("Range");
                if (range != null && range.startsWith("bytes=")) {
                    long start = Long.parseLong(range.substring(6, range.length() - 1));
                    if (start >= content.length) {
                        return new MockResponse.Builder().code(416).build();
                    }
                    byte[] rest = Arrays.copyOfRange(content, (int) start, content.length);
                    return new MockResponse.Builder().code(206)
                            .addHeader("Content-Range",
                                    "bytes " + start + "-" + (content.length - 1)
                                            + "/" + content.length)
                            .body(new Buffer().write(rest)).build();
                }
                return new MockResponse.Builder().code(200)
                        .body(new Buffer().write(content)).build();
            }
        });
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.close();
        ModuleVerifierTest.deleteRecursive(tempRoot);
    }

    @Test
    public void downloadFullFile() throws Exception {
        File target = new File(tempRoot, "full.zip");
        String error = ModuleDownloader.downloadWithResume(server.url("/full.zip").toString(),
                target, null, null);
        assertNull(error);
        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void downloadResumesPartialFile() throws Exception {
        File target = new File(tempRoot, "resume.zip");
        Files.write(target.toPath(), Arrays.copyOfRange(content, 0, 80 * 1024));
        String error = ModuleDownloader.downloadWithResume(server.url("/full.zip").toString(),
                target, null, null);
        assertNull(error);
        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void downloadRestartsWhenServerIgnoresRange() throws Exception {
        File target = new File(tempRoot, "ignore.zip");
        Files.write(target.toPath(), Arrays.copyOfRange(content, 0, 80 * 1024));
        String error = ModuleDownloader.downloadWithResume(
                server.url("/ignore-range.zip").toString(), target, null, null);
        assertNull(error);
        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void downloadRestartsAfter416() throws Exception {
        File target = new File(tempRoot, "gone.zip");
        // 半截文件比服务端文件还长：服务端回 416，下载器删旧文件从头下。
        byte[] longer = new byte[content.length + 10];
        System.arraycopy(content, 0, longer, 0, content.length);
        Files.write(target.toPath(), longer);
        String error = ModuleDownloader.downloadWithResume(server.url("/full.zip").toString(),
                target, null, null);
        assertNull(error);
        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void downloadReportsHttpError() throws Exception {
        File target = new File(tempRoot, "missing.zip");
        String error = ModuleDownloader.downloadWithResume(
                server.url("/not-found.zip").toString(), target, null, null);
        assertTrue(error.contains("404"));
    }

    @Test
    public void downloadFallsBackToSecondUrl() throws Exception {
        File target = new File(tempRoot, "fallback.zip");
        String error = ModuleDownloader.downloadWithFallback(
                server.url("/not-found.zip").toString(),
                server.url("/full.zip").toString(), target, null, null);
        assertNull(error);
        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void downloadRespectsCancellation() throws Exception {
        File target = new File(tempRoot, "cancel.zip");
        ModuleProgress.CancellationToken token = new ModuleProgress.CancellationToken();
        token.cancel();
        String error = ModuleDownloader.downloadWithResume(server.url("/full.zip").toString(),
                target, null, token);
        assertEquals("已取消下载", error);
        assertEquals(0L, target.length());
    }

    @Test
    public void downloadReportsProgress() throws Exception {
        File target = new File(tempRoot, "progress.zip");
        final long[] last = {0L, 0L};
        String error = ModuleDownloader.downloadWithResume(server.url("/full.zip").toString(),
                target, (done, total) -> {
                    last[0] = done;
                    last[1] = total;
                }, null);
        assertNull(error);
        assertEquals(content.length, last[0]);
        assertEquals(content.length, last[1]);
    }
}
