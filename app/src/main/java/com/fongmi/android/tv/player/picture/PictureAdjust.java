package com.fongmi.android.tv.player.picture;

/**
 * 画面调节五项值（B6）。滑杆口径与 mpv / BieCuiLe 一致：每项 -100..100 整数，默认 0。
 * 不可变值对象：Setting 与 PlayerManager / 效果器之间只传这个对象，不传五个散参。
 */
public class PictureAdjust {

    public static final int MIN = -100;
    public static final int MAX = 100;
    public static final int DEFAULT = 0;

    public static final PictureAdjust NEUTRAL = new PictureAdjust(0, 0, 0, 0, 0);

    private final int brightness;
    private final int contrast;
    private final int saturation;
    private final int hue;
    private final int gamma;

    public PictureAdjust(int brightness, int contrast, int saturation, int hue, int gamma) {
        this.brightness = clamp(brightness);
        this.contrast = clamp(contrast);
        this.saturation = clamp(saturation);
        this.hue = clamp(hue);
        this.gamma = clamp(gamma);
    }

    public static int clamp(int value) {
        return Math.min(Math.max(value, MIN), MAX);
    }

    public int getBrightness() {
        return brightness;
    }

    public int getContrast() {
        return contrast;
    }

    public int getSaturation() {
        return saturation;
    }

    public int getHue() {
        return hue;
    }

    public int getGamma() {
        return gamma;
    }

    /** 五项全为 0 即原样输出。 */
    public boolean isNeutral() {
        return brightness == 0 && contrast == 0 && saturation == 0 && hue == 0 && gamma == 0;
    }

    public PictureAdjust copyWith(int brightness, int contrast, int saturation, int hue, int gamma) {
        return new PictureAdjust(brightness, contrast, saturation, hue, gamma);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PictureAdjust other)) return false;
        return brightness == other.brightness && contrast == other.contrast && saturation == other.saturation && hue == other.hue && gamma == other.gamma;
    }

    @Override
    public int hashCode() {
        int result = brightness;
        result = 31 * result + contrast;
        result = 31 * result + saturation;
        result = 31 * result + hue;
        result = 31 * result + gamma;
        return result;
    }

    @Override
    public String toString() {
        return "PictureAdjust{brightness=" + brightness + ", contrast=" + contrast + ", saturation=" + saturation + ", hue=" + hue + ", gamma=" + gamma + "}";
    }
}
