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
            val ldPath = "$nativeLibDir:$fallbackLibDir:${File(runtimeDir, "usr/lib").absolutePath}"
            env["LD_LIBRARY_PATH"] = "$ldPath:${env["LD_LIBRARY_PATH"] ?: ""}"
            env["ZCODE_PRODUCT_FLAVOR"] = "zcode-x"
            env["ZCODE_DATA_BASE_DIR"] = File(filesDir, ".zcode-x").absolutePath
            env["HOME"] = filesDir.absolutePath
            env["TMPDIR"] = cacheDir.absolutePath
            env["PORT"] = "3030"
            val binPath = "$nativeLibDir:$fallbackLibDir:${File(runtimeDir, "zcode/bin").absolutePath}:${File(runtimeDir, "bin").absolutePath}"
            env["PATH"] = "$binPath:${env["PATH"] ?: "/system/bin"}"

            try {
                nodeProcess = pb.start()
                startProcessLogDrain(nodeProcess!!)

                // 3. 轮询健康检测，直到服务端返回 200
                var attempts = 0
                while (isActive && attempts < 30) {
                    delay(500)
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
