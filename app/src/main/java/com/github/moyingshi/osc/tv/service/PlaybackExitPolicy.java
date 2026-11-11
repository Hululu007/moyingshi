package com.github.moyingshi.osc.tv.service;

final class PlaybackExitPolicy {

    private PlaybackExitPolicy() {
    }

    public static boolean canContinueInBackground(boolean serviceRunning, boolean playerAvailable, boolean hasActiveMedia) {
        return serviceRunning && playerAvailable && hasActiveMedia;
    }
}
