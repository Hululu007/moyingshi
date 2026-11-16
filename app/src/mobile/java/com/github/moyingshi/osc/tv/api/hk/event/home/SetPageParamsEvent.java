package com.github.moyingshi.osc.tv.api.hk.event.home;
/** 移植自星辰 com/fongmi/android/tv/api/hk/event/home/SetPageParamsEvent.java（23 行）：除包名外与原文一致。详见 docs/miniapp-xingchen-3e-facade.md。 */

/**
 * 8.83 com.example.hikerview.event.home.SetPageParamsEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class SetPageParamsEvent {

    private String params;

    public SetPageParamsEvent(String string0) {
        this.params = string0;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

}