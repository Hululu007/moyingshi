package com.github.moyingshi.osc.tv.api.hk.event.home;
/** 移植自星辰 com/fongmi/android/tv/api/hk/event/home/SetPagePicEvent.java（23 行）：除包名外与原文一致。详见 docs/miniapp-xingchen-3e-facade.md。 */

/**
 * 8.83 com.example.hikerview.event.home.SetPagePicEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class SetPagePicEvent {

    private String url;

    public SetPagePicEvent(String string0) {
        this.url = string0;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}