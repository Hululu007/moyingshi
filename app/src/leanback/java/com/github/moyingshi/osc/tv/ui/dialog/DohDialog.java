package com.github.moyingshi.osc.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.databinding.DialogDohBinding;
import com.github.moyingshi.osc.tv.ui.adapter.DohAdapter;
import com.github.moyingshi.osc.tv.ui.custom.SpaceItemDecoration;
import com.github.catvod.bean.Doh;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class DohDialog extends BaseAlertDialog implements DohAdapter.OnClickListener {

    private DialogDohBinding binding;
    private DohAdapter adapter;
    private int index;

    public static DohDialog create() {
        return new DohDialog();
    }

    public DohDialog index(int index) {
        this.index = index;
        return this;
    }

    public void show(FragmentActivity activity) {
        String[] items = new String[VodConfig.get().getDoh().size()];
        for (int i = 0; i < items.length; i++) items[i] = VodConfig.get().getDoh().get(i).getName();
        ChoiceDialog.showSingle(activity, R.string.setting_doh, items, index, which -> ((Listener) activity).setDoh(VodConfig.get().getDoh().get(which)));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogDohBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new DohAdapter(this);
        adapter.setSelect(index);
        binding.recycler.setAdapter(adapter);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.post(() -> binding.recycler.scrollToPosition(adapter.getSelect()));
    }

    @Override
    public void onItemClick(Doh item) {
        ((Listener) requireActivity()).setDoh(item);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.4f);
    }

    public interface Listener {

        void setDoh(Doh doh);
    }
}
