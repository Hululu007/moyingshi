package com.example.hikerview.utils;

/**
 * 只搬海阔 {@code FileUtil.getName} 一个方法（原文见 hikerview-ref
 * {@code app/src/main/java/com/example/hikerview/utils/FileUtil.java:513-519}），
 * 供 {@code HkJsRuntime.hkInJsDir} 用。
 * <p>
 * 不整文件抄：海阔/星辰的 FileUtil 全文（824 行）import 了
 * {@code com.lxj.xpopup.util.XPermission} 与 {@code com.fongmi.android.tv.App}，
 * 移植已明确排除 XPopup，整抄编不过；咱家全树只有这一处调用 getName，
 * 单方法移植即闭合。
 */
public class FileUtil {

    public static String getName(String fileName) {
        if (fileName.lastIndexOf(".") < 1) {
            return fileName;
        }
        return fileName.substring(0, fileName.lastIndexOf("."));
    }
}
