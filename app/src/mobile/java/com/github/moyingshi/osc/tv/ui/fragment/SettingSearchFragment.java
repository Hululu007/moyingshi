package com.github.moyingshi.osc.tv.ui.fragment;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.FragmentSettingSearchBinding;
import com.github.moyingshi.osc.tv.ui.activity.HomeActivity;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;

import java.util.List;

/**
 * Settings search (BieCuiLe SettingsSearchPage): type to filter every settings row;
 * tapping a result opens its sub-page or deep-links the main-page row.
 */
public class SettingSearchFragment extends BaseFragment {

    private FragmentSettingSearchBinding mBinding;
    private SettingSearchAdapter mAdapter;
    private List<SettingSearchIndex.Entry> mEntries;

    public static SettingSearchFragment newInstance() {
        return new SettingSearchFragment();
    }

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingSearchBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mEntries = SettingSearchIndex.all();
        mAdapter = new SettingSearchAdapter(this::onEntry);
        mBinding.results.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.results.setAdapter(mAdapter);
        showHint();
    }

    @Override
    protected void initEvent() {
        mBinding.backBtn.setOnClickListener(v -> getRoot().change(1));
        mBinding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onQuery(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        mBinding.searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    private void onQuery(String query) {
        List<SettingSearchIndex.Entry> result = SettingSearchIndex.search(requireContext(), mEntries, query);
        mAdapter.setEntries(result);
        if (query == null || query.trim().isEmpty()) {
            showHint();
        } else if (result.isEmpty()) {
            mBinding.results.setVisibility(View.GONE);
            mBinding.emptyView.setVisibility(View.VISIBLE);
            mBinding.emptyView.setText(R.string.setting_search_empty);
        } else {
            mBinding.results.setVisibility(View.VISIBLE);
            mBinding.emptyView.setVisibility(View.GONE);
        }
    }

    private void showHint() {
        mBinding.results.setVisibility(View.GONE);
        mBinding.emptyView.setVisibility(View.VISIBLE);
        mBinding.emptyView.setText(R.string.setting_search_hint);
    }

    private void onEntry(SettingSearchIndex.Entry entry) {
        hideKeyboard();
        if (entry.page >= 0) {
            getRoot().change(entry.page);
        } else if (entry.rowId != null) {
            SettingFragment.requestRow(entry.rowId);
            getRoot().change(1);
        }
    }

    private void hideKeyboard() {
        if (mBinding == null || !isAdded()) return;
        InputMethodManager manager = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(mBinding.searchInput.getWindowToken(), 0);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mBinding != null) {
            mBinding.searchInput.requestFocus();
            InputMethodManager manager = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(mBinding.searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }
}
