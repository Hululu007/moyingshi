package com.github.aiyingshi.osc.tv.module;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 六个可选模块的静态定义（与 {@code docs/app-modularization.md} 模块总表一致）。
 *
 * <p>体积为 mobile-arm64 下载口径（MiB，见 {@code ~/workspace/apk-size-analysis/REPORT.md}），
 * 仅用于模块管理页展示；安装校验以各模块 zip 内的 manifest.json 为准，不以这里为准。
 * {@link #files} 是模块 zip 内应有的文件（不含 manifest.json 本身）；
 * {@link #loadOrder} 是 {@code System.load} 的预载顺序（无原生库的模块为空表）。
 */
public final class ModuleDefinition {

    /** mpv 内核：bundle 全部 10 个 .so（含自带的 libc++_shared.so）。 */
    public static final ModuleDefinition MPV = new ModuleDefinition(
            "mpv", "MPV",
            44_500_000L,
            Arrays.asList("libc++_shared.so", "libmvutil.so", "libmwresample.so", "libmwscale.so",
                    "libmvcodec.so", "libmvformat.so", "libmvfilter.so", "libmvdevice.so",
                    "libmpv.so", "libplayer.so"),
            // 与 MPVLib.LOAD_ORDER 同顺序：先 libc++，后 libmpv，最后 JNI 桥 libplayer。
            Arrays.asList("libc++_shared.so", "libmvutil.so", "libmwresample.so", "libmwscale.so",
                    "libmvcodec.so", "libmvformat.so", "libmvfilter.so", "libmvdevice.so",
                    "libmpv.so", "libplayer.so"));

    /** VLC 内核：libvlc.so + libvlcjni.so，另自带一份 libc++_shared.so（见下）。 */
    public static final ModuleDefinition VLC = new ModuleDefinition(
            "vlc", "VLC",
            46_000_000L,
            Arrays.asList("libc++_shared.so", "libvlc.so", "libvlcjni.so"),
            // Stage 0 实测：libvlc.so 需要 libc++_shared.so，libvlcjni.so 需要 libvlc.so。
            // 基础包拆走 VLC 后不再自带 libc++_shared.so，故 VLC 模块 zip 必须自带并先预载它。
            Arrays.asList("libc++_shared.so", "libvlc.so", "libvlcjni.so"));

    /** 实时字幕（Sherpa-ONNX）：单个 .so，无依赖链。 */
    public static final ModuleDefinition SHERPA = new ModuleDefinition(
            "sherpa", "实时字幕",
            23_600_000L,
            Collections.singletonList("libsherpa-onnx-jni.so"),
            Collections.singletonList("libsherpa-onnx-jni.so"));

    /** 杜比视界渲染器：libexo_dovi_renderer.so + libdovi_android.so（两者无 DT_NEEDED 互依赖）。 */
    public static final ModuleDefinition DOVI = new ModuleDefinition(
            "dovi", "杜比视界",
            10_000_000L,
            Arrays.asList("libdovi_android.so", "libexo_dovi_renderer.so"),
            // 先载 libdovi_android，再载渲染器（渲染器运行时 dlopen libdovi 时能在已加载表里找到）。
            Arrays.asList("libdovi_android.so", "libexo_dovi_renderer.so"));

    /** ijk 内核：libijkffmpeg.so + libijksdl.so + libijkplayer.so。 */
    public static final ModuleDefinition IJK = new ModuleDefinition(
            "ijk", "ijk",
            10_300_000L,
            Arrays.asList("libijkffmpeg.so", "libijksdl.so", "libijkplayer.so"),
            // Stage 0 实测：libijkplayer.so 需要 libijkffmpeg.so 与 libijksdl.so。
            Arrays.asList("libijkffmpeg.so", "libijksdl.so", "libijkplayer.so"));

    /**
     * LUT 预设包（资产包，无原生库）：13 个 .cube 中的 12 个。
     * 乌托邦.cube 留在基础包 assets 内（它是资产列表首项，用户未装模块时 LUT 功能仍有默认可用项），
     * 其余 12 个随模块走；模块安装后 LutStore 再从模块目录扫描补齐。
     */
    public static final ModuleDefinition LUT = new ModuleDefinition(
            "lut", "LUT 预设",
            3_700_000L,
            Arrays.asList("低对比.cube", "复古深棕.cube", "徕卡胶片.cube", "明亮清新.cube",
                    "柔和中性.cube", "浪漫柔美.cube", "色彩还原.cube", "蓝绿带亮.cube",
                    "赛博朋克.cube", "通透鲜艳.cube", "钨丝灯.cube", "高级质感.cube"),
            Collections.emptyList());

    public static final List<ModuleDefinition> ALL = Collections.unmodifiableList(
            Arrays.asList(MPV, VLC, SHERPA, DOVI, IJK, LUT));

    /** 模块 id：zip 文件名与私有目录名都用它（module-&lt;id&gt;-&lt;abi&gt;-&lt;versionCode&gt;.zip）。 */
    public final String id;
    /** 模块管理页显示名。 */
    public final String displayName;
    /** 约体积（字节），仅展示用。 */
    public final long approxSizeBytes;
    /** 模块 zip 内应有的文件（不含 manifest.json）。 */
    public final List<String> files;
    /** System.load 预载顺序；无原生库的模块为空。 */
    public final List<String> loadOrder;

    private ModuleDefinition(String id, String displayName, long approxSizeBytes,
                             List<String> files, List<String> loadOrder) {
        this.id = id;
        this.displayName = displayName;
        this.approxSizeBytes = approxSizeBytes;
        this.files = Collections.unmodifiableList(files);
        this.loadOrder = Collections.unmodifiableList(loadOrder);
    }

    public static ModuleDefinition byId(String id) {
        if (id == null) return null;
        for (ModuleDefinition definition : ALL) {
            if (definition.id.equals(id)) return definition;
        }
        return null;
    }

    /** zip 文件名前缀，如 module-mpv-。 */
    public String zipPrefix() {
        return "module-" + id + "-";
    }

    /** 总包文件名，如 modules-all-arm64-v8a-560.zip。 */
    public static String totalBundleName(String abi, int versionCode) {
        return "modules-all-" + abi + "-" + versionCode + ".zip";
    }

    /** 分包文件名，如 module-mpv-arm64-v8a-560.zip。 */
    public String zipName(String abi, int versionCode) {
        return "module-" + id + "-" + abi + "-" + versionCode + ".zip";
    }
}
