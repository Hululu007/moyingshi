package com.github.aiyingshi.osc.tv.cache;

import java.util.List;

public record CacheCleanupPlan(CacheCleanupMode mode, List<CacheModuleId> modules) {
}
