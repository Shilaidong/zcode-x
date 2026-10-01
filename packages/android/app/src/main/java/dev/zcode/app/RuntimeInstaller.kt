package dev.zcode.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object RuntimeInstaller {
    private const val TAG = "RuntimeInstaller"
    private const val DOWNLOAD_URL =
        "https://github.com/Shilaidong/zcode-x/releases/download/v3.14.3-x/zcode-3.14.3.tar.gz"

    fun getRuntimeDir(context: Context): File {
        return File(context.filesDir, "runtime")
    }

    fun getNodeExecutable(context: Context): File {
        // 检查私有运行时中的 node 可执行文件；若不存在则回退至系统中的 node (如已安装 Termux 或自带 node)
        val bundledNode = File(getRuntimeDir(context), "bin/node")
        if (bundledNode.exists() && bundledNode.canExecute()) {
            return bundledNode
        }
        val termuxNode = File("/data/data/com.termux/files/usr/bin/node")
        if (termuxNode.exists()) {
            return termuxNode
        }
        return File("node")
    }

    fun isRuntimeInstalled(context: Context): Boolean {
        val runtimeDir = getRuntimeDir(context)
        val runnerScript = File(runtimeDir, "zcode/bin/zcode.mjs")
        return runnerScript.exists()
    }

    suspend fun ensureRuntime(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val runtimeDir = getRuntimeDir(context)
        if (isRuntimeInstalled(context)) {
            Log.i(TAG, "Runtime already installed in: ${runtimeDir.absolutePath}")
            return@withContext true
        }

        runtimeDir.mkdirs()

        // 1. 尝试从 APK 内置 assets 复制并解压
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (assetList.contains("zcode-runtime.tar.gz")) {
                onProgress("正在从安装包释放核心运行时...")
                context.assets.open("zcode-runtime.tar.gz").use { input ->
                    extractTarGz(input, runtimeDir)
                }
                makeExecutable(runtimeDir)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract from assets, falling back to download", e)
        }

        // 2. 若 assets 中未内嵌完整运行时包（控制 APK 初始大小），则从 GitHub Releases 下载
        try {
            onProgress("正在下载 Zcode-x 核心运行时 (约 78MB)...")
            val tempArchive = File(context.cacheDir, "zcode-runtime.tar.gz")
            downloadFile(DOWNLOAD_URL, tempArchive) { downloaded, total ->
                val percent = if (total > 0) (downloaded * 100 / total) else 0
                onProgress("正在下载运行时组件... $percent%")
            }

            onProgress("正在解压安装运行时...")
            tempArchive.inputStream().use { input ->
                extractTarGz(input, runtimeDir)
            }
            tempArchive.delete()
            makeExecutable(runtimeDir)
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download runtime", e)
            onProgress("运行时初始化失败: ${e.message}")
            return@withContext false
        }
    }

    private fun makeExecutable(directory: File) {
        try {
            val binDir = File(directory, "zcode/bin")
            binDir.listFiles()?.forEach { file ->
                file.setExecutable(true, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set executable flags", e)
        }
    }

    private fun downloadFile(urlStr: String, destination: File, onProgress: (Long, Long) -> Unit) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true

        val totalLength = conn.contentLengthLong
        var downloaded = 0L

        conn.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onProgress(downloaded, totalLength)
                }
            }
        }
    }

    /**
     * 极轻量流式解包 tar.gz 到指定目录
     */
    private fun extractTarGz(inputStream: InputStream, targetDir: File) {
        // 使用系统 tar 命令解压以保留 POSIX 权限和软链接
        val tempTarGz = File(targetDir.parentFile, "extract_temp.tar.gz")
        FileOutputStream(tempTarGz).use { out ->
            inputStream.copyTo(out)
        }

        try {
            val process = ProcessBuilder("tar", "-xzf", tempTarGz.absolutePath, "-C", targetDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w(TAG, "System tar exited with code $exitCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "System tar failed, fallback manually", e)
        } finally {
            tempTarGz.delete()
        }
    }
}
