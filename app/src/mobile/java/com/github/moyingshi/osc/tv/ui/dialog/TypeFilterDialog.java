package com.github.moyingshi.osc.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.bean.Class;
import com.github.moyingshi.osc.tv.bean.Filter;
import com.github.moyingshi.osc.tv.databinding.DialogTypeFilterBinding;
import com.github.moyingshi.osc.tv.impl.FilterListener;
import com.github.moyingshi.osc.tv.ui.adapter.FilterAdapter;
import com.github.moyingshi.osc.tv.ui.adapter.TypeAdapter;
import com.github.moyingshi.osc.tv.ui.adapter.TypeDialogAdapter;
import com.github.moyingshi.osc.tv.utils.ResUtil;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayoutManager;

import java.util.List;

/**
 * 首页浏览面板：全部分类 + 筛选合成一个底部面板。
 * 上半是分类 chips（旧 TypeDialog 的内容），下半是筛选组（旧 FilterDialog 的内容）。
 * 点分类不关闭面板：切页后就地刷新高亮与筛选区；点筛选项原样应用（可连选多组）。
 */
public class TypeFilterDialog extends BaseBottomSheetDialog implements TypeAdapter.OnClickListener {

    /** 切分类后取新分类筛选条件的来源，由 VodFragment 提供。 */
    public interface FilterProvider {
        List<Filter> getFilters(int position);
    }

    private DialogTypeFilterBinding binding;
    private TypeAdapter.OnClickListener typeListener;
    private FilterListener filterListener;
    private FilterProvider filterProvider;
    private List<Class> items;
    private List<Filter> filter;
    private TypeDialogAdapter typeAdapter;
    private FilterAdapter filterAdapter;

    public static TypeFilterDialog create() {
        return new TypeFilterDialog();
    }

    public TypeFilterDialog items(List<Class> items) {
        this.items = items;
        return this;
    }

    public TypeFilterDialog filter(List<Filter> filter) {
        this.filter = filter;
        return this;
    }

    public TypeFilterDialog filterProvider(FilterProvider filterProvider) {
        this.filterProvider = filterProvider;
        return this;
    }

    public void show(Fragment fragment) {
        for (Fragment child : fragment.getChildFragmentManager().getFragments()) if (child instanceof TypeFilterDialog) return;
        this.typeListener = (TypeAdapter.OnClickListener) fragment;
        this.filterListener = (FilterListener) fragment;
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogTypeFilterBinding.inflate(inflater, container, false);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        configureWindow(dialog.getWindow());
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null) configureWindow(getDialog().getWindow());
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected boolean stableOverlay() {
        return true;
    }

    @Override
    protected void setBehavior(com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        configureWindow(dialog.getWindow());
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    @Override
    protected void initView() {
        boolean land = ResUtil.isLand(requireContext());
        int typeRatio = land ? 32 : 40;
        int filterRatio = land ? 32 : 40;
        binding.typeRecycler.setMaxHeight(ResUtil.getScreenHeight(requireContext()) * typeRatio / 100);
        binding.filterRecycler.setMaxHeight(ResUtil.getScreenHeight(requireContext()) * filterRatio / 100);
        binding.typeRecycler.setHasFixedSize(false);
        binding.typeRecycler.setItemAnimator(null);
        FlexboxLayoutManager manager = new FlexboxLayoutManager(requireContext(), FlexDirection.ROW);
        manager.setFlexWrap(FlexWrap.WRAP);
        binding.typeRecycler.setLayoutManager(manager);
        binding.typeRecycler.setAdapter(typeAdapter = new TypeDialogAdapter(this, items));
        binding.filterRecycler.setHasFixedSize(true);
        binding.filterRecycler.setItemAnimator(null);
        refreshFilterSection(filter);
    }

    /** 刷新筛选区：有条件就显示并换数据，没有就整段隐藏，不留空白。 */
    private void refreshFilterSection(List<Filter> filters) {
        boolean visible = filters != null && !filters.isEmpty();
        binding.filterDivider.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.filterTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.filterRecycler.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        binding.filterRecycler.setAdapter(filterAdapter = new FilterAdapter(filterListener, filters));
    }

    @Override
    public void onItemClick(int position, Class item) {
        typeListener.onItemClick(position, item);
        typeAdapter.notifyDataSetChanged();
        if (filterProvider != null) refreshFilterSection(filterProvider.getFilters(position));
    }

    private void configureWindow(Window window) {
        if (window == null) return;
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0f;
        window.setAttributes(params);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowCompat.setDecorFitsSystemWindows(window, true);
    }
}
