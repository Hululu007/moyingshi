package com.github.moyingshi.osc.tv.player.engine;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.vlcplayer.VlcPlayer;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.bean.Track;
import com.github.moyingshi.osc.tv.module.ModuleDefinition;
import com.github.moyingshi.osc.tv.module.ModuleManager;
import com.github.moyingshi.osc.tv.player.PlaybackResourceClassifier;
import com.github.moyingshi.osc.tv.player.PlaybackRoute;
import com.github.moyingshi.osc.tv.player.PlaybackTrace;
import com.github.moyingshi.osc.tv.player.exo.ExoUtil;
import com.github.moyingshi.osc.tv.player.exo.TrackUtil;
import com.github.moyingshi.osc.tv.utils.ResUtil;

import java.util.List;

/**
 * VLC 播放内核（Stage 1 最小可用）。
 *
 * <p>与 IjkPlayerEngine 同款薄壳：start 走 ExoUtil.getMediaItem + setMediaItem /
 * prepare / play，选轨走 {@link #setTrack(List)}，其余能力缺省。
 * Stage 1 诚实不支持：画面调节、Anime4K、LUT、双字幕、字幕样式
 * （PlayerEngine 的 supports* 缺省 false，面板按现有逻辑置灰）。
 * 外挂字幕与 HTTP 任意头是 Stage 2 的事。
 */
@UnstableApi
public class VlcPlayerEngine implements PlayerEngine {

    static final String ERROR_LOAD_FAILED = "VLC_LOAD_FAILED";

    private VlcPlayer player;
    private PlaySpec spec;
    private boolean playWhenReady;
    private int decode;

    public VlcPlayerEngine(int decode, Player.Listener listener) {
        this.decode = decode;
        this.player = buildPlayer(listener);
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        player.release();
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "rebuild vlc decode=%d", decode);
        return player = buildPlayer(listener);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
        player.setDecode(decode);
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        start(spec, true);
    }

    @Override
    public void start(PlaySpec spec, boolean playWhenReady) {
        start(spec, 0, playWhenReady);
    }

    @Override
    public void start(PlaySpec spec, long position, boolean playWhenReady) {
        this.spec = spec;
        this.playWhenReady = playWhenReady;
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "start vlc decode=%d position=%d play=%s urlLen=%d headers=%d", decode, position, playWhenReady, spec.getUrl() == null ? 0 : spec.getUrl().length(), spec.getHeaders() == null ? 0 : spec.getHeaders().size());
        MediaItem item = ExoUtil.getMediaItem(spec, decode);
        if (position > 0) player.setMediaItem(item, position);
        else player.setMediaItem(item);
        player.prepare();
        if (playWhenReady) player.play();
        else player.pause();
    }

    @Override
    public void restart(PlaySpec spec, long position, boolean playWhenReady) {
        player.stop();
        start(spec, position, playWhenReady);
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return TimelineStreamKindPolicy.isLive(player.getDuration(), player.isCurrentMediaItemLive());
    }

    @Override
    public boolean isVod() {
        return TimelineStreamKindPolicy.isVod(player.getDuration(), player.isCurrentMediaItemLive());
    }

    @Override
    public PlaybackResourceClassifier.Classification getResourceClassification() {
        return player.getResourceClassification();
    }

    @Override
    public PlaybackRoute.Resolution getEffectivePlaybackRoute() {
        PlaybackRoute.Resolution current = player.getPlaybackRouteResolution();
        if (current.route() != PlaybackRoute.OTHER) return current;
        return spec == null ? current : spec.getPlaybackRoute();
    }

    /**
     * 选轨：Track.getPlayerId() 是 VlcPlayer 造的 "type:vlcId"
     *（见 VlcPlayer.addTrackGroup），照 mpv 的 parseMpvTrackId 同款解析。
     * 文本轨 isDisabled 表示关闭字幕（libvlc 哨兵值 -1）。
     */
    @Override
    public void setTrack(List<Track> tracks) {
        for (Track track : tracks) {
            if (track.isDisabled() && track.getType() == C.TRACK_TYPE_TEXT) {
                player.setSpuTrackIndex(-1);
                continue;
            }
            Integer vlcId = parseVlcTrackId(track.getPlayerId());
            if (vlcId == null) continue;
            if (track.getType() == C.TRACK_TYPE_AUDIO) player.setAudioTrackIndex(vlcId);
            else if (track.getType() == C.TRACK_TYPE_TEXT) player.setSpuTrackIndex(vlcId);
        }
    }

    @Override
    public void resetTrack() {
        // 与 ijk 同款空实现：新片起播时 libvlc 自动选轨，手动选轨只走 setTrack。
    }

    @Override
    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracksSnapshot();
    }

    @Override
    public Format getVideoFormat() {
        return TrackUtil.selectedFormat(getCurrentTracks(), C.TRACK_TYPE_VIDEO);
    }

    @Override
    public PlaybackFactsSnapshot getPlaybackFactsSnapshot() {
        Format video = TrackUtil.explicitlySelectedFormat(getCurrentTracks(), C.TRACK_TYPE_VIDEO);
        Format audio = TrackUtil.explicitlySelectedFormat(getCurrentTracks(), C.TRACK_TYPE_AUDIO);
        return new PlaybackFactsSnapshot(
                video,
                audio,
                video,
                audio,
                "",
                "",
                DecoderKind.UNKNOWN,
                null,
                "",
                "",
                null);
    }

    @Override
    public String getPlaybackTraceId() {
        return spec == null ? PlaybackTrace.NONE : spec.getPlaybackTraceId();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        String message = e == null ? null : e.getMessage();
        if (message != null && message.startsWith(ERROR_LOAD_FAILED)) return ResUtil.getString(R.string.error_play_vlc);
        return e == null ? ResUtil.getString(R.string.error_play_vlc) : e.getMessage();
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "handleError vlc code=%d message=%s action=fatal", e == null ? PlaybackException.ERROR_CODE_UNSPECIFIED : e.errorCode, e == null ? "none" : e.getMessage());
        return ErrorAction.FATAL;
    }

    private VlcPlayer buildPlayer(Player.Listener listener) {
        // VLC 的原生库由第三方绑定在 LibVLC 构造时加载，应用层从不显式加载：
        // 必须在 new VlcPlayer 之前 System.load 预载（先 libvlc 后 libvlcjni），
        // 否则绑定找不到库直接抛 UnsatisfiedLinkError。模块未安装/损坏时这里抛
        // IllegalStateException，由 PlayerManager.buildEngine 接住回落 EXO。
        ModuleManager modules = ModuleManager.getInstance(App.get());
        if (!modules.ensureLoaded(ModuleDefinition.VLC)) {
            if (!modules.isInstalled(ModuleDefinition.VLC.id)) {
                throw new IllegalStateException("VLC 模块未安装，请到模块里面下载 VLC");
            }
            throw new IllegalStateException("VLC 模块加载失败，请到模块管理里重新下载 VLC",
                    modules.getLoadError(ModuleDefinition.VLC.id));
        }
        VlcPlayer player = new VlcPlayer(App.get(), decode);
        player.addListener(listener);
        return player;
    }

    private static Integer parseVlcTrackId(String playerId) {
        if (playerId == null) return null;
        int index = playerId.indexOf(':');
        if (index < 0 || index + 1 >= playerId.length()) return null;
        try {
            return Integer.parseInt(playerId.substring(index + 1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
