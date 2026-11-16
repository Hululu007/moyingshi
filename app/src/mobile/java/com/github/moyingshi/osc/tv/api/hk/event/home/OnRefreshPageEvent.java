package com.github.moyingshi.osc.tv.api.hk.event.home;
/** 移植自星辰 com/fongmi/android/tv/api/hk/event/home/OnRefreshPageEvent.java（33 行）：除包名外与原文一致。详见 docs/miniapp-xingchen-3e-facade.md。 */

/**
 * 8.83 com.example.hikerview.event.home.OnRefreshPageEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnRefreshPageEvent {

    private boolean forceRefresh;
    private boolean scrollTop;

    public OnRefreshPageEvent(boolean boolean0) {
        this.scrollTop = boolean0;
    }

    public OnRefreshPageEvent(boolean boolean0, boolean boolean1) {
        this.scrollTop = boolean0;
        this.forceRefresh = boolean1;
    }

    public boolean isForceRefresh() {
        return forceRefresh;
    }

    public boolean isScrollTop() {
        return scrollTop;
    }

    public void setForceRefresh(boolean forceRefresh) {
        this.forceRefresh = forceRefresh;
    }

}