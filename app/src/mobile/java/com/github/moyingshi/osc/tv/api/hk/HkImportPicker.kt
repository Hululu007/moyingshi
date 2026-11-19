package com.github.moyingshi.osc.tv.api.hk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 小程序导入的 SAF 接线，阶段 6b 重写后端（见 docs/miniapp-xingchen-6b-share-publish.md §2.6）。
 *
 * 不变的部分（相对 6b 之前）：
 * - 包名；SAF 选择器（文件多选 / 目录树）与拿持久读权限；
 * - 复制 + 收集在后台线程做；文件选择按扩展名过滤；
 * - 缓存文件名用 nanoTime，避免同一毫秒同名覆盖；
 * - 取消选择器时不回调。
 *
 * 改动（相对 6b 之前）：
 * - 后端由 HkStore.importFiles 改走星辰助手 HkShareHelper.importFromFile
 *  （.json/.txt/.hiker 单规则文本、.zip/.hkzip 包、合集包），成功后经
 *   HkStore.registerImportedRules 登记站源；单文件失败记日志、继续下一个。
 * - 合集包只解压落盘、不自动导入条目（星辰官形；条目点选面在阶段 7），added 计 0。
 * - 无 mirrorToCache 失败重试（星辰 importFromFile 无重试）。
 * - isRuleFile 新收 .txt/.hiker（星辰 importFromFile 支持的单规则文本格式）。
 *
 * 用法（在 Activity 的 onCreate 里，先于使用创建）：
 *     picker = HkImportPicker(this) { added -> ... }
 *     // “文件/目录”按钮 -> picker.pickFiles() / picker.pickDirectory()
 *
 * 依赖：androidx.documentfile:documentfile:1.0.1。
 * 只收 .json / .txt / .hiker / .zip / .hkzip；目录导入递归子目录。
 */
class HkImportPicker(
    private val activity: AppCompatActivity,
    private val onImported: (added: Int) -> Unit
) {
    private val pickFilesLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris -> importUris(uris) }

    private val pickTreeLauncher: ActivityResultLauncher<Uri?> =
        activity.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val root = DocumentFile.fromTreeUri(activity, uri) ?: return@registerForActivityResult
            importDirectory(root)
        }

    /** 文件导入：系统选择器，可多选 .json / .txt / .hiker / .zip / .hkzip。 */
    fun pickFiles() {
        pickFilesLauncher.launch(arrayOf("application/json", "application/zip", "*/*"))
    }

    /** 目录导入：系统选择器选一个目录，递归收里面的规则文件。 */
    fun pickDirectory() {
        pickTreeLauncher.launch(null)
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Thread {
            val files = uris.mapNotNull { uri -> copyUriToCache(activity, uri) }
            val added = importFiles(files)
            activity.runOnUiThread { onImported(added) }
        }.start()
    }

    private fun importDirectory(root: DocumentFile) {
        Thread {
            val files = mutableListOf<File>()
            collectRuleFiles(root, files)
            val added = importFiles(files)
            activity.runOnUiThread { onImported(added) }
        }.start()
    }

    /**
     * 逐文件经新助手导入并登记站源，返回成功登记的条数。
     * 合集包只解压落盘（HkShareHelper.importFromFile 回空列表），added 计 0，见类头注。
     * 单文件失败记日志、继续下一个文件（与旧 HkStore.importFiles 的逐文件容错同口径）。
     */
    private fun importFiles(files: List<File>): Int {
        var added = 0
        for (file in files) {
            try {
                val rules = HkShareHelper.importFromFile(file)
                added += HkStore.registerImportedRules(rules)
            } catch (e: Exception) {
                Log.w(TAG, "import failed: " + file.name, e)
            }
        }
        return added
    }

    private fun collectRuleFiles(dir: DocumentFile, out: MutableList<File>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                collectRuleFiles(child, out)
            } else if (isRuleFile(child.name)) {
                copyUriToCache(activity, child.uri)?.let { out.add(it) }
            }
        }
    }

    companion object {
        private const val TAG = "HkImportPicker"

        private fun isRuleFile(name: String?): Boolean {
            val n = name?.lowercase().orEmpty()
            return n.endsWith(".json") || n.endsWith(".txt") || n.endsWith(".hiker")
                    || n.endsWith(".zip") || n.endsWith(".hkzip")
        }

        /** SAF 的 Uri 读一次就复制到缓存，得到 importFiles() 能用的 File；不是规则文件返回 null。 */
        private fun copyUriToCache(context: Context, uri: Uri): File? = try {
            val rawName = DocumentFile.fromSingleUri(context, uri)?.name
            if (!isRuleFile(rawName)) return null
            val name = (rawName ?: "hk_import_${System.currentTimeMillis()}")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(context.cacheDir, "hk_import").apply { mkdirs() }
            val out = File(dir, "${System.nanoTime()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            null
        }
    }
}
