package com.github.moyingshi.osc.tv.ui.hk

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.viewbinding.ViewBinding
import com.github.moyingshi.osc.tv.R
import com.github.moyingshi.osc.tv.api.config.VodConfig
import com.github.moyingshi.osc.tv.api.hk.HkCloudExport
import com.github.moyingshi.osc.tv.api.hk.HkCloudImport
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
 * 海阔小程序管理页（mobile 专属）：导入入口（文件/目录、粘贴 JSON、云口令、同步已装规则）
 * + 导出按钮 + 已导入规则列表（标题⁽ʰᵏ⁾┃[类型]）。点一条规则进它自己的列表页（第 3 步）。
 * 行末删除是彻底卸载：站源条目 + plugins/hk/ 下的规则文件一起删。
 * 列表多选框为导出服务：勾选已导入的规则点“导出”，走云 2 textdb 上传生成云口令
 * 再弹系统分享面板（第 6 步之二）；普通本地源不在这个列表里，不会触发导出。
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
        adapter = LocalSourceAdapter(this).setCheckable(true)
        binding.recycler.adapter = adapter
        binding.btnImportFile.setOnClickListener { showPickChooser() }
        binding.btnImportPaste.setOnClickListener { showPasteDialog() }
        binding.btnCloudCode.setOnClickListener { showCloudCodeDialog() }
        binding.btnSync.setOnClickListener { runSync() }
        binding.btnExport.setOnClickListener { runExport() }
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

    /**
     * 云口令导入（第 7 步之四）：输入框贴入云口令（每行一条，也支持直接粘一整段分享文本），
     * 按口令里的云号分流取回规则 JSON，走 HkStore.importJsonText 落盘登记。
     * 打开时读剪贴板预提取口令填进去（与魔影 bc0 case 2 同款行为）；提取不到口令时
     * 只 toast、不关框、不发请求。注意：云口令只是公开短链，不是加密，拿到口令的人
     * 都能取回同一份规则，对话框 message 里写清楚这一点。
     */
    private fun showCloudCodeDialog() {
        val edit = AppCompatEditText(this).apply {
            hint = getString(R.string.hk_miniapp_cloud_code_hint)
            minLines = 5
            maxLines = 12
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        edit.setPadding(pad, pad, pad, pad)
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.takeIf { it.hasPrimaryClip() }?.primaryClip
            val clipText = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() else ""
            val codes = HkCloudImport.extractCloudCodes(clipText)
            if (codes.isNotEmpty()) edit.setText(codes.joinToString("\n"))
        } catch (ignored: Throwable) {
            // 读剪贴板失败不阻断导入，用户可手动粘贴。
        }
        val dialog = WebHtvAlertDialogBuilder(this, R.style.Theme_App_Lab_DayNight_Dialog)
            .setTitle(R.string.hk_miniapp_cloud_code)
            .setMessage(R.string.hk_miniapp_export_share_note)
            .setView(edit)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lab_import, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val codes = HkCloudImport.extractCloudCodes(edit.text?.toString().orEmpty())
                if (codes.isEmpty()) {
                    Notify.show(R.string.hk_miniapp_cloud_code_none)
                    return@setOnClickListener
                }
                dialog.dismiss()
                binding.btnCloudCode.isEnabled = false
                Thread {
                    val added = HkCloudImport.importCloudCodes(codes)
                    runOnUiThread {
                        binding.btnCloudCode.isEnabled = true
                        if (added > 0) {
                            val error = HkCloudImport.lastError().orEmpty()
                            if (error.isEmpty()) {
                                onImported(added)
                            } else {
                                // 部分成功：报成功数 + 最后一条失败的原因，再重载配置 + 刷新列表。
                                Notify.show(getString(R.string.hk_miniapp_cloud_code_partial, added, error))
                                reloadLocalConfig()
                                refreshList()
                            }
                        } else {
                            val error = HkCloudImport.lastError().orEmpty()
                            if (error.isEmpty()) Notify.show(R.string.hk_miniapp_no_rule)
                            else Notify.show(getString(R.string.hk_miniapp_import_error, error))
                            refreshList()
                        }
                    }
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

    /**
     * 导出已勾选的小程序规则（第 6 步之二）：逐条走云 2 textdb 上传生成云口令，
     * 成功后弹系统分享面板让用户自己选发到哪。未勾选 / 勾选的不是小程序 /
     * 上传失败时只 toast 真实原因，不弹分享、不崩。
     * 注意：导出是把规则公开给拿到口令的人——云口令只是公开短链，不是加密，
     * 分享文本首行（hk_miniapp_export_share_note）写清楚这一点。
     */
    private fun runExport() {
        val selected = adapter.getSelected()
        if (selected.isEmpty()) {
            Notify.show(R.string.hk_miniapp_export_select_first)
            return
        }
        val targets = adapter.getItems().filter { selected.contains(it.key) && HkStore.isMiniapp(it) }
        if (targets.isEmpty()) {
            Notify.show(R.string.hk_miniapp_export_not_miniapp)
            return
        }
        val titles = LinkedHashSet<String>()
        for (site in targets) {
            val title = HkStore.titleOf(site)
            if (title.isNotEmpty()) titles.add(title)
        }
        if (titles.isEmpty()) {
            Notify.show(R.string.hk_miniapp_export_not_miniapp)
            return
        }
        binding.btnExport.isEnabled = false
        Thread {
            val codes = LinkedHashMap<String, String>()
            var firstError: String? = null
            var failed = 0
            for (title in titles) {
                try {
                    codes[title] = HkCloudExport.uploadTextDb(title)
                } catch (e: Exception) {
                    failed++
                    if (firstError == null) firstError = e.message ?: e.toString()
                }
            }
            runOnUiThread {
                binding.btnExport.isEnabled = true
                if (codes.isEmpty()) {
                    Notify.show(getString(R.string.hk_miniapp_export_failed, firstError.orEmpty()))
                    return@runOnUiThread
                }
                if (failed > 0) {
                    Notify.show(getString(R.string.hk_miniapp_export_partial, codes.size, failed, firstError.orEmpty()))
                }
                val share = Intent(Intent.ACTION_SEND)
                share.type = "text/plain"
                share.putExtra(Intent.EXTRA_TEXT, HkCloudExport.shareText(codes, getString(R.string.hk_miniapp_export_share_note)))
                val chooserTitle = if (codes.size == 1) getString(R.string.hk_miniapp_export_share_title_one, codes.keys.first())
                else getString(R.string.hk_miniapp_export_share_title_many, codes.size)
                startActivity(Intent.createChooser(share, chooserTitle))
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
        VodConfig.load(Config.vod(), object : Callback() {
            override fun success() {
                ConfigEvent.vod()
            }
        })
    }

    /** 点一条规则：进它自己的列表页（第 3 步），不切站源、不进蜘蛛空页。 */
    override fun onItemClick(item: Site) {
        HkListActivity.open(this, HkStore.titleOf(item))
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
        // 多选框只为导出服务，不需要选中计数 UI。
    }
}
