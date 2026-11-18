package com.github.moyingshi.osc.tv.flutter;

import androidx.fragment.app.Fragment;

import com.github.moyingshi.osc.tv.bean.Class;
import com.github.moyingshi.osc.tv.bean.Filter;
import com.github.moyingshi.osc.tv.bean.Value;
import com.github.moyingshi.osc.tv.impl.FilterListener;
import com.github.moyingshi.osc.tv.ui.adapter.TypeAdapter;
import com.github.moyingshi.osc.tv.ui.dialog.TypeFilterDialog;

import java.util.Collections;
import java.util.List;

/**
 * Headless host for the native TypeFilterDialog opened from the
 * Flutter home browse FAB (b8-home-fabs). This fragment must live in
 * the mobile source set: the dialog's show() casts its host fragment
 * to the mobile-only {@link TypeAdapter.OnClickListener} and to
 * {@link FilterListener}, and its filterProvider is the dialog's own
 * nested interface. The main source set cannot name those types (the
 * leanback variant has no mobile source set), so B11HostApiImpl
 * obtains this fragment reflectively (B20 registrar shape) and the
 * fragment forwards every callback to the impl, which owns the
 * selection state Dart polls.
 *
 * <p>Carries no UI. The dialog is shown into this fragment's child
 * FragmentManager, exactly like VodFragment hosts it natively. The
 * impl re-attaches itself after rotation (same as B11HomeHostFragment):
 * the FragmentManager restores the fragment without its host reference.
 */
public class B11FabHostFragment extends Fragment
        implements TypeAdapter.OnClickListener, FilterListener, TypeFilterDialog.FilterProvider {

    private B11HostApiImpl host;

    public void setHost(B11HostApiImpl host) {
        this.host = host;
    }

    @Override
    public void onItemClick(int position, Class item) {
        if (host != null) host.onFabTypeSelected(position);
    }

    @Override
    public void setFilter(String key, Value value) {
        if (host != null && value != null) host.onFabFilterSet(key, value.getV());
    }

    @Override
    public List<Filter> getFilters(int position) {
        if (host == null) return Collections.emptyList();
        return host.fabFiltersForPosition(position);
    }
}
