package com.fongmi.android.tv.player.picture;

import com.github.catvod.utils.Prefers;

/**
 * 画面调节持久化（B6）。写法照抄 LutSetting：Prefers + 静态方法 + clamp。
 * 五项默认 0，Anime4K 默认关；老版本升级后无这些 key，行为与以前一模一样（零回归）。
 */
public class PictureAdjustSetting {

    private static final String KEY_BRIGHTNESS = "pic_brightness";
    private static final String KEY_CONTRAST = "pic_contrast";
    private static final String KEY_SATURATION = "pic_saturation";
    private static final String KEY_HUE = "pic_hue";
    private static final String KEY_GAMMA = "pic_gamma";
    private static final String KEY_ANIME4K = "pic_anime4k";

    public static PictureAdjust getAdjust() {
        return new PictureAdjust(
                Prefers.getInt(KEY_BRIGHTNESS, PictureAdjust.DEFAULT),
                Prefers.getInt(KEY_CONTRAST, PictureAdjust.DEFAULT),
                Prefers.getInt(KEY_SATURATION, PictureAdjust.DEFAULT),
                Prefers.getInt(KEY_HUE, PictureAdjust.DEFAULT),
                Prefers.getInt(KEY_GAMMA, PictureAdjust.DEFAULT));
    }

    public static void putAdjust(PictureAdjust adjust) {
        if (adjust == null) adjust = PictureAdjust.NEUTRAL;
        Prefers.put(KEY_BRIGHTNESS, adjust.getBrightness());
        Prefers.put(KEY_CONTRAST, adjust.getContrast());
        Prefers.put(KEY_SATURATION, adjust.getSaturation());
        Prefers.put(KEY_HUE, adjust.getHue());
        Prefers.put(KEY_GAMMA, adjust.getGamma());
    }

    /** 重置只清五项调节，Anime4K 开关不动（开关是独立功能）。 */
    public static void resetAdjust() {
        putAdjust(PictureAdjust.NEUTRAL);
    }

    public static boolean isAnime4kEnabled() {
        return Prefers.getBoolean(KEY_ANIME4K);
    }

    public static void putAnime4kEnabled(boolean enabled) {
        Prefers.put(KEY_ANIME4K, enabled);
    }
}
