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
    @Volatile private var starting = false
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

    @Synchronized
    fun startServer(onStatusUpdate: (String) -> Unit) {
        if (isServerReady) {
            readyListener?.invoke(true)
            return
        }
        if (starting) {
            onStatusUpdate("服务启动中...")
            return
        }
        starting = true
        serviceScope.launch {
            try {
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

                // 上轮进程被 ROM 强杀（onDestroy 不执行）时，老 node 可能残留占着 3030，
                // 这会导致健康检查永远失败、UI 永远转圈。spawn 前按同 uid 清理残留。
                killLeftoverRuntimes(runtimeDir = RuntimeInstaller.getRuntimeDir(this@ServerService))

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

            // 设备指纹与内核页大小（一行为后续真机日志定位提供基线；
            // MagicOS/部分新机型用 16KB 页内核，旧 4KB 对齐的 ELF 会在 execve 处直接失败）
            val pageSize = try {
                android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            } catch (e: Exception) {
                -1L
            }
            Log.i(
                TAG,
                "Device fingerprint: model=${Build.MODEL} brand=${Build.BRAND} sdk=${Build.VERSION.SDK_INT} " +
                    "abis=${Build.SUPPORTED_ABIS?.joinToString(",")} pageSize=$pageSize"
            )
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

            val proc = pb.start()
            nodeProcess = proc
            startProcessLogDrain(proc)
            // 捕获 node 在服务就绪前就挂掉的场景：UI 只转圈没有任何提示，
            // 退出码是这类故障唯一可见信号（spawn 成功但 exec 后崩溃/被杀）。
            launch {
                    try {
                        val code = proc.waitFor()
                        if (!isServerReady) {
                            Log.e(TAG, "Node process died before server ready, exitCode=$code")
                            onStatusUpdate("服务进程异常退出(code=$code)")
                        } else {
                            Log.i(TAG, "Node process exited after ready, exitCode=$code")
                        }
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "waitFor interrupted", e)
                    }
                }

                // 3. 轮询健康检测，直到服务端返回 200
                var attempts = 0
                while (isActive && attempts < 90) {
                    delay(1000)
                    attempts++
                    if (checkHealth()) {
                        isServerReady = true
                        releaseWakeLockIfHeld()
                        Log.i(TAG, "Zcode-x HTTP/WS server is now ready!")
                        withContext(Dispatchers.Main) {
                            readyListener?.invoke(true)
                        }
                        break
                    }
                }
                if (!isServerReady) {
                    val msg = "服务启动超时（90 秒内无响应），设备 ${Build.MODEL} 页大小 $pageSize"
                    Log.e(TAG, msg)
                    onStatusUpdate(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to spawn node server process", e)
                onStatusUpdate("服务启动异常: ${e.message}")
            } finally {
                starting = false
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
     * 清理上次运行残留的同 uid node 进程（zcode.mjs / entry-http.js 属于本应用运行时）。
     * ROM 强杀应用时 onDestroy 不一定会执行，残留 node 会继续占住 3030，导致应用重启后
     * 健康检查永远失败、UI 永远转圈。spawn 前精确按「cmdline 同时含本包名路径与
     * zcode.mjs/entry-http.js」匹配，只动自己的残留，读不到 /proc 的进程直接跳过。
     */
    private fun killLeftoverRuntimes(runtimeDir: File) {
        val runtimePath = runtimeDir.absolutePath
        val pkg = applicationContext.packageName
        if (!runtimePath.contains(pkg)) return // 防御：路径异常时不做任何清理
        try {
            File("/proc").listFiles()?.forEach { pidDir ->
                val name = pidDir.name
                if (name.isEmpty() || !name.all { it.isDigit() }) return@forEach
                val pid = name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val cmdline = try {
                    File(pidDir, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
                } catch (_: Exception) {
                    return@forEach
                }
                if (!cmdline.contains(runtimePath)) return@forEach
                if (cmdline.contains("zcode.mjs") || cmdline.contains("entry-http.js")) {
                    Log.i(TAG, "Killing leftover runtime pid=$pid cmd=${cmdline.take(120)}")
                    try {
                        android.os.Process.killProcess(pid)
                    } catch (e: Exception) {
                        Log.w(TAG, "kill leftover $pid failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "killLeftoverRuntimes scan failed: ${e.message}")
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
            acquire(2 * 60 * 1000L) // 仅启动窗口持有；就绪后由 releaseWakeLock 释放
        }
    }

    private fun releaseWakeLockIfHeld() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
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
        // START_STICKY 复活路径（intent==null）：系统/ROM 杀进程后用 sticky 重建了
        // service，但 node 不会自动拉起，应用只会在转圈页死等。这里主动恢复启动。
        if (intent == null && !isServerReady && nodeProcess?.isAlive != true) {
            Log.i(TAG, "onStartCommand restarted by system (sticky), resuming server")
            startServer { msg -> Log.i(TAG, "resume status: $msg") }
        }
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
        // ROM 强杀不走这里，但正常停止/显式重启时要确保 node 与 3030 一并释放
        nodeProcess?.destroy()
        nodeProcess?.destroyForcibly()
        releaseWakeLockIfHeld()
        Log.i(TAG, "ServerService destroyed and process stopped")
    }

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "zcode_x_service_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
