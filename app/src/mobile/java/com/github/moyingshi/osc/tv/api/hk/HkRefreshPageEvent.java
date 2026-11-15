package com.github.moyingshi.osc.tv.api.hk;

import org.greenrobot.eventbus.EventBus;

/**
 * 海阔小程序 refreshPage 事件（对齐第 3 项）。
 * <p>
 * 对照官方 {@code fe3}（反编译源码 defpackage/fe3.java，只有一个 boolean 字段）：
 * 规则 JS 调 {@code refreshPage(scrollTop?)} 后，{@link HkEngine} 置位并经这里发事件，
 * 当前页（列表/详情/搜索 Fragment）收到后重跑当前页；{@code toTop} 为真时先回顶。
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkRefreshPageEvent {

    private final boolean toTop;

    public HkRefreshPageEvent(boolean toTop) {
        this.toTop = toTop;
    }

    /** 是否回顶（官方 refreshScrollTop：无参/非布尔参为 true，false 才不回顶）。 */
    public boolean isToTop() {
        return toTop;
    }

    public static void post(boolean toTop) {
        EventBus.getDefault().post(new HkRefreshPageEvent(toTop));
    }
}
