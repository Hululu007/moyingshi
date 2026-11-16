package com.github.moyingshi.osc.tv.api.hk.event.rule;
/** 移植自星辰 com/fongmi/android/tv/api/hk/event/rule/RefreshVideoUrlEvent.java（28 行）：除包名外与原文一致。详见 docs/miniapp-xingchen-3e-facade.md。 */

import java.util.Map;

/**
 * 8.83 com.example.hikerview.event.home.OnRefreshVideoUrlEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 规则 JS 调 refreshVideoSource(url, headers) 时发布（原版无条件 post，JSEngine smali 22209）；
 * 列表页订阅后把新视频地址与请求头存进网页 holder 的视频面
 * （原版 ArticleListFragment.onRefreshVideoUrl → ArticleWebViewHolder.updateVideoUrl）。
 */
public class RefreshVideoUrlEvent {

    private final String url;
    private final Map<String, String> headers;

    public RefreshVideoUrlEvent(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
