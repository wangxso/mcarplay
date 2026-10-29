package com.mcarplay.app.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.mcarplay.app.BuildConfig
import com.mcarplay.app.MainActivity
import com.mcarplay.app.SessionController
import com.mcarplay.app.legal.DisclaimerPrefs
import com.mcarplay.app.usb.UsbMediaEndpoint

/** 只供 adb 显式调用的 USB 媒体台架，DUMP 权限阻止普通应用调用；不自动建立 root gadget。 */
class UsbMediaBenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || !DisclaimerPrefs.hasAcceptedCurrent(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (intent.getBooleanExtra("stop", false)) {
            SessionController.disconnect(this)
            finish()
            return
        }
        val endpoint =
            runCatching {
                UsbMediaEndpoint(
                    interfaceName = intent.getStringExtra("interface").orEmpty(),
                    address = intent.getStringExtra("host").orEmpty(),
                    port = intent.getIntExtra("port", 17000),
                    scopeId = intent.getIntExtra("scope", 0).takeIf { it != 0 },
                )
            }.getOrElse {
                Toast.makeText(this, "USB 台架参数无效：${it.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        // 仍在前台时启动 connectedDevice FGS，后续协议与媒体都在后台线程执行。
        runCatching { SessionController.startUsbMediaBench(this, endpoint) }.onFailure {
            SessionController.appendLog("USB-BENCH: 启动失败：${it.message}")
            Toast.makeText(this, "USB 台架启动失败：${it.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
