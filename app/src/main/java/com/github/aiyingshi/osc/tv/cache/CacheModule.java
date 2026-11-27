package com.github.aiyingshi.osc.tv.cache;

import java.util.List;

public interface CacheModule {

    CacheModuleId id();

    CacheGroup group();

    List<CacheRoot> roots();

    CacheCleanability cleanability();

    CacheEvictionPolicy evictionPolicy();

    CacheProtection protection();
}
