// 苍竹输入法 · 文曲星（诗词提示）
// 包管理：内置包随 APK 发布，首次启动或版本变化时拷到私有目录；用户导入的包放在
// Documents/BambooIME/wenquxing/。查询时按启用顺序合并去重。
package com.osfans.trime.actions.wenquxing

import android.content.Context
import android.os.Environment
import com.osfans.trime.BuildConfig
import java.io.File

object WenquxingStore {

    /** 内置包在 assets 里的目录。 */
    private const val ASSET_DIR = "wenquxing"

    /** 私有目录名（用户不可见）。 */
    private const val PRIVATE_DIR = "wenquxing"

    /** 用户可见目录名。 */
    private const val USER_DIR = "BambooIME/wenquxing"

    private const val MARKER = ".builtin-version"

    /** 内置包集合有变化时手动加一，让老安装能重拷。 */
    private const val PACKS_STAMP = "2"

    private val packs = ArrayList<WenquxingPack>()

    @Volatile
    private var loaded = false

    /** 已载入的包名（按载入顺序）。 */
    fun packNames(): List<String> = packs.map { it.title }

    fun builtinDir(context: Context): File = File(context.filesDir, PRIVATE_DIR)

    fun userDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), USER_DIR)

    /** 内置包是否有随本版 APK 更新；有则重新拷出。 */
    fun syncBuiltin(context: Context) {
        if (!BuildConfig.BUNDLED_WENQUXING_PACKS) return
        val target = builtinDir(context)
        if (!target.exists() && !target.mkdirs()) return
        val marker = File(target, MARKER)
        val assets = context.assets
        val names = runCatching { assets.list(ASSET_DIR) }.getOrNull() ?: return
        // 包集合变了也要重拷，不能只看版本号
        val stamp = runCatching { BuildConfig.BUILD_VERSION_NAME }.getOrDefault("dev") +
            "/" + PACKS_STAMP + "/" + names.size
        if (marker.isFile && marker.readText().trim() == stamp) return
        for (name in names) {
            if (!name.endsWith(WenquxingPack.SUFFIX)) continue
            runCatching {
                assets.open("$ASSET_DIR/$name").use { input ->
                    File(target, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        runCatching { marker.writeText(stamp) }
    }

    /** 扫描并载入所有包；已在内存里的先关掉。 */
    fun reload(context: Context) {
        synchronized(this) {
            packs.forEach { runCatching { it.close() } }
            packs.clear()
            val dirs = listOf(builtinDir(context), userDir())
            for (dir in dirs) {
                val files = runCatching {
                    dir.listFiles { file -> file.isFile && file.name.endsWith(WenquxingPack.SUFFIX) }
                }.getOrNull() ?: continue
                for (file in files.sortedBy { it.name }) {
                    WenquxingPack.open(file)?.let { packs.add(it) }
                }
            }
            loaded = true
            com.osfans.trime.actions.handwriting.HandwritingLog.d(
                "wenquxing",
                "loaded packs=" + packs.size + " " + packs.joinToString(",") { it.name },
            )
        }
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            syncBuiltin(context)
            reload(context)
        }
    }

    /**
     * 查一个键，返回合并后的命中（同一条内容只留一次，保持包的先后顺序）。
     * 只看启用的包。
     */
    fun query(key: String, enabled: (String) -> Boolean = { true }): List<Pair<String, WenquxingPack.Hit>> {
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, WenquxingPack.Hit>>()
        val seen = HashSet<String>()
        synchronized(this) {
            for (pack in packs) {
                if (!enabled(pack.name)) continue
                for (hit in runCatching { pack.lookup(key) }.getOrDefault(emptyList())) {
                    if (!seen.add(hit.text)) continue
                    out.add(pack.name to hit)
                }
            }
        }
        return out
    }
}
