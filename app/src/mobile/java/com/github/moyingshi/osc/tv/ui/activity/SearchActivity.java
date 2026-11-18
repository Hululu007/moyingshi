package com.github.moyingshi.osc.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.History;
import com.github.moyingshi.osc.tv.flutter.C1ScreenActivity;
import com.github.moyingshi.osc.tv.playback.HistoryResumePayload;
import com.github.moyingshi.osc.tv.databinding.ActivitySearchBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.ui.fragment.CollectFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SearchFragment;

import java.net.URLEncoder;

public class SearchActivity extends BaseActivity {

    public static void start(Activity activity) {
        start(activity, "");
    }

    public static void start(Activity activity, String keyword) {
        start(activity, keyword, null);
    }

    public static void start(Activity activity, String keyword, String siteKey) {
        start(activity, keyword, siteKey, null, null);
    }

    public static void start(Activity activity, String keyword, String siteKey, String pic, String wallPic) {
        // B8 B9: the search input page is the Flutter page (same entry,
        // same params — the params travel URL-encoded in the route
        // query). direct()/directFromHistory() are diverted below (B10
        // territory). Any failure here falls through to the native
        // screen.
            try {
                String route = C1ScreenActivity.ROUTE_SEARCH
                        + "?keyword=" + URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8")
                        + "&siteKey=" + URLEncoder.encode(siteKey == null ? "" : siteKey, "UTF-8")
                        + "&pic=" + URLEncoder.encode(pic == null ? "" : pic, "UTF-8")
                        + "&wallPic=" + URLEncoder.encode(wallPic == null ? "" : wallPic, "UTF-8");
                C1ScreenActivity.start(activity, route);
                return;
            } catch (Throwable e) {
                e.printStackTrace();
            }
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra("keyword", keyword);
        intent.putExtra("siteKey", siteKey);
        intent.putExtra("pic", pic);
        intent.putExtra("wallPic", wallPic);
        activity.startActivity(intent);
    }

    public static void direct(Activity activity, String keyword) {
        direct(activity, keyword, null);
    }

    public static void direct(Activity activity, String keyword, String siteKey) {
        direct(activity, keyword, siteKey, null, null);
    }

    public static void direct(Activity activity, String keyword, String siteKey, String pic, String wallPic) {
        // B8 B10: the results page is the Flutter page (same entry, same
        // params — the params travel URL-encoded in the route query). The
        // siteKey is the explicit filter here (currentSite=false; the B9
        // input page passes its own scope through the family push
        // instead). A live C1 screen underneath cannot take a second
        // route (G2 engine race, see docs/b8-b7-settings-home.md), so a
        // C1 caller falls through to the native results page. Any
        // failure here falls through to the native screen.
        if (!(activity instanceof C1ScreenActivity)) {
            try {
                String route = C1ScreenActivity.ROUTE_COLLECT
                        + "?keyword=" + URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8")
                        + "&currentSite=false"
                        + "&siteKey=" + URLEncoder.encode(siteKey == null ? "" : siteKey, "UTF-8")
                        + "&group="
                        + "&pic=" + URLEncoder.encode(pic == null ? "" : pic, "UTF-8")
                        + "&wallPic=" + URLEncoder.encode(wallPic == null ? "" : wallPic, "UTF-8")
                        + "&hrCid=-1&hrKey=&hrTargetCid=-1";
                C1ScreenActivity.start(activity, route);
                return;
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra("keyword", keyword);
        intent.putExtra("siteKey", siteKey);
        intent.putExtra("pic", pic);
        intent.putExtra("wallPic", wallPic);
        intent.putExtra("direct", true);
        activity.startActivity(intent);
    }

    public static void directFromHistory(Activity activity, History history) {
        directFromHistory(activity, history, history.getVodName(), VodConfig.getCid());
    }

    public static void directFromHistory(Activity activity, History history, String keyword) {
        directFromHistory(activity, history, keyword, VodConfig.getCid());
    }

    public static void directFromHistory(Activity activity, History history, String keyword, int targetCid) {
        // B8 B10: same diversion as direct() above (history-resume
        // params travel in the route query).
        if (!(activity instanceof C1ScreenActivity)) {
            try {
                String route = C1ScreenActivity.ROUTE_COLLECT
                        + "?keyword=" + URLEncoder.encode(keyword == null || keyword.isEmpty() ? history.getVodName() : keyword, "UTF-8")
                        + "&currentSite=false"
                        + "&siteKey="
                        + "&group="
                        + "&pic=" + URLEncoder.encode(history.getVodPic() == null ? "" : history.getVodPic(), "UTF-8")
                        + "&wallPic=" + URLEncoder.encode(history.getWallPic() == null ? "" : history.getWallPic(), "UTF-8")
                        + "&hrCid=" + history.getCid()
                        + "&hrKey=" + URLEncoder.encode(HistoryResumePayload.encode(history), "UTF-8")
                        + "&hrTargetCid=" + targetCid;
                C1ScreenActivity.start(activity, route);
                return;
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra("keyword", keyword == null || keyword.isEmpty() ? history.getVodName() : keyword);
        intent.putExtra("pic", history.getVodPic());
        intent.putExtra("wallPic", history.getWallPic());
        intent.putExtra("direct", true);
        intent.putExtra("historyResumeCid", history.getCid());
        intent.putExtra("historyResumeKey", HistoryResumePayload.encode(history));
        intent.putExtra("historyResumeTargetCid", targetCid);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        return getIntent().getStringExtra("keyword");
    }

    private boolean isDirect() {
        return getIntent().getBooleanExtra("direct", false);
    }

    private String getSiteKey() {
        return getIntent().getStringExtra("siteKey");
    }

    private String getPic() {
        return getIntent().getStringExtra("pic");
    }

    private String getWallPic() {
        return getIntent().getStringExtra("wallPic");
    }

    private int getHistoryResumeCid() {
        return getIntent().getIntExtra("historyResumeCid", -1);
    }

    private String getHistoryResumeKey() {
        return getIntent().getStringExtra("historyResumeKey");
    }

    private int getHistoryResumeTargetCid() {
        return getIntent().getIntExtra("historyResumeTargetCid", -1);
    }

    @Override
    protected ViewBinding getBinding() {
        return ActivitySearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            if (isDirect()) getSupportFragmentManager().beginTransaction().replace(R.id.container, CollectFragment.newInstance(getKeyword(), getSiteKey(), "", getPic(), getWallPic(), getHistoryResumeCid(), getHistoryResumeKey(), getHistoryResumeTargetCid()), CollectFragment.class.getSimpleName()).commit();
            else getSupportFragmentManager().beginTransaction().replace(R.id.container, SearchFragment.newInstance(getKeyword(), getSiteKey(), getPic(), getWallPic()), SearchFragment.class.getSimpleName()).commit();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
        } else {
            super.onBackInvoked();
        }
    }
}
