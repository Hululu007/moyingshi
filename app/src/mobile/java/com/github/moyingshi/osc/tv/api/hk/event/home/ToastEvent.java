package com.github.moyingshi.osc.tv.api.hk.event.home;

/**
 * 对照 8.83 com.example.hikerview.event.home.ToastEvent：生产方是
 * JSEngine.setError 的无回调支（解析失败消息投事件），由宿主面承接
 * （官 FilmListActivity.onToast：isOnPause 丢弃、否则 DebugUtil
 * .showErrorMsg 错误式呈现，title=msg、code="500"、ex=new Exception(msg)）。
 * JS toast() 与本事件无关——官 JSEngine.toast 是 runOnUI 直弹
 * ToastMgr.shortBottomCenter 的轻提示（136b A7 曾认混两条通道、
 * 把 toast 改投本事件冒充报错，2026-11-15 已订正）。咱家承接点放三个 hk Fragment
 *（HkListFragment/HkDetailFragment/HkSearchFragment 各一个 onToastEvent，见
 * docs/miniapp-xingchen-7b-favorite-history-events.md §5.2；星辰原文落点是
 * HkArticleFragment，我方无此面）。
 */
public class ToastEvent {

    public final String message;

    public ToastEvent(String message) {
        this.message = message;
    }
}
