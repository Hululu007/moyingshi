package com.github.moyingshi.osc.tv.content;

import android.app.Activity;

import com.github.moyingshi.osc.tv.bean.Episode;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.setting.PlayerSetting;
import com.github.moyingshi.osc.tv.ui.activity.AudioActivity;
import com.github.moyingshi.osc.tv.utils.AudioUtil;

import java.util.List;

public class AudioContentHandler implements ContentHandler {

    @Override
    public boolean canHandleSite(String key, String name) {
        return AudioUtil.isAudioSiteEnabled(key);
    }

    @Override
    public boolean canHandleUrl(String url) {
        return !PlayerSetting.isImmersiveAudioMode() && AudioUtil.isAudioUrl(url);
    }

    @Override
    public boolean handleSite(Activity activity, String key, String id, String name, String pic, String mark) {
        return AudioActivity.startSite(activity, key, id, name, pic, mark);
    }

    @Override
    public boolean handleUrl(Activity activity, String url, String title) {
        if (PlayerSetting.isImmersiveAudioMode()) return false;
        AudioActivity.start(activity, url, title, "", null);
        return true;
    }

    @Override
    public boolean handleResult(Activity activity, String historyKey, String siteKey, String flag, String vodName, String vodPic, List<Episode> episodes, int position, Result result, long timeout) {
        if (PlayerSetting.isImmersiveAudioMode()) return false;
        return AudioActivity.startIfAudio(activity, historyKey, siteKey, flag, vodName, vodPic, episodes, position, result, timeout);
    }
}
