/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.link

import com.osfans.trime.data.ResourceUrls
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.FileDownloader
import com.osfans.trime.util.appContext
import com.osfans.trime.util.computeFileSha256
import timber.log.Timber
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

object VoiceModelManager {
    /**
     * Local voice models are stored per backend and per chunk size. QNN models are
     * `.so` model libs that must be dlopen'd (external/shared storage is noexec), and
     * CPU models are plain ONNX files; keeping the two backends in separate dirs lets
     * both be installed side by side without mixing file names.
     *
     * Layout: `filesDir/voice-{qnn,cpu}/<chunk>ms/`.
     */
    private fun chunkMs(): Int = AppPrefs.defaultInstance().voiceInput.voiceChunkSize.getValue().ms

    private fun chunkRoot(backend: VoiceBackend): File = File(
        appContext.filesDir,
        when (backend) {
            VoiceBackend.QNN -> "voice-qnn"
            VoiceBackend.CPU -> "voice-cpu"
        },
    )

    fun voiceDir(backend: VoiceBackend): File = File(chunkRoot(backend), "${chunkMs()}ms").also { dir -> dir.mkdirs() }

    fun getDownloadFileName(): String = getDownloadUrl().substringAfterLast('/')

    fun getDownloadUrl(backend: VoiceBackend = VoiceBackendSelector.preferred()): String {
        val chunkMs = chunkMs()
        return when (backend) {
            VoiceBackend.QNN -> ResourceUrls.buildQnnVoiceModelUrl(chunkMs)
            VoiceBackend.CPU -> ResourceUrls.buildCpuVoiceModelUrl(chunkMs)
        }
    }

    private fun releaseApiUrl(backend: VoiceBackend): String = when (backend) {
        VoiceBackend.QNN -> ResourceUrls.VOICE_MODEL_QNN_RELEASE_API
        VoiceBackend.CPU -> ResourceUrls.VOICE_MODEL_CPU_RELEASE_API
    }

    /** CNB 镜像地址；未镜像的后端/档位返回 null。 */
    fun getCnbDownloadUrl(backend: VoiceBackend = VoiceBackendSelector.preferred()): String? = when (backend) {
        VoiceBackend.CPU -> ResourceUrls.buildCnbCpuVoiceModelUrl(chunkMs())
        VoiceBackend.QNN -> null
    }

    /** 内置的期望大小与 sha256（目前只有 CPU 四档）；没有内置值时回落到 GitHub API。 */
    fun getBundledAsset(backend: VoiceBackend = VoiceBackendSelector.preferred()): ResourceUrls.VoiceModelAsset? =
        if (backend == VoiceBackend.CPU) ResourceUrls.VOICE_MODEL_CPU_ASSETS[chunkMs()] else null

    suspend fun getExpectedSha256(): String? {
        getBundledAsset()?.let { return it.sha256 }
        val backend = VoiceBackendSelector.preferred()
        return ResourceUrls.GitHubAssetCache.getSha256(
            getDownloadUrl(backend),
            releaseApiUrl(backend),
        )
    }

    /**
     * 从「最快的可用源」下载语音模型，失败自动换源。返回实际成功的 URL。
     *
     * 源顺序由 [VoiceModelMirror.orderedCandidates] 决定（自动测速或用户指定）。
     */
    suspend fun downloadWithMirror(
        destFile: File,
        onProgress: ((Float, Long, Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        onAttempt: (label: String, index: Int, total: Int) -> Unit = { _, _, _ -> },
    ): String {
        val candidates = VoiceModelMirror.orderedCandidates()
        val expectedSha256 = getExpectedSha256()
        var lastError: Exception? = null

        candidates.forEachIndexed { index, candidate ->
            onAttempt(candidate.label, index + 1, candidates.size)
            try {
                FileDownloader.download(
                    url = candidate.url,
                    destFile = destFile,
                    expectedSha256 = expectedSha256,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                if (index > 0) {
                    Timber.i("Voice model: downloaded from fallback source ${candidate.label}")
                }
                return candidate.url
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "Voice model: source ${candidate.label} failed")
                destFile.delete()
            }
        }

        throw lastError ?: IllegalStateException("No download source available")
    }

    fun verifySha256(
        file: File,
        expectedHash: String?,
    ): Boolean {
        if (expectedHash == null) return false
        val hash = computeFileSha256(file) ?: return false
        return hash.equals(expectedHash, ignoreCase = true)
    }

    suspend fun verifySha256AnyVariant(file: File): Boolean {
        val hash = computeFileSha256(file) ?: return false
        val knownSha256s = ResourceUrls.GitHubAssetCache.getAllKnownSha256s(
            ResourceUrls.VOICE_MODEL_QNN_RELEASE_API,
            ResourceUrls.VOICE_MODEL_CPU_RELEASE_API,
        )
        return knownSha256s.any { hash.equals(it, ignoreCase = true) }
    }

    private const val DEFAULT_ARCHIVE_BYTES = 600L * 1024 * 1024
    private const val STORAGE_MARGIN_BYTES = 64L * 1024 * 1024

    /**
     * Free space needed to download + extract the current model. The archive is
     * cached while extracting, so roughly twice the archive size is required.
     */
    suspend fun getRequiredFreeSpaceBytes(): Long {
        val backend = VoiceBackendSelector.preferred()
        val url = getDownloadUrl(backend)
        val archiveBytes = getBundledAsset(backend)?.size
            ?: ResourceUrls.GitHubAssetCache.getAssetSize(url, releaseApiUrl(backend))?.takeIf { it > 0 }
            ?: DEFAULT_ARCHIVE_BYTES
        return archiveBytes * 2 + STORAGE_MARGIN_BYTES
    }

    fun hasEnoughSpace(requiredBytes: Long): Boolean = appContext.filesDir.usableSpace >= requiredBytes

    data class ModelFiles(
        val tokens: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
        val backend: VoiceBackend,
    ) {
        /** QNN context binary paths; generated on-device on first use. Only for QNN. */
        val qnnContextBinary: String
            get() {
                val dir = encoder.parentFile!!
                return listOf(encoder, decoder, joiner)
                    .joinToString(",") {
                        "${dir.absolutePath}/${it.nameWithoutExtension.removePrefix("lib")}.bin"
                    }
            }
    }

    private fun contextBinaryFiles(dir: File): List<File> = listOf("encoder.bin", "decoder.bin", "joiner.bin").map { File(dir, it) }

    fun hasContextBinaries(): Boolean {
        val dir = voiceDir(VoiceBackend.QNN)
        if (!dir.isDirectory) return false
        return contextBinaryFiles(dir).all { it.isFile }
    }

    fun resolveModelFiles(backend: VoiceBackend = VoiceBackendSelector.preferred()): ModelFiles? {
        val dir = voiceDir(backend)
        if (!dir.isDirectory) return null
        return when (backend) {
            VoiceBackend.QNN -> resolveQnnModelFiles(dir)
            VoiceBackend.CPU -> resolveCpuModelFiles(dir)
        }
    }

    /**
     * Model resolution used by the engine: prefer the selected backend, and fall
     * back to the CPU model when QNN is selected but its model is not installed.
     */
    fun resolveActiveModelFiles(): ModelFiles? {
        val preferred = VoiceBackendSelector.preferred()
        resolveModelFiles(preferred)?.let { return it }
        return if (preferred == VoiceBackend.QNN) resolveModelFiles(VoiceBackend.CPU) else null
    }

    private fun resolveQnnModelFiles(dir: File): ModelFiles? {
        val tokensFile = File(dir, "tokens.txt")
        if (!tokensFile.exists()) {
            Timber.w("Voice model check: tokens.txt not found in $dir")
            return null
        }

        val libEncoder = File(dir, "libencoder.so")
        val libDecoder = File(dir, "libdecoder.so")
        val libJoiner = File(dir, "libjoiner.so")
        val hasLibs = libEncoder.isFile && libDecoder.isFile && libJoiner.isFile

        if (!hasLibs && !contextBinaryFiles(dir).all { it.isFile }) {
            Timber.w("Voice model check: neither .so model libs nor .bin context binaries found in $dir")
            return null
        }

        return if (hasLibs) {
            ModelFiles(tokensFile, libEncoder, libDecoder, libJoiner, VoiceBackend.QNN)
        } else {
            val bins = contextBinaryFiles(dir)
            ModelFiles(tokensFile, bins[0], bins[1], bins[2], VoiceBackend.QNN)
        }
    }

    private fun resolveCpuModelFiles(dir: File): ModelFiles? {
        val tokensFile = File(dir, "tokens.txt")
        if (!tokensFile.isFile) {
            Timber.w("Voice model check: tokens.txt not found in $dir")
            return null
        }

        val encoder = firstExisting(dir, "encoder.onnx", "encoder.int8.onnx", "encoder.fp16.onnx")
        val decoder = firstExisting(dir, "decoder.onnx", "decoder.int8.onnx")
        val joiner = firstExisting(dir, "joiner.onnx", "joiner.int8.onnx", "joiner.fp16.onnx")
        if (encoder == null || decoder == null || joiner == null) {
            Timber.w("Voice model check: incomplete ONNX transducer model in $dir")
            return null
        }
        return ModelFiles(tokensFile, encoder, decoder, joiner, VoiceBackend.CPU)
    }

    private fun firstExisting(dir: File, vararg names: String): File? = names.asSequence().map { File(dir, it) }.firstOrNull { it.isFile }

    fun checkModelFiles(backend: VoiceBackend): Boolean = resolveModelFiles(backend) != null

    fun checkModelFiles(): Boolean = resolveActiveModelFiles() != null

    /**
     * Delete the original .so model libs once the QNN context binaries have been
     * generated on-device. No-op (returns false) unless all three .bin files exist,
     * so a failed context binary save keeps the .so fallback.
     */
    fun deleteModelLibsIfContextBinaryReady(): Boolean {
        if (!hasContextBinaries()) return false
        val dir = voiceDir(VoiceBackend.QNN)
        val results =
            listOf("libencoder.so", "libdecoder.so", "libjoiner.so").map {
                val f = File(dir, it)
                !f.exists() || f.delete()
            }
        if (results.any { !it }) {
            Timber.w("Voice model: failed to delete some .so model libs")
        }
        return results.all { it }
    }

    /** Delete the current chunk's model files for both backends (other chunks are kept). */
    fun deleteModel(): Boolean {
        val qnnDeleted = voiceDir(VoiceBackend.QNN).deleteRecursively()
        val cpuDeleted = voiceDir(VoiceBackend.CPU).deleteRecursively()
        return qnnDeleted || cpuDeleted
    }

    fun extractZip(
        zipFile: File,
        targetDir: File,
    ) = com.osfans.trime.util.extractZip(zipFile, targetDir)

    fun extractTarBz2(
        tarFile: File,
        targetDir: File,
    ) = com.osfans.trime.util.extractTarBz2(tarFile, targetDir)

    fun autoExtract(
        archiveFile: File,
        targetDir: File,
    ) {
        val name = archiveFile.name.lowercase()
        val isTarBz2 = name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tar.bzip2")
        val isZip = name.endsWith(".zip")

        val format = when {
            isTarBz2 -> "tar.bz2"
            isZip -> "zip"
            else -> detectArchiveFormat(archiveFile)
        }

        when (format) {
            "tar.bz2" -> extractTarBz2(archiveFile, targetDir)
            "zip" -> extractZip(archiveFile, targetDir)
            else -> throw IllegalArgumentException("Unsupported archive format: ${archiveFile.name}")
        }

        cleanupNonModelFiles(targetDir)
    }

    /**
     * Extract an archive into the directory of the backend it belongs to, so a QNN
     * package imported on a CPU-only device (or vice versa) does not end up in the
     * wrong place. Returns the detected backend.
     */
    fun installArchive(
        archiveFile: File,
        sourceName: String = archiveFile.name,
    ): VoiceBackend {
        val byName = detectBackendFromName(sourceName)
        if (byName != null) {
            autoExtract(archiveFile, voiceDir(byName))
            Timber.i("Voice model: installed archive for backend=$byName")
            return byName
        }

        // Unknown file name: extract into a staging dir, inspect the contents, then
        // merge into the matching backend dir.
        val staging = File(appContext.cacheDir, "voice_staging_${System.nanoTime()}")
        try {
            autoExtract(archiveFile, staging)
            val backend = detectBackendFromDir(staging) ?: VoiceBackendSelector.preferred()
            val target = voiceDir(backend)
            target.mkdirs()
            staging.listFiles()?.forEach { child ->
                val dest = File(target, child.name)
                if (child.isDirectory) {
                    dest.deleteRecursively()
                    child.copyRecursively(dest, overwrite = true)
                } else {
                    dest.delete()
                    if (!child.renameTo(dest)) child.copyTo(dest, overwrite = true)
                }
            }
            Timber.i("Voice model: installed archive for backend=$backend (detected by content)")
            return backend
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun detectBackendFromName(name: String): VoiceBackend? {
        val lower = name.lowercase()
        return when {
            lower.contains("qnn") -> VoiceBackend.QNN
            lower.contains("x-asr") || lower.contains("x_asr") -> VoiceBackend.CPU
            else -> null
        }
    }

    private fun detectBackendFromDir(dir: File): VoiceBackend? {
        val names = dir.listFiles()?.map { it.name.lowercase() } ?: return null
        return when {
            names.any { it == "libencoder.so" } -> VoiceBackend.QNN
            names.any { it.startsWith("encoder") && it.endsWith(".bin") } -> VoiceBackend.QNN
            names.any { it.startsWith("encoder") && it.endsWith(".onnx") } -> VoiceBackend.CPU
            else -> null
        }
    }

    private fun cleanupNonModelFiles(dir: File) {
        val junkNames = setOf("test_wavs", "README.md", "test_onnx.py")
        val junkFiles = dir.listFiles { f -> f.name in junkNames } ?: return
        for (f in junkFiles) {
            f.deleteRecursively()
            Timber.d("Voice model: cleaned up ${f.name}")
        }
    }

    private fun detectArchiveFormat(file: File): String {
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        if (header[0] == 0x42.toByte() && header[1] == 0x5A.toByte() && header[2] == 0x68.toByte()) {
            return "tar.bz2"
        }
        if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
            return "zip"
        }
        return "unknown"
    }

    fun archiveExtensionFromUrl(url: String): String {
        val name = url.substringAfterLast('/').substringBefore('?').lowercase()
        return when {
            name.endsWith(".tar.bz2") -> ".tar.bz2"
            name.endsWith(".tbz2") -> ".tbz2"
            name.endsWith(".tar.bzip2") -> ".tar.bzip2"
            name.endsWith(".zip") -> ".zip"
            else -> ".tar.bz2"
        }
    }
}
