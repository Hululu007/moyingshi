package com.github.moyingshi.osc.tv.cache;

public enum CacheCleanability {
    SAFE_NOW,
    DEFER_UNTIL_IDLE,
    OWNER_MANAGED,
    TEMPORARILY_LOCKED,
    UNAVAILABLE
}
