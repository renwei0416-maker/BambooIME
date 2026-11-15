/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.wanxiang

import com.osfans.trime.data.packaging.SchemaPackStore
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * 方案包下载 + 解包安装。
 *
 * 两条铁律：
 *  1. **整包覆盖**：官方各变体清单一致，换变体就是从 zip 里重写同名文件；
 *  2. **`custom/` 与 `*.custom.yaml` 一律不装** —— 那是官方给的示例/初始配置，
 *     真正的配置由 `WanxiangCustom` 写，装进来会互相打架。
 */
object WanxiangInstaller {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 下载到 [cacheDir]，回调 (已下载, 总长)。总长未知时为 0。 */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        runCatching {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) {
                Timber.w("wanxiang: HTTP ${resp.code} for $url")
                return@runCatching
            }
            val total = resp.body?.contentLength() ?: 0L
            dest.parentFile?.mkdirs()
            var done = 0L
            var lastNotify = 0L
            resp.body!!.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastNotify > 262144 || done == total) {
                            lastNotify = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            ok = dest.length() > 0
        }.onFailure { Timber.w(it, "wanxiang: download failed") }
        ok
    }

    /**
     * 把 zip 解到 [dir]（覆盖同名文件，跳过 `custom/`）。
     *
     * @return 写了多少个文件；0 表示失败。
     */
    suspend fun install(
        zip: File,
        dir: File,
        variantId: String,
        onProgress: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        runCatching {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (entry.isDirectory) continue
                    if (name.startsWith("custom/") || name.endsWith(".custom.yaml")) {
                        Timber.d("wanxiang: skip $name")
                        continue
                    }
                    if (name.contains("..")) continue
                    val out = File(dir, name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                    count++
                    if (count % 8 == 0) onProgress(name)
                }
            }
            if (count > 0) {
                SchemaPackStore.writeManifest(
                    dir = dir,
                    stamp = SchemaPackStore.currentStamp(),
                    files = count,
                    variantId = variantId,
                )
            }
        }.onFailure { Timber.w(it, "wanxiang: unzip failed") }
        count
    }
}
