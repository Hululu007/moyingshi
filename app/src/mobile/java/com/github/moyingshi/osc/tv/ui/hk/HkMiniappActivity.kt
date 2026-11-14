package com.github.moyingshi.osc.tv.ui.hk

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.viewbinding.ViewBinding
import com.github.moyingshi.osc.tv.R
import com.github.moyingshi.osc.tv.api.config.VodConfig
import com.github.moyingshi.osc.tv.api.hk.HkImportPicker
import com.github.moyingshi.osc.tv.api.hk.HkStore
import com.github.moyingshi.osc.tv.bean.Config
import com.github.moyingshi.osc.tv.bean.Site
import com.github.moyingshi.osc.tv.databinding.ActivityHkMiniappBinding
import com.github.moyingshi.osc.tv.event.ConfigEvent
import com.github.moyingshi.osc.tv.impl.Callback
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder
import com.github.moyingshi.osc.tv.ui.adapter.LocalSourceAdapter
import com.github.moyingshi.osc.tv.ui.base.BaseActivity
import com.github.moyingshi.osc.tv.utils.LocalSource
import com.github.moyingshi.osc.tv.utils.Notify

/**
 * 海阔小程序管理页（mobile 专属）：三个导入入口（文件/目录、粘贴 JSON、同步已装规则）
 * + 已导入规则列表（标题⁽ʰᵏ⁾┃[类型]）。点一条规则先弹“小程序列表页还在做”
 * （点开进列表页是第 3 步）。行末删除是彻底卸载：站源条目 + plugins/hk/ 下的规则文件一起删。
 *
 * 从实验室“海阔小程序”分组用隐式 intent 打开（main 源集不能直接引用 mobile 的类，
 * action 是 ${applicationId}.hk_miniapp，见 mobile 的 AndroidManifest）。
 */
class HkMiniappActivity : BaseActivity(), LocalSourceAdapter.OnClickListener {

    private lateinit var binding: ActivityHkMiniappBinding
    private lateinit var adapter: LocalSourceAdapter
    private lateinit var picker: HkImportPicker

    override fun getBinding(): ViewBinding {
        if (!::binding.isInitialized) {
            binding = ActivityHkMiniappBinding.inflate(layoutInflater)
        }
        return binding
    }

    override fun initView(savedInstanceState: Bundle?) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        picker = HkImportPicker(this) { added -> onImported(added) }
        adapter = LocalSourceAdapter(this).setCheckable(false)
        binding.recycler.adapter = adapter
        binding.btnImportFile.setOnClickListener { showPickChooser() }
        binding.btnImportPaste.setOnClickListener { showPasteDialog() }
        binding.btnSync.setOnClickListener { runSync() }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 已导入规则列表：本地源里登记的 hk:// 站源（标题⁽ʰᵏ⁾┃[类型]）。 */
    private fun refreshList() {
        val sites = LocalSource.getSites().filter { HkStore.isMiniapp(it) }
        adapter.setItems(sites)
        binding.empty.visibility = if (sites.isEmpty()) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (sites.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showPickChooser() {
        WebHtvAlertDialogBuilder(this, R.style.Theme_App_Lab_DayNight_Dialog)
            .setTitle(R.string.hk_miniapp_pick_title)
            .setItems(
                arrayOf<CharSequence>(
                    getString(R.string.hk_miniapp_pick_file),
                    getString(R.string.hk_miniapp_pick_dir)
                )
            ) { _, which ->
                if (which == 0) picker.pickFiles() else picker.pickDirectory()
            }
            .show()
    }

    private fun showPasteDialog() {
        val edit = AppCompatEditText(this).apply {
            hint = getString(R.string.hk_miniapp_paste_hint)
            minLines = 6
            maxLines = 10
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        edit.setPadding(pad, pad, pad, pad)
        val dialog = WebHtvAlertDialogBuilder(this, R.style.Theme_App_Lab_DayNight_Dialog)
            .setTitle(R.string.hk_miniapp_import_paste)
            .setView(edit)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lab_import, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = edit.text?.toString().orEmpty()
                if (text.isBlank()) {
                    Notify.show(getString(R.string.hk_miniapp_import_error, "规则内容为空"))
                    return@setOnClickListener
                }
                dialog.dismiss()
                Thread {
                    val added = HkStore.importJsonText(text)
                    runOnUiThread { onImported(added) }
                }.start()
            }
        }
        dialog.show()
    }

    private fun runSync() {
        Thread {
            val added = HkStore.syncSites()
            runOnUiThread {
                Notify.show(getString(R.string.hk_miniapp_synced, added))
                if (added > 0) reloadLocalConfig()
                refreshList()
            }
        }.start()
    }

    /** 导入回调（已在主线程）：报数 + 重载配置 + 刷新列表。 */
    private fun onImported(added: Int) {
        if (added > 0) {
            Notify.show(getString(R.string.hk_miniapp_imported, added))
            reloadLocalConfig()
        } else {
            val error = HkStore.lastError().orEmpty()
            if (error.isEmpty()) Notify.show(R.string.hk_miniapp_no_rule)
            else Notify.show(getString(R.string.hk_miniapp_import_error, error))
        }
        refreshList()
    }

    /** 重新加载当前点播配置，让新登记的小程序站源出现在首页站点列表里（与本地源弹窗一致）。 */
    private fun reloadLocalConfig() {
        VodConfig.load(Config.vod(), object : Callback {
            override fun success() {
                ConfigEvent.vod()
            }
        })
    }

    /** 点一条规则：列表页还没做（第 3 步），先弹一句，不切站源、不进蜘蛛空页。 */
    override fun onItemClick(item: Site) {
        Notify.show(R.string.hk_miniapp_list_pending)
    }

    /** 行末删除：站源条目 + 规则文件一起删（彻底卸载）；只删条目的话文件还在，同步会把它登记回来。 */
    override fun onDeleteClick(item: Site) {
        LocalSource.removeByKeys(listOf(item.key))
        val title = HkStore.titleOf(item).orEmpty()
        if (title.isNotEmpty()) HkStore.delete(title)
        Notify.show(getString(R.string.lab_local_source_deleted, 1))
        reloadLocalConfig()
        refreshList()
    }

    override fun onSelectionChanged() {
        // 管理页里多选框是关掉的，不会有选中变化。
    }
}
