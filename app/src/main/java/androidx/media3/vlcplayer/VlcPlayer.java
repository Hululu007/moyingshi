package androidx.media3.vlcplayer;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.mpvplayer.MpvHlsProxy;

import com.github.aiyingshi.osc.tv.player.PlaybackResourceClassifier;
import com.github.aiyingshi.osc.tv.player.PlaybackRoute;
import com.github.aiyingshi.osc.tv.player.cache.PlaybackDiskBufferStore;
import com.github.aiyingshi.osc.tv.player.engine.PlayerEngine;
import com.github.aiyingshi.osc.tv.player.exo.ExoUtil;
import com.github.aiyingshi.osc.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VLC 内核的 Media3 适配器：把 libvlc 的 {@link MediaPlayer} 包成
 * {@link SimpleBasePlayer}，与 mpv/ijk 的同款包装（MpvPlayer / IjkSimplePlayer）
 * 对齐同一套 PlayerEngine 契约。
 *
 * <p>Stage 1 范围：播/停/seek/倍速/音量、音轨与内嵌字幕轨枚举与选择、Surface 接入、
 * 错误映射。外挂字幕、字幕样式、画面调节、Anime4K、LUT、双字幕不在本阶段
 * （见 docs/vlc-player-kernel.md 的 Stage 2/4）。
 *
 * <p>libvlc API 签名按 3.4.3 官方 Javadoc 核实（3.7.8 无逐版 Javadoc，3.x 线稳定）：
 * {@code setSpuTrack(int)}、{@code getVideoTracksCount()}、
 * {@code addSlave(int, Uri, boolean)}、{@code IMedia.Slave.Type.Subtitle}。
 */
@UnstableApi
public class VlcPlayer extends SimpleBasePlayer implements IVLCVout.OnNewVideoLayoutListener {

    private static final String TAG = "TV-vlc";
    private static final long STATE_REFRESH_INTERVAL_MS = 1000;

    private static final Commands COMMANDS = new Commands.Builder()
            .add(COMMAND_PLAY_PAUSE)
            .add(COMMAND_PREPARE)
            .add(COMMAND_STOP)
            .add(COMMAND_RELEASE)
            .add(COMMAND_SET_REPEAT_MODE)
            .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(COMMAND_GET_TIMELINE)
            .add(COMMAND_GET_METADATA)
            .add(COMMAND_SET_MEDIA_ITEM)
            .add(COMMAND_CHANGE_MEDIA_ITEMS)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(COMMAND_GET_VOLUME)
            .add(COMMAND_SET_VOLUME)
            .add(COMMAND_SET_SPEED_AND_PITCH)
            .add(COMMAND_SET_VIDEO_SURFACE)
            .add(COMMAND_GET_TRACKS)
            .build();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable stateRefreshRunnable = this::refreshPlaybackState;
    private final LibVLC libVlc;
    private final MediaPlayer mediaPlayer;
    private final MpvHlsProxy hlsProxy;
    private MediaItem mediaItem;
    private Object videoOutput;
    private PlaybackParameters playbackParameters;
    private PlaybackException playerError;
    private Tracks currentTracks;
    private VideoSize videoSize;
    private String currentPlayableUrl;
    private PlaybackResourceClassifier.Classification resourceClassification;
    private long cachedPositionMs;
    private long cachedDurationMs;
    private long pendingSeekPositionMs;
    private float volume;
    private int decode;
    private int playbackState;
    private boolean playWhenReady;
    private boolean loading;
    private boolean repeatOne;
    private boolean prepared;
    private boolean mediaSet;
    private boolean newlyRenderedFirstFrame;
    private boolean renderedFirstFrameSeen;
    private volatile boolean released;

    // VLC 画面落点只在 attachViews 时按当前 surface 尺寸算一次，surface 之后改尺寸
    // （进/出全屏、切画面比例）它不跟着重算，画面就一直停在起播时的小窗口里——
    // 诊断版实测：surface 已是 2247x1264/2780x1264，画面仍约 67% 宽。
    // mpv/ijk 同款做法：在 holder 上挂回调，尺寸变了就 detach→bind 重绑一次。
    private SurfaceHolder surfaceCallbackHolder;
    private int lastBoundSurfaceWidth;
    private int lastBoundSurfaceHeight;
    private boolean rebindingSurface;

    public VlcPlayer(Context context) {
        this(context, PlayerEngine.HARD);
    }

    public VlcPlayer(Context context, int decode) {
        super(Looper.getMainLooper());
        this.decode = decode;
        // 关掉 libvlc 默认的片名 OSD，避免画面上多一行系统字幕条。
        // 网络缓存从默认 1000ms 提到 5000ms：走本地代理的高码率流（4K）上游抖动大，
        // 1s 缓存播几秒就断（黑屏转圈再续播），EXO/mpv 靠大缓存扛得住，VLC 扛不住。
        // 只影响 VLC；多占几 MB 内存，不拖首帧（缓存是上限，不是预填目标）。
        // 注意：List.of() 是不可变列表，LibVLC 构造时会在传进来的 options 上直接
        // add 默认项（3.7.8 字节码核对：构造里对入参调了 4 次 List.add，从不拷贝），
        // 传不可变列表会抛无消息的 UnsupportedOperationException，表现为 VLC
        // “根没启动”、马上回 EXO。必须传可变列表。
        libVlc = new LibVLC(context.getApplicationContext(),
                new ArrayList<>(List.of("--no-video-title-show", "--network-caching=5000")));
        mediaPlayer = new MediaPlayer(libVlc);
        mediaPlayer.setEventListener(this::onVlcEvent);
        // 与 mpv/ijk 同款：本地代理只在头里有 libvlc 表达不了的项时才启用，
        // 平时不占端口、不跑服务（见 openCurrent）。
        hlsProxy = new MpvHlsProxy(PlayerSetting.VLC);
        playbackParameters = PlaybackParameters.DEFAULT;
        currentTracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
        playbackState = Player.STATE_IDLE;
        cachedDurationMs = C.TIME_UNSET;
        pendingSeekPositionMs = C.TIME_UNSET;
        playWhenReady = true;
        volume = 1f;
    }

    public void setDecode(int decode) {
        this.decode = decode;
    }

    @Override
    protected State getState() {
        int state = playbackState;
        boolean isLoading = loading && state != Player.STATE_IDLE && state != Player.STATE_ENDED;
        boolean firstFrameEvent = newlyRenderedFirstFrame;
        newlyRenderedFirstFrame = false;
        State.Builder builder = new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(state)
                .setIsLoading(isLoading)
                .setPlayerError(playerError)
                .setRepeatMode(repeatOne ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF)
                .setPlaybackParameters(playbackParameters)
                .setVideoSize(videoSize)
                .setNewlyRenderedFirstFrame(firstFrameEvent)
                .setVolume(volume)
                .setPlaylist(mediaItem == null ? ImmutableList.of() : ImmutableList.of(mediaItemData()))
                .setCurrentMediaItemIndex(mediaItem == null ? C.INDEX_UNSET : 0);
        if (mediaItem != null) {
            long duration = duration();
            long position = position();
            long buffered = bufferedPosition(position, duration);
            builder.setContentPositionMs(isPlayingInternal()
                    ? PositionSupplier.getExtrapolating(position, playbackParameters.speed)
                    : PositionSupplier.getConstant(position));
            builder.setContentBufferedPositionMs(PositionSupplier.getConstant(buffered));
            builder.setTotalBufferedDurationMs(PositionSupplier.getConstant(Math.max(0, buffered - position)));
        }
        return builder.build();
    }

    private MediaItemData mediaItemData() {
        long duration = duration();
        return new MediaItemData.Builder(mediaItem.mediaId)
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setDurationUs(duration == C.TIME_UNSET ? C.TIME_UNSET : duration * 1000)
                .setIsSeekable(duration > 0)
                .setIsDynamic(duration == C.TIME_UNSET)
                .setTracks(currentTracks)
                .build();
    }

    public Tracks getCurrentTracksSnapshot() {
        return currentTracks;
    }

    public VideoSize getVideoSizeSnapshot() {
        return videoSize;
    }

    public PlaybackRoute.Resolution getPlaybackRouteResolution() {
        return PlaybackRoute.resolve(currentPlayableUrl);
    }

    public PlaybackResourceClassifier.Classification getResourceClassification() {
        return resourceClassification;
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        // 换片先停掉旧媒体，避免 setMedia 之前旧片继续出声。
        // 停是异步的：返回的 future 在 native stop 完成后才完成，Media3 会等它再跑
        // prepare，旧流一定先停干净。
        ListenableFuture<?> stopFuture = Futures.immediateVoidFuture();
        if (mediaSet) {
            stopFuture = stopAsync(false);
        }
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(0);
        pendingSeekPositionMs = mediaItem != null && startPositionMs > 0 ? startPositionMs : C.TIME_UNSET;
        playbackState = Player.STATE_IDLE;
        loading = false;
        currentTracks = Tracks.EMPTY;
        playerError = null;
        prepared = false;
        cachedPositionMs = Math.max(0, startPositionMs == C.TIME_UNSET ? 0 : startPositionMs);
        cachedDurationMs = C.TIME_UNSET;
        videoSize = VideoSize.UNKNOWN;
        newlyRenderedFirstFrame = false;
        renderedFirstFrameSeen = false;
        currentPlayableUrl = null;
        resourceClassification = null;
        return stopFuture;
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        if (mediaItem == null && !mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        if (mediaItems.isEmpty()) {
            ListenableFuture<?> stopFuture = stopAsync(false);
            mediaItem = null;
            invalidateState();
            return stopFuture;
        } else {
            mediaItem = mediaItems.get(0);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        ListenableFuture<?> stopFuture = stopAsync(false);
        mediaItem = null;
        playbackState = Player.STATE_IDLE;
        loading = false;
        invalidateState();
        return stopFuture;
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        openCurrent();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        if (released || mediaItem == null || !mediaSet) {
            invalidateState();
            return Futures.immediateVoidFuture();
        }
        try {
            if (playWhenReady) {
                if (playbackState == Player.STATE_ENDED) mediaPlayer.setTime(0);
                mediaPlayer.play();
            } else if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "play-when-ready failed errorType=%s", e.getClass().getSimpleName());
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        return stopAsync(true);
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        released = true;
        stopStateRefresh();
        boolean hadMedia = mediaSet;
        resetStopState(false);
        // 释放全套放后台：native stop 会等 input 线程退出，卡在缓冲读流时能拖很久，
        // 在主线程做就是这次的 ANR；detach/release/proxy/libVlc 顺序不变，只换线程。
        SettableFuture<Void> future = SettableFuture.create();
        STOP_EXECUTOR.execute(() -> {
            if (hadMedia) nativeStop();
            detachVideoOutput();
            try {
                mediaPlayer.release();
            } catch (Throwable ignored) {
            }
            try {
                hlsProxy.release();
            } catch (Throwable ignored) {
            }
            try {
                libVlc.release();
            } catch (Throwable ignored) {
            }
            future.set(null);
        });
        return future;
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        repeatOne = repeatMode == Player.REPEAT_MODE_ONE;
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (positionMs == C.TIME_UNSET) positionMs = 0;
        positionMs = Math.max(0, positionMs);
        cachedPositionMs = positionMs;
        if (!prepared || !mediaSet) {
            // 还没起播：只记目标，Playing 事件里再 seek（与 ijk 的 pendingSeek 同款做法）。
            pendingSeekPositionMs = positionMs;
            invalidateState();
            return Futures.immediateVoidFuture();
        }
        try {
            mediaPlayer.setTime(positionMs);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "seek failed errorType=%s", e.getClass().getSimpleName());
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        try {
            mediaPlayer.setRate(playbackParameters.speed);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "set rate failed errorType=%s", e.getClass().getSimpleName());
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, int volumeOperationType) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        try {
            // libvlc 音量 0..200，100 为标准音量。
            mediaPlayer.setVolume((int) (this.volume * 100));
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "set volume failed errorType=%s", e.getClass().getSimpleName());
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        this.videoOutput = videoOutput;
        detachVideoOutput();
        bindVideoOutput();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        if (videoOutput == null || videoOutput == this.videoOutput) {
            this.videoOutput = null;
            detachVideoOutput();
        }
        return Futures.immediateVoidFuture();
    }

    /** 引擎层选轨入口：audio/spu 的 libvlc 轨道 id（Format.id 里 "type:id" 的后半段）。 */
    public void setAudioTrackIndex(int vlcTrackId) {
        if (released || !mediaSet) return;
        try {
            mediaPlayer.setAudioTrack(vlcTrackId);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "set audio track failed errorType=%s", e.getClass().getSimpleName());
            return;
        }
        refreshTracks();
        invalidateState();
    }

    /** 传入 -1 表示关闭字幕（libvlc 的哨兵值）。 */
    public void setSpuTrackIndex(int vlcTrackId) {
        if (released || !mediaSet) return;
        try {
            mediaPlayer.setSpuTrack(vlcTrackId);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "set spu track failed errorType=%s", e.getClass().getSimpleName());
            return;
        }
        refreshTracks();
        invalidateState();
    }

    private void onVlcEvent(MediaPlayer.Event event) {
        if (event == null || released) return;
        // libvlc 事件应在主线程回调；不在主线程时转一下，invalidateState 必须在主线程调。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> onVlcEvent(event));
            return;
        }
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                playbackState = Player.STATE_BUFFERING;
                loading = true;
                invalidateState();
                break;
            case MediaPlayer.Event.Buffering: {
                float buffering = event.getBuffering();
                if (buffering >= 100f) {
                    if (playbackState == Player.STATE_BUFFERING) {
                        playbackState = Player.STATE_READY;
                        loading = false;
                    }
                } else {
                    playbackState = Player.STATE_BUFFERING;
                    loading = true;
                }
                invalidateState();
                break;
            }
            case MediaPlayer.Event.Playing:
                prepared = true;
                playbackState = Player.STATE_READY;
                loading = false;
                playerError = null;
                refreshTracks();
                if (pendingSeekPositionMs != C.TIME_UNSET) {
                    long target = pendingSeekPositionMs;
                    pendingSeekPositionMs = C.TIME_UNSET;
                    try {
                        mediaPlayer.setTime(target);
                    } catch (Throwable ignored) {
                    }
                }
                bindVideoOutput();
                invalidateState();
                startStateRefresh();
                break;
            case MediaPlayer.Event.Paused:
                // 暂停只是 playWhenReady=false 的确认态，READY 不变。
                invalidateState();
                break;
            case MediaPlayer.Event.Stopped:
                if (playbackState != Player.STATE_IDLE) {
                    playbackState = Player.STATE_IDLE;
                    loading = false;
                    stopStateRefresh();
                    invalidateState();
                }
                break;
            case MediaPlayer.Event.EndReached:
                if (repeatOne && mediaSet) {
                    try {
                        mediaPlayer.setTime(0);
                        mediaPlayer.play();
                    } catch (Throwable ignored) {
                    }
                    break;
                }
                playbackState = Player.STATE_ENDED;
                loading = false;
                stopStateRefresh();
                invalidateState();
                break;
            case MediaPlayer.Event.EncounteredError:
                playbackState = Player.STATE_IDLE;
                loading = false;
                prepared = false;
                stopStateRefresh();
                playerError = new PlaybackException(
                        "VLC_LOAD_FAILED",
                        null,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
                SpiderDebug.log(TAG, "encountered error url=%s", summarizeUrl());
                invalidateState();
                break;
            case MediaPlayer.Event.TimeChanged:
                cachedPositionMs = Math.max(0, event.getTimeChanged());
                // 位置靠 getState 里的 extrapolate 平滑推进，这里不 invalidate。
                break;
            case MediaPlayer.Event.LengthChanged:
                cachedDurationMs = event.getLengthChanged();
                invalidateState();
                break;
            case MediaPlayer.Event.Vout:
                if (event.getVoutCount() > 0 && !renderedFirstFrameSeen) {
                    renderedFirstFrameSeen = true;
                    newlyRenderedFirstFrame = true;
                }
                invalidateState();
                break;
            case MediaPlayer.Event.ESAdded:
            case MediaPlayer.Event.ESDeleted:
            case MediaPlayer.Event.ESSelected:
                refreshTracks();
                invalidateState();
                break;
            default:
                break;
        }
    }

    @Override
    public void onNewVideoLayout(IVLCVout vlcVout, int width, int height,
                                 int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        if (width > 0 && height > 0) {
            videoSize = new VideoSize(width, height);
        } else {
            videoSize = VideoSize.UNKNOWN;
        }
        refreshTracks();
        invalidateState();
    }

    private void openCurrent() {
        if (mediaItem == null || mediaItem.localConfiguration == null) return;
        if (released) return;
        try {
            playbackState = Player.STATE_BUFFERING;
            loading = true;
            playerError = null;
            prepared = false;
            newlyRenderedFirstFrame = false;
            renderedFirstFrameSeen = false;
            Uri sourceUri = mediaItem.localConfiguration.uri;
            Map<String, String> headers = ExoUtil.extractHeaders(mediaItem);
            String playableUrl = sourceUri.toString();
            currentPlayableUrl = playableUrl;
            resourceClassification = PlaybackResourceClassifier.classifyRequest(
                    playableUrl, mediaItem.localConfiguration.mimeType, mediaItem.localConfiguration.mimeType);
            // libvlc 的 media options 只能传 UA/Referer：头里一旦有 Cookie 或任意自定义头，
            // 直连必被源拒绝（EncounteredError → VLC_LOAD_FAILED），而 EXO/mpv/ijk 都传全量头。
            // 此时走现有本地代理 MpvHlsProxy（mpv/ijk 同款）：代理用 OkHttp 带全量头向上游取流，
            // LibVLC 只需连 127.0.0.1。头里只有 UA/Referer（或无头）时保持直连，零行为变化。
            // 非 http(s)（rtsp/file 等）代理帮不上忙，同样保持直连。
            Uri mediaUri = sourceUri;
            hlsProxy.clear();
            if (needsProxyHeaders(headers) && isHttpUrl(playableUrl)) {
                String mediaKey = PlaybackDiskBufferStore.mediaKey(mediaItem);
                String mimeType = mediaItem.localConfiguration.mimeType;
                if (isLikelyDash(mimeType, playableUrl)) {
                    mediaUri = Uri.parse(hlsProxy.proxyDash(playableUrl, headers, mediaKey));
                } else if (isLikelyHls(mimeType, playableUrl)) {
                    mediaUri = Uri.parse(hlsProxy.proxy(playableUrl, headers, mediaKey));
                } else {
                    mediaUri = Uri.parse(hlsProxy.proxyFile(playableUrl, headers, mediaKey));
                }
                SpiderDebug.log(TAG, "proxy enabled mime=%s headers=%d",
                        mimeType, headers.size());
            }
            Media media = new Media(libVlc, mediaUri);
            try {
                applyHttpOptions(media, headers);
                media.setHWDecoderEnabled(decode == PlayerEngine.HARD, false);
                mediaPlayer.setMedia(media);
                mediaSet = true;
            } finally {
                // setMedia 后 player 内部已 retain，立即 release 是官方示例写法。
                media.release();
            }
            SpiderDebug.log(TAG, "open decode=%s headers=%d urlLen=%d",
                    decode == PlayerEngine.HARD ? "hard" : "soft",
                    headers.size(), playableUrl.length());
            // 关键顺序：先接 surface 再 play，否则会出现“只有声音没有画面”。
            bindVideoOutput();
            invalidateState();
            startStateRefresh();
        } catch (Throwable e) {
            playerError = new PlaybackException(
                    "VLC_LOAD_FAILED",
                    e,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            playbackState = Player.STATE_IDLE;
            loading = false;
            prepared = false;
            mediaSet = false;
            stopStateRefresh();
            SpiderDebug.log(TAG, "open failed errorType=%s", e.getClass().getSimpleName());
            invalidateState();
        }
    }

    /**
     * 只传 UA 与 Referer（libvlc media options 有对应项）。
     * Cookie 与任意自定义头 libvlc 3.x 没有直达的 media option，头里一旦有它们，
     * openCurrent 会改走本地代理 MpvHlsProxy（代理带全量头取流），而不是在这里硬传。
     */
    private void applyHttpOptions(IMedia media, Map<String, String> headers) {
        if (media == null || headers == null || headers.isEmpty()) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            if ("User-Agent".equalsIgnoreCase(key)) {
                media.addOption(":http-user-agent=" + value);
            } else if ("Referer".equalsIgnoreCase(key)) {
                media.addOption(":http-referrer=" + value);
            }
        }
    }

    /**
     * 头里是否含有 libvlc media options 表达不了的项（Cookie 与任意自定义头）。
     * 只有 UA/Referer（或无头）时直连即可（applyHttpOptions 已覆盖），其余一律走本地代理。
     */
    private static boolean needsProxyHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return false;
        for (String key : headers.keySet()) {
            if (key == null) continue;
            if ("User-Agent".equalsIgnoreCase(key) || "Referer".equalsIgnoreCase(key)) continue;
            return true;
        }
        return false;
    }

    private static boolean isHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String scheme = Uri.parse(url).getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /** 与 IjkSimplePlayer 同款判定：mime 或 URL 后缀含 m3u8。 */
    private static boolean isLikelyHls(String mimeType, String url) {
        if (MimeTypes.APPLICATION_M3U8.equals(mimeType)
                || "application/vnd.apple.mpegurl".equalsIgnoreCase(mimeType)
                || "application/x-mpegurl".equalsIgnoreCase(mimeType)
                || "hls".equalsIgnoreCase(mimeType)) {
            return true;
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        return lower.contains("m3u8");
    }

    /** 与 IjkSimplePlayer 同款判定：mime 或 URL 含 mpd。 */
    private static boolean isLikelyDash(String mimeType, String url) {
        if (MimeTypes.APPLICATION_MPD.equals(mimeType)
                || "application/dash+xml".equalsIgnoreCase(mimeType)
                || "dash".equalsIgnoreCase(mimeType)) {
            return true;
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        return lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("format=mpd");
    }

    /**
     * 停 native 播放器的专用单线程：libvlc_media_player_stop 是同步调用，会等 input
     * 线程退出才返回；VLC 卡在缓冲读流时这一步能拖几秒甚至更久，在主线程调就是 ANR
     *（v89 实测：主线程 vlc_cond_wait → libvlc_media_player_stop，Input dispatching
     * timed out）。状态复位仍在主线程立即做，只把阻塞调用挪走；返回的 future 在
     * native stop 完成后才完成，Media3 会等它再跑下一个操作。
     */
    private static final ExecutorService STOP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vlc-stop");
        thread.setDaemon(true);
        return thread;
    });

    /** 主线程上立即做的状态复位（不含阻塞调用）。 */
    private void resetStopState(boolean notifyState) {
        mediaSet = false;
        prepared = false;
        loading = false;
        playbackState = Player.STATE_IDLE;
        stopStateRefresh();
        if (notifyState) invalidateState();
    }

    /** 只调阻塞的 native stop，必须在 STOP_EXECUTOR 上跑，绝不在主线程调。 */
    private void nativeStop() {
        try {
            mediaPlayer.stop();
        } catch (Throwable ignored) {
        }
    }

    /** 异步停播：状态立即复位，native stop 放后台，future 在 stop 完成后才完成。 */
    private ListenableFuture<?> stopAsync(boolean notifyState) {
        boolean hadMedia = mediaSet;
        resetStopState(notifyState);
        if (!hadMedia) return Futures.immediateVoidFuture();
        SettableFuture<Void> future = SettableFuture.create();
        STOP_EXECUTOR.execute(() -> {
            nativeStop();
            future.set(null);
        });
        return future;
    }

    private void startStateRefresh() {
        mainHandler.removeCallbacks(stateRefreshRunnable);
        mainHandler.postDelayed(stateRefreshRunnable, STATE_REFRESH_INTERVAL_MS);
    }

    private void stopStateRefresh() {
        mainHandler.removeCallbacks(stateRefreshRunnable);
    }

    private void refreshPlaybackState() {
        if (mediaItem == null || playbackState == Player.STATE_IDLE
                || playbackState == Player.STATE_ENDED || playerError != null) return;
        long length = safeLength();
        if (length > 0) cachedDurationMs = length;
        invalidateState();
        startStateRefresh();
    }

    private long safeLength() {
        try {
            return mediaPlayer.getLength();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean isPlayingInternal() {
        try {
            return playbackState == Player.STATE_READY && mediaPlayer.isPlaying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private long position() {
        try {
            long actual = Math.max(0, mediaPlayer.getTime());
            if (pendingSeekPositionMs == C.TIME_UNSET) return actual;
            boolean reached = Math.abs(actual - pendingSeekPositionMs) <= 1500;
            if (reached) {
                pendingSeekPositionMs = C.TIME_UNSET;
                return actual;
            }
            return pendingSeekPositionMs;
        } catch (Throwable ignored) {
            return pendingSeekPositionMs == C.TIME_UNSET ? Math.max(0, cachedPositionMs) : pendingSeekPositionMs;
        }
    }

    private long duration() {
        long duration = cachedDurationMs > 0 ? cachedDurationMs : safeLength();
        return duration > 0 ? duration : C.TIME_UNSET;
    }

    private long bufferedPosition(long position, long duration) {
        // libvlc Java 绑定不暴露已缓冲位置：缓冲中按当前位置算，其余按总时长算。
        if (playbackState == Player.STATE_BUFFERING) return position;
        return duration == C.TIME_UNSET ? position : duration;
    }

    private void refreshTracks() {
        if (released || !mediaSet) return;
        try {
            List<Tracks.Group> groups = new ArrayList<>();
            int selectedVideo = safeTrackId(() -> mediaPlayer.getVideoTrack());
            int selectedAudio = safeTrackId(() -> mediaPlayer.getAudioTrack());
            int selectedSpu = safeTrackId(() -> mediaPlayer.getSpuTrack());
            MediaPlayer.TrackDescription[] videoTracks = mediaPlayer.getVideoTracks();
            int firstVideoId = firstVideoTrackId(videoTracks);
            if (firstVideoId >= 0 && !hasTrackId(videoTracks, selectedVideo)) {
                // libvlc 没报出已选视频轨（getVideoTrack() 返回 -1 或与描述 id 对不上）时，
                // 像 mpv/ijk 一样把第一条视频轨当作已选上报：只改上报给 Media3 的 Tracks，
                // 不动 libvlc 实际选轨。否则 PlayerView 认为没有视频轨，会一直把海报
                //（exo_artwork）盖在画面上，只剩声音和进度在走。
                SpiderDebug.log(TAG, "video track unselected, fallback to first id=%d reported=%d", firstVideoId, selectedVideo);
                selectedVideo = firstVideoId;
            }
            addTrackGroup(groups, C.TRACK_TYPE_VIDEO, videoTracks, selectedVideo);
            addTrackGroup(groups, C.TRACK_TYPE_AUDIO, mediaPlayer.getAudioTracks(), selectedAudio);
            addTrackGroup(groups, C.TRACK_TYPE_TEXT, mediaPlayer.getSpuTracks(), selectedSpu);
            currentTracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "tracks refresh failed errorType=%s", e.getClass().getSimpleName());
        }
    }

    private static boolean hasTrackId(MediaPlayer.TrackDescription[] descriptions, int id) {
        if (descriptions == null) return false;
        for (MediaPlayer.TrackDescription description : descriptions) {
            if (description != null && description.id == id) return true;
        }
        return false;
    }

    /** 返回第一条非空视频轨的 libvlc id，没有则返回 -1。 */
    private static int firstVideoTrackId(MediaPlayer.TrackDescription[] descriptions) {
        if (descriptions == null) return -1;
        for (MediaPlayer.TrackDescription description : descriptions) {
            if (description != null) return description.id;
        }
        return -1;
    }

    private int safeTrackId(TrackIdSupplier supplier) {
        try {
            return supplier.get();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private interface TrackIdSupplier {
        int get();
    }

    private void addTrackGroup(List<Tracks.Group> groups, int type,
                               MediaPlayer.TrackDescription[] descriptions, int selectedId) {
        if (descriptions == null) return;
        // libvlc 的 TrackDescription 只有 id/name，没有编码信息，只能用通用占位 mime。
        // 但必须给一个：TrackGroup 的 type 是从 Format.sampleMimeType（为空时看
        // containerMimeType）推导的；不给 mime 时 type 全是 UNKNOWN，PlayerView 的
        // isTypeSelected(VIDEO) 永远为 false，海报就一直盖在画面上——v91 只改了
        // selected 标志，没改 type，所以没生效。mpv/ijk 都给了 mime，只有 VLC 漏了。
        String sampleMimeType = switch (type) {
            case C.TRACK_TYPE_VIDEO -> MimeTypes.VIDEO_UNKNOWN;
            case C.TRACK_TYPE_AUDIO -> MimeTypes.AUDIO_UNKNOWN;
            default -> MimeTypes.TEXT_VTT;
        };
        for (MediaPlayer.TrackDescription description : descriptions) {
            if (description == null) continue;
            String label = description.name == null || description.name.isEmpty() ? null : description.name;
            Format format = new Format.Builder()
                    .setId(type + ":" + description.id)
                    .setLabel(label)
                    .setSampleMimeType(sampleMimeType)
                    .build();
            boolean selected = description.id == selectedId;
            TrackGroup group = new TrackGroup("vlc:" + type + ":" + description.id, format);
            groups.add(new Tracks.Group(group, false,
                    new int[]{C.FORMAT_HANDLED}, new boolean[]{selected}));
        }
    }

    /**
     * Surface 接入：Media3 的 PlayerView 在 surface 建好/销毁时调
     * setVideoSurface(surface)，经 SimpleBasePlayer 走到这里。
     * 顺序必须是 setVideoSurface → attachViews → play，否则黑屏只有声音。
     */
    private void bindVideoOutput() {
        if (released || mediaPlayer == null || videoOutput == null) return;
        try {
            IVLCVout vout = mediaPlayer.getVLCVout();
            if (videoOutput instanceof SurfaceView view) {
                vout.setVideoView(view);
            } else if (videoOutput instanceof TextureView view) {
                vout.setVideoView(view);
            } else if (videoOutput instanceof SurfaceHolder holder) {
                Surface surface = holder.getSurface();
                if (surface == null || !surface.isValid()) return;
                vout.setVideoSurface(surface, holder);
            } else if (videoOutput instanceof Surface surface) {
                if (!surface.isValid()) return;
                vout.setVideoSurface(surface, null);
            } else {
                return;
            }
            if (!vout.areViewsAttached()) {
                vout.attachViews(this);
                // 落点按这次 attach 时的 surface 尺寸算；之后尺寸再变由 surfaceCallback 重绑。
                // 注意：只有真正 attach 才更新，没 attach 时不更新——否则一次没重绑的
                // bind 会把新尺寸记成“已绑定”，surfaceChanged 再来时就直接跳过了。
                updateLastBoundSurfaceSize();
            }
            // 挂在 holder 上（mpv/ijk 同款）：surface 改尺寸时重绑，libVLC 才重新落点。
            // 放 attach 之后，避免 addCallback 立即回放 surfaceCreated 造成重入。
            registerSurfaceCallback(surfaceHolderOf(videoOutput));
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "bind surface failed errorType=%s", e.getClass().getSimpleName());
        }
    }

    private void detachVideoOutput() {
        if (mediaPlayer == null) return;
        try {
            IVLCVout vout = mediaPlayer.getVLCVout();
            if (vout.areViewsAttached()) vout.detachViews();
        } catch (Throwable ignored) {
        }
    }

    /**
     * surface 尺寸变了：detach→bind 逼 libVLC 按当前 surface 重新落点。
     * 只换视频输出，不碰播放状态（不断流、不 seek、不改音量/倍速）。
     */
    private void rebindVideoOutput() {
        if (released || rebindingSurface || mediaPlayer == null || videoOutput == null) return;
        rebindingSurface = true;
        try {
            detachVideoOutput();
            bindVideoOutput();
        } finally {
            rebindingSurface = false;
        }
    }

    /**
     * 画面比例/全屏导致 surface 尺寸变了时用：读当前 holder 尺寸，
     * 与上次 attach 时的尺寸不同才 detach→bind，否则直接返回。
     * 给 PlaybackActivity 的 applyResizeModeNow 兜底（surfaceChanged 没触发的路径），
     * 不碰播放状态，不动 EXO/mpv/ijk。
     */
    public void rebindVideoOutputForResize() {
        if (released || mediaPlayer == null || videoOutput == null || !mediaSet) return;
        SurfaceHolder holder = surfaceHolderOf(videoOutput);
        if (holder == null || holder.getSurfaceFrame() == null) return;
        Rect frame = holder.getSurfaceFrame();
        if (frame.width() <= 0 || frame.height() <= 0) return;
        if (frame.width() == lastBoundSurfaceWidth && frame.height() == lastBoundSurfaceHeight) return;
        SpiderDebug.log(TAG, "rebind for resize surface=%dx%d lastBound=%dx%d",
                frame.width(), frame.height(), lastBoundSurfaceWidth, lastBoundSurfaceHeight);
        rebindVideoOutput();
    }

    @Nullable
    private static SurfaceHolder surfaceHolderOf(@Nullable Object output) {
        if (output instanceof SurfaceView view) return view.getHolder();
        if (output instanceof SurfaceHolder holder) return holder;
        return null;
    }

    private void updateLastBoundSurfaceSize() {
        SurfaceHolder holder = surfaceHolderOf(videoOutput);
        if (holder == null || holder.getSurfaceFrame() == null) return;
        Rect frame = holder.getSurfaceFrame();
        if (frame.width() > 0 && frame.height() > 0) {
            lastBoundSurfaceWidth = frame.width();
            lastBoundSurfaceHeight = frame.height();
        }
    }

    private void registerSurfaceCallback(@Nullable SurfaceHolder holder) {
        if (holder == null || holder == surfaceCallbackHolder) return;
        if (surfaceCallbackHolder != null) {
            try {
                surfaceCallbackHolder.removeCallback(surfaceCallback);
            } catch (Throwable ignored) {
            }
        }
        surfaceCallbackHolder = holder;
        try {
            holder.addCallback(surfaceCallback);
        } catch (Throwable ignored) {
        }
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            if (released) return;
            // 新 surface：按 mpv/ijk 同款做法重新接一次；surfaceChanged 紧接着会带真实尺寸再来。
            bindVideoOutput();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (released || width <= 0 || height <= 0) return;
            if (width == lastBoundSurfaceWidth && height == lastBoundSurfaceHeight) return;
            // surface 尺寸变了（进/出全屏、切画面比例），但 libVLC 的落点还停在上一次
            // attach 时的尺寸上。没起播时 openCurrent/Playing 事件里自会 bind，这里只处理已起播。
            if (!mediaSet) return;
            SpiderDebug.log(TAG, "surface resized %dx%d lastBound=%dx%d rebind",
                    width, height, lastBoundSurfaceWidth, lastBoundSurfaceHeight);
            rebindVideoOutput();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (released) return;
            detachVideoOutput();
        }
    };

    private String summarizeUrl() {
        String url = currentPlayableUrl;
        if (url == null) return "null";
        int query = url.indexOf('?');
        String shown = query >= 0 ? url.substring(0, query) : url;
        return shown.length() > 80 ? shown.substring(0, 80) + "..." : shown;
    }
}
