package com.github.moyingshi.osc.tv.cache;

public record CacheProtection(boolean allowAutomaticCleanup, boolean allowManualCleanup,
                              boolean requireOwnerIdle, boolean requireAppIdle,
                              long minimumKeepBytes, long minimumKeepFiles,
                              boolean keepIfActiveSession) {
}
