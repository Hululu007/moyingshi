package com.github.moyingshi.osc.tv.setting;

public final class FlagSwitchPlaybackPolicy {

    private FlagSwitchPlaybackPolicy() {
    }

    public static boolean shouldDeferPlayback(boolean deferFlagSwitchPlayback, boolean programmaticSwitch) {
        return deferFlagSwitchPlayback && !programmaticSwitch;
    }
}
