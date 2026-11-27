package com.github.aiyingshi.osc.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.github.aiyingshi.osc.tv.R;
import com.github.aiyingshi.osc.tv.databinding.FragmentSettingSubtitleBinding;
import com.github.aiyingshi.osc.tv.setting.Setting;
import com.github.aiyingshi.osc.tv.subtitle.provider.SubtitleProviderRegistry;
import com.github.aiyingshi.osc.tv.ui.base.BaseFragment;
import com.github.aiyingshi.osc.tv.ui.dialog.SubtitleSettingsDialog;
import com.github.aiyingshi.osc.tv.utils.ResUtil;

public class SettingSubtitleFragment extends BaseFragment {

    private FragmentSettingSubtitleBinding mBinding;
    private String[] subtitleLanguageLabels;
    private String[] subtitleLanguageValues;
    /** AI 翻译目标语言（与 Setting.getAiSubtitleTargetLanguage 的取值一致）。 */
    private static final String[] AI_TARGET_LABELS = {"简体中文", "繁體中文", "English"};
    private static final String[] AI_TARGET_VALUES = {"zh-Hans", "zh-Hant", "en"};

    public static SettingSubtitleFragment newInstance() {
        return new SettingSubtitleFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingSubtitleBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        subtitleLanguageLabels = ResUtil.getStringArray(R.array.select_subtitle_language);
        subtitleLanguageValues = ResUtil.getStringArray(R.array.select_subtitle_language_value);
        mBinding.subtitleAutoMatchText.setText(getSwitch(Setting.isSubtitleAutoMatchEnabled()));
        mBinding.subtitleLanguageText.setText(getSubtitleLanguageText());
        mBinding.subtitleAssrtTokenText.setText(getSubtitleSourceText());
        mBinding.aiSubtitleTranslateText.setText(getSwitch(Setting.isAiSubtitleTranslate()));
        mBinding.aiSubtitleTargetLanguageText.setText(getAiSubtitleTargetLanguageText());
    }

    @Override
    protected void initEvent() {
        mBinding.subtitleAutoMatch.setOnClickListener(this::setSubtitleAutoMatch);
        mBinding.subtitleLanguage.setOnClickListener(this::onSubtitleLanguage);
        mBinding.subtitleAssrtToken.setOnClickListener(this::onSubtitleSource);
        mBinding.aiSubtitleTranslate.setOnClickListener(this::setAiSubtitleTranslate);
        mBinding.aiSubtitleTargetLanguage.setOnClickListener(this::onAiSubtitleTargetLanguage);
    }

    /** AI 字幕翻译开关（P0 功能 5）：默认关；关着时播放器字幕菜单里不发翻译请求。 */
    private void setAiSubtitleTranslate(View view) {
        Setting.putAiSubtitleTranslate(!Setting.isAiSubtitleTranslate());
        mBinding.aiSubtitleTranslateText.setText(getSwitch(Setting.isAiSubtitleTranslate()));
    }

    private void onAiSubtitleTargetLanguage(View view) {
        SubtitleSettingsDialog.showPreferredLanguage(requireActivity(), AI_TARGET_LABELS, AI_TARGET_VALUES, Setting.getAiSubtitleTargetLanguage(), value -> {
            Setting.putAiSubtitleTargetLanguage(value);
            mBinding.aiSubtitleTargetLanguageText.setText(getAiSubtitleTargetLanguageText());
        });
    }

    private String getAiSubtitleTargetLanguageText() {
        String current = Setting.getAiSubtitleTargetLanguage();
        for (int i = 0; i < AI_TARGET_VALUES.length; i++) if (TextUtils.equals(AI_TARGET_VALUES[i], current)) return AI_TARGET_LABELS[i];
        return current;
    }

    private void setSubtitleAutoMatch(View view) {
        Setting.putSubtitleAutoMatchEnabled(!Setting.isSubtitleAutoMatchEnabled());
        mBinding.subtitleAutoMatchText.setText(getSwitch(Setting.isSubtitleAutoMatchEnabled()));
    }

    private void onSubtitleLanguage(View view) {
        SubtitleSettingsDialog.showPreferredLanguage(requireActivity(), subtitleLanguageLabels, subtitleLanguageValues, Setting.getSubtitlePreferredLanguage(), value -> {
            Setting.putSubtitlePreferredLanguage(value);
            mBinding.subtitleLanguageText.setText(getSubtitleLanguageText());
        });
    }

    private void onSubtitleSource(View view) {
        SubtitleSettingsDialog.showSourceSummary(requireActivity(), SubtitleProviderRegistry.get().providers(), () -> mBinding.subtitleAssrtTokenText.setText(getSubtitleSourceText()));
    }

    private String getSubtitleLanguageText() {
        int index = getSubtitleLanguageIndex();
        return subtitleLanguageLabels != null && index >= 0 && index < subtitleLanguageLabels.length ? subtitleLanguageLabels[index] : Setting.getSubtitlePreferredLanguage();
    }

    private int getSubtitleLanguageIndex() {
        if (subtitleLanguageValues == null) return 0;
        for (int i = 0; i < subtitleLanguageValues.length; i++) if (TextUtils.equals(subtitleLanguageValues[i], Setting.getSubtitlePreferredLanguage())) return i;
        return 0;
    }

    private String getSubtitleSourceText() {
        return getString(R.string.player_subtitle_source_count, SubtitleProviderRegistry.get().providerNames().size());
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}
