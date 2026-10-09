package dev.zcode.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ServerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nodeProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServerReady = false
    private var readyListener: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): ServerService = this@ServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    fun setOnServerReadyListener(listener: (Boolean) -> Unit) {
        this.readyListener = listener
        if (isServerReady) {
            listener(true)
        }
    }

    fun startServer(onStatusUpdate: (String) -> Unit) {
        serviceScope.launch {
            if (isServerReady) {
                withContext(Dispatchers.Main) { readyListener?.invoke(true) }
                return@launch
            }

            // 1. 确保运行时就绪
            val runtimeOk = RuntimeInstaller.ensureRuntime(this@ServerService) { msg ->
                onStatusUpdate(msg)
            }

            if (!runtimeOk) {
                onStatusUpdate("运行时准备失败，无法启动服务")
                return@launch
            }

            onStatusUpdate("正在启动 Zcode-x 服务进程...")

            // 2. 启动 Node 进程
            val runtimeDir = RuntimeInstaller.getRuntimeDir(this@ServerService)
            val runnerScript1 = File(runtimeDir, "zcode/bin/zcode.mjs")
            val runnerScript2 = File(runtimeDir, "bin/zcode.mjs")
            val runnerScript = if (runnerScript1.exists()) runnerScript1 else runnerScript2
            val nodeBinary = RuntimeInstaller.getNodeExecutable(this@ServerService)

            if (!nodeBinary.exists()) {
                val errMsg = "未找到可执行 Node.js 执行引擎 (路径: ${nodeBinary.absolutePath})"
                Log.e(TAG, errMsg)
                onStatusUpdate(errMsg)
                return@launch
            }

            if (!runnerScript.exists()) {
                val errMsg = "未找到 ZCode 服务启动脚本 (路径: ${runnerScript.absolutePath})"
                Log.e(TAG, errMsg)
                onStatusUpdate(errMsg)
                return@launch
            }

            Log.i(TAG, "Spawning node: ${nodeBinary.absolutePath} ${runnerScript.absolutePath}")

            val pb = ProcessBuilder(
                nodeBinary.absolutePath,
                runnerScript.absolutePath,
                "--web",
                "--host", "127.0.0.1",
                "--port", "3030",
                "--no-open"
            )

            pb.directory(runtimeDir)
            val env = pb.environment()
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val fallbackLibDir = RuntimeInstaller.getFallbackLibDir(this@ServerService).absolutePath
            val ldPath = "$nativeLibDir:$fallbackLibDir:${File(runtimeDir, "usr/lib").absolutePath}:${File(runtimeDir, "zcode/usr/lib").absolutePath}"
            env["LD_LIBRARY_PATH"] = "$ldPath:${env["LD_LIBRARY_PATH"] ?: ""}"
            env["ZCODE_PRODUCT_FLAVOR"] = "zcode-x"
            env["ZCODE_DATA_BASE_DIR"] = File(filesDir, ".zcode-x").absolutePath
            env["HOME"] = filesDir.absolutePath
            env["TMPDIR"] = cacheDir.absolutePath
            env["PORT"] = "3030"
            val binPath = "$nativeLibDir:$fallbackLibDir:${File(runtimeDir, "zcode/bin").absolutePath}:${File(runtimeDir, "bin").absolutePath}"
            env["PATH"] = "$binPath:${env["PATH"] ?: "/system/bin"}"
            // Android 没有 /bin/sh，也没有 bash/zsh。ZCode 的 Bash 工具 shell 解析器
            // (resolvePosixBashShell) 只接受 basename 含 bash/zsh 的候选，且固定回退目录
            // (/bin:/usr/bin:...) 在本平台均不存在；探测失败后落到 legacy spawn
            // (shell:true)，而捆绑 Node 构建在该路径上的默认解释器不可用。
            // 显式声明系统 shell，并在 PATH 内的 runtime/bin 放置 bash/zsh 别名，
            // 使解析器命中 posix 分支并统一走 /system/bin/sh (toybox)。
            env["SHELL"] = "/system/bin/sh"
            ensurePosixShellAliases(runtimeDir)

            try {
                nodeProcess = pb.start()
                startProcessLogDrain(nodeProcess!!)

                // 3. 轮询健康检测，直到服务端返回 200
                var attempts = 0
                while (isActive && attempts < 90) {
                    delay(1000)
                    attempts++
                    if (checkHealth()) {
                        isServerReady = true
                        Log.i(TAG, "Zcode-x HTTP/WS server is now ready!")
                        withContext(Dispatchers.Main) {
                            readyListener?.invoke(true)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to spawn node server process", e)
                onStatusUpdate("服务启动异常: ${e.message}")
            }
        }
    }

    private fun checkHealth(): Boolean {
        return try {
            val url = URL("http://127.0.0.1:3030/api/server-info")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 在 PATH 覆盖的 runtime/bin 下创建 bash/zsh 符号链接指向 /system/bin/sh。
     * ZCode 的 shell 解析器按 PATH+bash/zsh 搜索候选并要求可执行，命中后
     * Bash 工具即走 posix 分支正常 spawn（Android 系统无 bash/zsh，也没有 /bin/sh）。
     */
    private fun ensurePosixShellAliases(runtimeDir: File) {
        val systemSh = "/system/bin/sh"
        if (!File(systemSh).canExecute()) {
            Log.w(TAG, "ensurePosixShellAliases: $systemSh not executable, skipped")
            return
        }
        val binDir = File(runtimeDir, "bin").apply { mkdirs() }
        for (alias in listOf("bash", "zsh")) {
            val link = File(binDir, alias)
            try {
                if (!link.exists()) {
                    android.system.Os.symlink(systemSh, link.absolutePath)
                    Log.i(TAG, "ensurePosixShellAliases: created ${link.absolutePath} -> $systemSh")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensurePosixShellAliases failed for ${link.absolutePath}: ${e.message}")
            }
        }
    }

    private fun startProcessLogDrain(process: Process) {
        serviceScope.launch {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d("ZcodeNodeOut", line ?: "")
                    }
                }
            } catch (_: Exception) {}
        }

        serviceScope.launch {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.e("ZcodeNodeErr", line ?: "")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZcodeX::ServerWakeLock").apply {
            acquire(10 * 60 * 1000L) // 初始保护 10 分钟
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zcode-x Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保证 Zcode-x 本地编程环境在后台正常执行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_desc))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        nodeProcess?.destroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        Log.i(TAG, "ServerService destroyed and process stopped")
    }

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "zcode_x_service_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
