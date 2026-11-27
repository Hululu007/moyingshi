package com.github.aiyingshi.osc.tv.module;

/** 安装状态：模块管理页与回落逻辑共用。 */
public enum ModuleStatus {
    /** 从未安装（或已被删除）。 */
    NOT_INSTALLED,
    /** 已安装且 manifest 校验通过、App 版本在绑定范围内。 */
    INSTALLED,
    /** 已安装但 App 升级后超出 manifest 的 appVersionCode 绑定范围，需重新下载。 */
    OUTDATED,
    /** 已安装但文件缺失/大小不符/加载失败，需重新下载。 */
    CORRUPT
}
