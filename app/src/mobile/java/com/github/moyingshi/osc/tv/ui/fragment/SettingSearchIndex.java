package com.github.moyingshi.osc.tv.ui.fragment;

import android.content.Context;

import com.github.moyingshi.osc.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings-search index: every row on the main settings page plus every row on the
 * 9 sub-pages. A tap opens the sub-page (page >= 0) or deep-links the main-page row
 * (rowId != null, via SettingFragment.requestRow).
 */
public final class SettingSearchIndex {

    public static final class Entry {
        public final int titleRes;
        public final int subtitleRes;
        public final int destRes;
        public final int page;
        public final String rowId;

        private Entry(int titleRes, int subtitleRes, int destRes, int page, String rowId) {
            this.titleRes = titleRes;
            this.subtitleRes = subtitleRes;
            this.destRes = destRes;
            this.page = page;
            this.rowId = rowId;
        }
    }

    private static Entry main(int titleRes, int subtitleRes, String rowId) {
        return new Entry(titleRes, subtitleRes, R.string.nav_setting, -1, rowId);
    }

    private static Entry sub(int titleRes, int destRes, int page) {
        return new Entry(titleRes, 0, destRes, page, null);
    }

    public static List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        // Main settings page rows (same titles/subtitles as the grouped cards).
        entries.add(main(R.string.setting_vod, 0, SettingFragment.ROW_VOD));
        entries.add(main(R.string.setting_live, 0, SettingFragment.ROW_LIVE));
        entries.add(main(R.string.setting_wall, 0, SettingFragment.ROW_WALL));
        entries.add(main(R.string.setting_appearance, R.string.setting_appearance_sub, SettingFragment.ROW_APPEARANCE));
        entries.add(main(R.string.setting_enhance, R.string.setting_enhance_sub, SettingFragment.ROW_WIDGET));
        entries.add(main(R.string.setting_player, R.string.setting_player_sub, SettingFragment.ROW_PLAYER));
        entries.add(main(R.string.setting_danmaku, R.string.setting_danmaku_sub, SettingFragment.ROW_DANMAKU));
        entries.add(main(R.string.setting_subtitle, R.string.setting_subtitle_sub, SettingFragment.ROW_SUBTITLE));
        entries.add(main(R.string.setting_ai, R.string.setting_ai_sub, SettingFragment.ROW_AI));
        entries.add(main(R.string.setting_ad, R.string.setting_ad_sub, SettingFragment.ROW_AD));
        entries.add(main(R.string.setting_tmdb, R.string.setting_tmdb_sub, SettingFragment.ROW_ACCOUNT));
        entries.add(main(R.string.setting_personal, R.string.setting_personal_sub, SettingFragment.ROW_PERSONAL));
        entries.add(main(R.string.setting_local_source, R.string.setting_local_source_sub, SettingFragment.ROW_LOCAL_SOURCE));
        entries.add(main(R.string.setting_lab, R.string.setting_lab_sub, SettingFragment.ROW_LAB));
        entries.add(main(R.string.setting_git_cloud, R.string.setting_gitcloud_sub, SettingFragment.ROW_GITCLOUD));
        entries.add(main(R.string.setting_one_key_sync, R.string.setting_onekey_sub, SettingFragment.ROW_ONEKEY));
        entries.add(main(R.string.setting_drive_check, R.string.setting_drive_sub, SettingFragment.ROW_DRIVE));
        entries.add(main(R.string.setting_site_health, R.string.setting_site_health_sub, SettingFragment.ROW_SITE_HEALTH));
        entries.add(main(R.string.setting_cache, R.string.setting_cache_sub, SettingFragment.ROW_CACHE));
        entries.add(main(R.string.setting_backup, R.string.setting_backup_sub, SettingFragment.ROW_BACKUP));
        entries.add(main(R.string.setting_restore, R.string.setting_restore_sub, SettingFragment.ROW_RESTORE));
        entries.add(main(R.string.setting_doh, R.string.setting_doh_sub, SettingFragment.ROW_DOH));
        entries.add(main(R.string.setting_incognito, R.string.setting_incognito_sub, SettingFragment.ROW_INCOGNITO));
        entries.add(main(R.string.setting_about, R.string.setting_about_sub, SettingFragment.ROW_ABOUT));
        // Player page (2).
        entries.add(sub(R.string.player_render, R.string.setting_player, 2));
        entries.add(sub(R.string.player_button_config, R.string.setting_player, 2));
        entries.add(sub(R.string.player_pad_live, R.string.setting_player, 2));
        entries.add(sub(R.string.player_exo_4k_compat, R.string.setting_player, 2));
        entries.add(sub(R.string.player_kernel, R.string.setting_player, 2));
        entries.add(sub(R.string.player_mpv_config, R.string.setting_player, 2));
        entries.add(sub(R.string.player_mpv_render, R.string.setting_player, 2));
        entries.add(sub(R.string.player_bluray_menu, R.string.setting_player, 2));
        entries.add(sub(R.string.player_scale, R.string.setting_player, 2));
        entries.add(sub(R.string.player_lut, R.string.setting_player, 2));
        entries.add(sub(R.string.player_caption, R.string.setting_player, 2));
        entries.add(sub(R.string.player_osd, R.string.setting_player, 2));
        entries.add(sub(R.string.osd_media_badge_position, R.string.setting_player, 2));
        entries.add(sub(R.string.player_buffer, R.string.setting_player, 2));
        entries.add(sub(R.string.player_buffer_bytes, R.string.setting_player, 2));
        entries.add(sub(R.string.player_back_buffer, R.string.setting_player, 2));
        entries.add(sub(R.string.player_cache, R.string.setting_player, 2));
        entries.add(sub(R.string.player_preload, R.string.setting_player, 2));
        entries.add(sub(R.string.player_preload_threads, R.string.setting_player, 2));
        entries.add(sub(R.string.player_preload_size, R.string.setting_player, 2));
        entries.add(sub(R.string.player_preload_time, R.string.setting_player, 2));
        entries.add(sub(R.string.player_preload_ahead, R.string.setting_player, 2));
        entries.add(sub(R.string.player_preload_pause, R.string.setting_player, 2));
        entries.add(sub(R.string.player_auto_play, R.string.setting_player, 2));
        entries.add(sub(R.string.player_auto_change, R.string.setting_player, 2));
        entries.add(sub(R.string.player_defer_flag_switch, R.string.setting_player, 2));
        entries.add(sub(R.string.player_remember_brightness, R.string.setting_player, 2));
        entries.add(sub(R.string.player_live_source_fallback, R.string.setting_player, 2));
        entries.add(sub(R.string.player_failure_fallback, R.string.setting_player, 2));
        entries.add(sub(R.string.player_speed, R.string.setting_player, 2));
        entries.add(sub(R.string.player_tunnel, R.string.setting_player, 2));
        entries.add(sub(R.string.player_music_notification, R.string.setting_player, 2));
        entries.add(sub(R.string.player_audio_book_notification, R.string.setting_player, 2));
        entries.add(sub(R.string.player_audio_decode, R.string.setting_player, 2));
        entries.add(sub(R.string.player_audio_passthrough, R.string.setting_player, 2));
        entries.add(sub(R.string.player_video_decode, R.string.setting_player, 2));
        entries.add(sub(R.string.player_aac_track, R.string.setting_player, 2));
        entries.add(sub(R.string.player_background, R.string.setting_player, 2));
        entries.add(sub(R.string.player_ua, R.string.setting_player, 2));
        // Enhance page (3).
        entries.add(sub(R.string.setting_lab, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_drive_check, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_site_name, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_local_reader, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_novel_source, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_comic_source, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_audio_source, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_short_drama_source, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_plugin_manager, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_debug_log, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_search_current_site, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_home_widget, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_site_health_sort, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_manage_page, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_remote_trust, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_git_cloud, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_shell_proxy, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_shell_proxy_config, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_custom_csp, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_web_home_extension, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_web_home_theme, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_web_home_fullscreen, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_csp_warmup, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_playback_artwork_wall, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_viewing_record_sync, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_login_state, R.string.setting_enhance, 3));
        entries.add(sub(R.string.setting_one_key_sync, R.string.setting_enhance, 3));
        // Danmaku page (4).
        entries.add(sub(R.string.danmaku_load, R.string.setting_danmaku, 4));
        entries.add(sub(R.string.danmaku_api, R.string.setting_danmaku, 4));
        entries.add(sub(R.string.danmaku_auto_load, R.string.setting_danmaku, 4));
        entries.add(sub(R.string.danmaku_spider_first, R.string.setting_danmaku, 4));
        // Personal page (5).
        entries.add(sub(R.string.setting_search_thread, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_following, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_auto_backup, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_playback_overlay, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_play_back_to_detail, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_episode_history, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_global_history, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_interface_failover, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_play_speed, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_group_rule, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_search_ui, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_search_column, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_site_column, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_search_result_sort, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_reset_app, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_app_branding, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_touch_optimization, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_liquid_glass, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_short_drama_collected, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_glass_blur, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_glass_distortion, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_glass_translucency, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_glass_dispersion, R.string.setting_personal, 5));
        entries.add(sub(R.string.setting_glass_reset, R.string.setting_personal, 5));
        // Subtitle page (6).
        entries.add(sub(R.string.player_subtitle_auto_match, R.string.setting_subtitle, 6));
        entries.add(sub(R.string.player_subtitle_language, R.string.setting_subtitle, 6));
        entries.add(sub(R.string.player_subtitle_source, R.string.setting_subtitle, 6));
        // TMDB page (7).
        entries.add(sub(R.string.setting_tmdb_source, R.string.setting_tmdb, 7));
        entries.add(sub(R.string.setting_detail_open_mode, R.string.setting_tmdb, 7));
        entries.add(sub(R.string.setting_detail_theme_mode, R.string.setting_tmdb, 7));
        entries.add(sub(R.string.setting_tmdb_match_mode, R.string.setting_tmdb, 7));
        entries.add(sub(R.string.setting_tmdb_episode_file_size, R.string.setting_tmdb, 7));
        entries.add(sub(R.string.setting_history_aggregation, R.string.setting_tmdb, 7));
        // AI page (8).
        entries.add(sub(R.string.setting_ai_recommendation, R.string.setting_ai, 8));
        entries.add(sub(R.string.setting_personal_recommendation, R.string.setting_ai, 8));
        entries.add(sub(R.string.setting_recommendation_feedback, R.string.setting_ai, 8));
        entries.add(sub(R.string.player_subtitle_realtime_model, R.string.setting_ai, 8));
        entries.add(sub(R.string.player_subtitle_ai_chunk_count, R.string.setting_ai, 8));
        entries.add(sub(R.string.player_subtitle_ai_max_concurrency, R.string.setting_ai, 8));
        // Ad page (9).
        entries.add(sub(R.string.setting_ad_master, R.string.setting_ad, 9));
        entries.add(sub(R.string.setting_ai_ad_detection, R.string.setting_ad, 9));
        entries.add(sub(R.string.setting_ad_rule_manage, R.string.setting_ad, 9));
        entries.add(sub(R.string.ad_block_stats, R.string.setting_ad, 9));
        entries.add(sub(R.string.setting_auto_skip_intro_outro, R.string.setting_ad, 9));
        entries.add(sub(R.string.setting_intro_skip_kinds, R.string.setting_ad, 9));
        return entries;
    }

    /** Case-insensitive substring match on title + subtitle. Empty query matches nothing. */
    public static List<Entry> search(Context context, List<Entry> entries, String query) {
        List<Entry> result = new ArrayList<>();
        if (query == null) return result;
        String needle = query.trim().toLowerCase(Locale.getDefault());
        if (needle.isEmpty()) return result;
        for (Entry entry : entries) {
            String haystack = context.getString(entry.titleRes);
            if (entry.subtitleRes != 0) haystack += " " + context.getString(entry.subtitleRes);
            if (haystack.toLowerCase(Locale.getDefault()).contains(needle)) result.add(entry);
        }
        return result;
    }

    private SettingSearchIndex() {
    }
}
