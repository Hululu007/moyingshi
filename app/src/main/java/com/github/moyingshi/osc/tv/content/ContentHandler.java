package com.github.moyingshi.osc.tv.content;

import android.app.Activity;

import com.github.moyingshi.osc.tv.bean.Episode;
import com.github.moyingshi.osc.tv.bean.Result;

import java.util.List;

public interface ContentHandler {

    boolean canHandleSite(String key, String name);

    boolean canHandleUrl(String url);

    boolean handleSite(Activity activity, String key, String id, String name, String pic, String mark);

    boolean handleUrl(Activity activity, String url, String title);

    boolean handleResult(Activity activity, String historyKey, String siteKey, String flag, String vodName, String vodPic, List<Episode> episodes, int position, Result result, long timeout);
}
