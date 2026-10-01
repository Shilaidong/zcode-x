package dev.zcode.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

object RuntimeInstaller {
    private const val TAG = "RuntimeInstaller"
    private const val DOWNLOAD_URL =
        "https://github.com/Shilaidong/zcode-x/releases/download/v3.14.3-x/zcode-3.14.3.tar.gz"

    fun getRuntimeDir(context: Context): File {
        return File(context.filesDir, "runtime")
    }

    fun getFallbackLibDir(context: Context): File {
        return File(context.filesDir, "lib")
    }

    fun getNodeExecutable(context: Context): File {
        // 1. 方向二首选：APK 原生库目录中的 libnode.so (由 Android PackageManager 自动释放)
        val nativeLibNode = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
        if (nativeLibNode.exists()) {
            Log.i(TAG, "Using system native bundled libnode: ${nativeLibNode.absolutePath}")
            return nativeLibNode
        }

        // 2. 备选：从私有 fallback 目录 (filesDir/lib/libnode.so) 执行
        val fallbackNode = File(getFallbackLibDir(context), "libnode.so")
        if (fallbackNode.exists() && fallbackNode.canExecute()) {
            Log.i(TAG, "Using fallback libnode: ${fallbackNode.absolutePath}")
            return fallbackNode
        }

        // 3. 检查私有运行时中的 bin/node
        val bundledNode = File(getRuntimeDir(context), "bin/node")
        if (bundledNode.exists() && bundledNode.canExecute()) {
            return bundledNode
        }

        // 4. 检查外部 Termux node
        val termuxNode = File("/data/data/com.termux/files/usr/bin/node")
        if (termuxNode.exists() && termuxNode.canExecute()) {
            return termuxNode
        }

        return nativeLibNode
    }

    fun isRuntimeInstalled(context: Context): Boolean {
        val runtimeDir = getRuntimeDir(context)
        val runnerScript1 = File(runtimeDir, "zcode/bin/zcode.mjs")
        val runnerScript2 = File(runtimeDir, "bin/zcode.mjs")
        return runnerScript1.exists() || runnerScript2.exists()
    }

    suspend fun ensureRuntime(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // 第一步：确保 Node.js 原生底层执行引擎就绪
        ensureNativeLibraries(context, onProgress)

        val runtimeDir = getRuntimeDir(context)
        if (isRuntimeInstalled(context)) {
            Log.i(TAG, "Runtime already installed in: ${runtimeDir.absolutePath}")
            return@withContext true
        }

        runtimeDir.mkdirs()

        // 第二步：尝试从 APK 内置 assets 复制并解压核心静态资源
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            val assetName = assetList.firstOrNull { it.startsWith("zcode-runtime") }
            if (assetName != null) {
                onProgress("正在从安装包释放核心静态资源...")
                context.assets.open(assetName).use { input ->
                    extractArchive(input, runtimeDir)
                }
                makeExecutable(runtimeDir)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract from assets, falling back to download", e)
        }

        // 第三步：若 assets 中未内嵌完整资源包，则从 GitHub Releases 在线下载
        try {
            onProgress("正在下载 Zcode-x 核心资源包 (约 78MB)...")
            val tempArchive = File(context.cacheDir, "zcode-runtime.tar.gz")
            downloadFile(DOWNLOAD_URL, tempArchive) { downloaded, total ->
                val percent = if (total > 0) (downloaded * 100 / total) else 0
                onProgress("正在下载核心资源... $percent%")
            }

            onProgress("正在解压安装静态资源...")
            tempArchive.inputStream().use { input ->
                extractArchive(input, runtimeDir)
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

    /**
     * 自愈机制：若系统未解压 native 库，则主动从 APK 自身提取 so 库
     */
    private fun ensureNativeLibraries(context: Context, onProgress: (String) -> Unit) {
        val nativeLibNode = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
        if (nativeLibNode.exists()) {
            return
        }

        val fallbackLibDir = getFallbackLibDir(context)
        val fallbackNode = File(fallbackLibDir, "libnode.so")
        if (fallbackNode.exists() && fallbackNode.canExecute()) {
            return
        }

        Log.i(TAG, "Extracting bundled native libraries from APK sourceDir...")
        onProgress("正在准备 Node.js 执行引擎...")
        fallbackLibDir.mkdirs()

        try {
            val apkPath = context.applicationInfo.sourceDir
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("lib/arm64-v8a/") && !entry.isDirectory) {
                        val fileName = File(entry.name).name
                        val targetFile = File(fallbackLibDir, fileName)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        targetFile.setReadable(true, false)
                        targetFile.setExecutable(true, false)
                        Log.i(TAG, "Extracted native lib: ${targetFile.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract native libraries from APK", e)
        }
    }

    private fun makeExecutable(directory: File) {
        try {
            val binDirs = listOf(
                File(directory, "zcode/bin"),
                File(directory, "bin")
            )
            for (binDir in binDirs) {
                binDir.listFiles()?.forEach { file ->
                    file.setReadable(true, false)
                    file.setExecutable(true, false)
                }
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
     * 基于 Apache Commons Compress 的工业级解压实现
     * 自动检测 GZIP 压缩头与纯 TAR 格式，完全支持 POSIX/PAX/GNU 长文件名与深层目录树
     */
    private fun extractArchive(inputStream: InputStream, targetDir: File) {
        val bufIn = BufferedInputStream(inputStream)
        bufIn.mark(2)
        val b1 = bufIn.read()
        val b2 = bufIn.read()
        bufIn.reset()

        val isGzip = (b1 == 0x1F && b2 == 0x8B)
        val tarInStream: InputStream = if (isGzip) {
            GzipCompressorInputStream(bufIn)
        } else {
            bufIn
        }

        TarArchiveInputStream(tarInStream).use { tarIn ->
            var entry: TarArchiveEntry?
            val buffer = ByteArray(8192)
            while ((tarIn.nextEntry as? TarArchiveEntry).also { entry = it } != null) {
                val tarEntry = entry ?: break
                val outputFile = File(targetDir, tarEntry.name)

                // 避免 Zip Slip 漏洞
                if (!outputFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    Log.w(TAG, "Skipping suspicious tar entry: ${tarEntry.name}")
                    continue
                }

                if (tarEntry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { out ->
                        var count: Int
                        while (tarIn.read(buffer).also { count = it } != -1) {
                            out.write(buffer, 0, count)
                        }
                    }
                    if (tarEntry.name.contains("bin/") || tarEntry.name.endsWith(".mjs") || tarEntry.name.endsWith(".sh")) {
                        outputFile.setReadable(true, false)
                        outputFile.setExecutable(true, false)
                    }
                }
            }
        }
    }
}
