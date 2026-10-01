package dev.zcode.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast

class AndroidBridge(private val context: Context, private val onRestartRequested: () -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun restartServer() {
        mainHandler.post {
            onRestartRequested()
        }
    }

    @JavascriptInterface
    fun getPlatform(): String {
        return "android"
    }

    @JavascriptInterface
    fun getVersion(): String {
        return "3.14.3"
    }
}
