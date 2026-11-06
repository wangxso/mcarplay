package com.mcarplay.app.diagnostics.btsnoop

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.mcarplay.app.R
import com.mcarplay.app.ui.EdgeToEdge
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.DebugFileLog

/**
 * 蓝牙抓包分析页：引导用户打开 HCI 日志、提交错误报告，再把错误报告分享或选择给本页，
 * 在本机解析出脱敏结论（[BtSnoopReport]）并写进诊断日志。普通应用无法自行开启或读取 HCI 日志。
 */
class BtSnoopActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var copy: Button
    private var busy = false

    @Volatile
    private var cancelled = false

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) analyze(listOf(uri))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_btsnoop)
        EdgeToEdge.padContent(this)
        status = findViewById(R.id.btsnoopStatus)
        result = findViewById(R.id.btsnoopResult)
        copy = findViewById(R.id.btsnoopCopy)
        findViewById<View>(R.id.btsnoopBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btsnoopDeveloper).setOnClickListener { openDeveloperOptions() }
        findViewById<Button>(R.id.btsnoopPick).setOnClickListener {
            if (busy) {
                toast(getString(R.string.btsnoop_busy))
            } else {
                runCatching { picker.launch(arrayOf("application/zip", "application/vnd.android.bugreport", "application/octet-stream")) }
            }
        }
        copy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.btsnoop_clip_label), result.text))
            toast(getString(R.string.btsnoop_copied))
        }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    /** 系统错误报告的分享是 ACTION_SEND_MULTIPLE（zip + 截图）；截图会被识别为不支持并跳过。 */
    private fun handleShare(intent: Intent?) {
        val uris =
            when (intent?.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> emptyList()
            }
        if (uris.isNotEmpty()) analyze(uris)
    }

    private fun openDeveloperOptions() {
        val enabled = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
        if (!enabled) {
            status.setText(R.string.btsnoop_developer_disabled)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            status.setText(R.string.btsnoop_developer_disabled)
        }
    }

    private fun analyze(uris: List<Uri>) {
        if (busy) {
            toast(getString(R.string.btsnoop_busy))
            return
        }
        busy = true
        status.setText(R.string.btsnoop_running)
        result.visibility = View.GONE
        copy.visibility = View.GONE
        val resolver = contentResolver
        val appContext = applicationContext
        Bg.run("btsnoop") {
            val files = ArrayList<BtSnoopFileAnalysis>()
            var noCapture = false
            var failure: String? = null
            for (uri in uris) {
                if (cancelled) break
                try {
                    val outcome = resolver.openInputStream(uri)?.use { BtSnoopImporter.analyze(it) { cancelled } }
                    when (outcome) {
                        is BtSnoopImporter.Outcome.Analyzed -> files += outcome.files
                        BtSnoopImporter.Outcome.NoCapture -> noCapture = true
                        else -> Unit
                    }
                } catch (error: Exception) {
                    // 只记异常类型：消息里可能有文件路径。
                    failure = error.javaClass.simpleName
                }
            }
            val lines = files.flatMap { BtSnoopReport.format(it) }
            val message =
                when {
                    files.isNotEmpty() -> appContext.getString(R.string.btsnoop_done, files.size)
                    noCapture -> appContext.getString(R.string.btsnoop_no_capture)
                    failure != null -> appContext.getString(R.string.btsnoop_failed, failure)
                    else -> appContext.getString(R.string.btsnoop_unsupported)
                }
            if (!cancelled) {
                DebugFileLog.append(appContext, "BTSNOOP: 导入分析 文件=${uris.size} 抓包=${files.size}" + if (noCapture) " 无抓包=是" else "")
                lines.forEach { DebugFileLog.append(appContext, "BTSNOOP: $it") }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                status.text = message
                if (lines.isNotEmpty()) {
                    result.text = lines.joinToString("\n")
                    result.visibility = View.VISIBLE
                    copy.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
