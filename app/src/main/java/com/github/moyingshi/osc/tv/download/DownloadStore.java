package com.github.moyingshi.osc.tv.download;

import com.github.moyingshi.osc.tv.App;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载任务持久化：内部存储 download_tasks.json（应用私有目录，不占 Download 文件夹）。
 * 写临时文件后改名，避免中途被杀写坏原文件。
 */
public final class DownloadStore {

    private static final String FILE_NAME = "download_tasks.json";

    private DownloadStore() {
    }

    private static File file() {
        return new File(App.get().getFilesDir(), FILE_NAME);
    }

    public static synchronized List<DownloadTask> load() {
        File f = file();
        if (!f.exists()) return new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<ArrayList<DownloadTask>>() {
            }.getType();
            List<DownloadTask> tasks = new Gson().fromJson(reader, type);
            return tasks == null ? new ArrayList<>() : tasks;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static synchronized void save(List<DownloadTask> tasks) {
        File target = file();
        File tmp = new File(App.get().getFilesDir(), FILE_NAME + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            new Gson().toJson(tasks, writer);
            writer.flush();
        } catch (Exception e) {
            return;
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            tmp.renameTo(target);
        }
    }
}
