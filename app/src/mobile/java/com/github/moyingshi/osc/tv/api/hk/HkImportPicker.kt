package com.github.moyingshi.osc.tv.api.hk

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 小程序导入的 SAF 接线，改编自用户给的参考实现 HkImportPicker（com.example.hkminiapp）。
 *
 * 改动（相对参考实现）：
 * - 包名改成咱们的；对接的后端是 HkStore.importFiles（i22 的导入方法），参考实现里
 *   的 HkSiteAdapter 门面咱们还没有，不造。
 * - 复制 + 收集也在后台线程做（参考实现里目录收集在主线程）。
 * - 文件选择也按扩展名过滤（参考实现只在目录路径过滤了扩展名）。
 * - 缓存文件名用 nanoTime，避免同一毫秒同名覆盖。
 * - 取消选择器时不回调（参考实现里空列表也会走到 onImported(0)）。
 *
 * 用法（在 Activity 的 onCreate 里，先于使用创建）：
 *     picker = HkImportPicker(this) { added -> ... }
 *     // “文件/目录”按钮 -> picker.pickFiles() / picker.pickDirectory()
 *
 * 依赖：androidx.documentfile:documentfile:1.0.1。
 * 只收 .json / .zip / .hkzip；目录导入递归子目录。
 * 没在 Android Studio 编译过，以编译结果为准。
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

    /** 文件导入：系统选择器，可多选 .json / .zip / .hkzip。 */
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
            val added = HkStore.importFiles(files)
            activity.runOnUiThread { onImported(added) }
        }.start()
    }

    private fun importDirectory(root: DocumentFile) {
        Thread {
            val files = mutableListOf<File>()
            collectRuleFiles(root, files)
            val added = HkStore.importFiles(files)
            activity.runOnUiThread { onImported(added) }
        }.start()
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
        private fun isRuleFile(name: String?): Boolean {
            val n = name?.lowercase().orEmpty()
            return n.endsWith(".json") || n.endsWith(".zip") || n.endsWith(".hkzip")
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
