package com.mcarplay.app.bridge

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mcarplay.app.MainActivity

/** remote-debug 主界面卡片打开的一键 USB NCM 入口。 */
class UsbWiredConnectActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var stop: Button
    private var returnedToMain = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "USB 有线连接（实验）"
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad)
            }
        root.addView(
            TextView(this).apply {
                text = "USB 有线媒体"
                setTextColor(Color.WHITE)
                textSize = 25f
                gravity = Gravity.CENTER
            },
            matchWrap(),
        )
        root.addView(
            TextView(this).apply {
                text = "保持手机与 Mac 的 USB 连接。MacPlay 中请选择“有线CarPlay”并先点击“启动接收”。"
                setTextColor(0xFFB8C2C8.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
            },
            matchWrap(top = 12),
        )
        status =
            TextView(this).apply {
                setTextColor(0xFF3DD6C4.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
            }
        detail =
            TextView(this).apply {
                setTextColor(0xFFE0E0E5.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
            }
        root.addView(status, matchWrap(top = 28))
        root.addView(detail, matchWrap(top = 10))
        root.addView(
            Button(this).apply {
                text = "重试"
                setOnClickListener {
                    returnedToMain = false
                    UsbNcmAutoConnector.connect(this@UsbWiredConnectActivity)
                }
            },
            matchWrap(top = 24),
        )
        stop =
            Button(this).apply {
                text = "停止并恢复 USB"
                setOnClickListener {
                    UsbNcmAutoConnector.stop(this@UsbWiredConnectActivity)
                }
            }
        root.addView(stop, matchWrap(top = 8))
        setContentView(ScrollView(this).apply { addView(root) })

        UsbNcmAutoConnector.observe { snapshot ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text =
                    when (snapshot.phase) {
                        UsbNcmAutoConnector.Phase.IDLE -> "等待连接"
                        UsbNcmAutoConnector.Phase.REQUESTING_ROOT -> "正在请求 root"
                        UsbNcmAutoConnector.Phase.PREPARING_USB -> "正在建立 USB NCM"
                        UsbNcmAutoConnector.Phase.FINDING_PEER -> "正在查找 MacPlay"
                        UsbNcmAutoConnector.Phase.STARTING_AIRPLAY -> "正在连接 AirPlay"
                        UsbNcmAutoConnector.Phase.CONNECTED -> "USB 媒体已启动"
                        UsbNcmAutoConnector.Phase.STOPPING -> "正在断开"
                        UsbNcmAutoConnector.Phase.FAILED -> "连接失败"
                    }
                detail.text = snapshot.detail
                stop.visibility =
                    if (snapshot.phase == UsbNcmAutoConnector.Phase.IDLE) View.GONE else View.VISIBLE
                if (snapshot.phase == UsbNcmAutoConnector.Phase.CONNECTED && !returnedToMain) {
                    returnedToMain = true
                    Toast.makeText(this, "USB 媒体已启动，正在返回主界面", Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                }
            }
        }
        if (UsbNcmAutoConnector.snapshotPhase() == UsbNcmAutoConnector.Phase.IDLE) {
            UsbNcmAutoConnector.connect(this)
        }
    }

    override fun onDestroy() {
        UsbNcmAutoConnector.observe(null)
        super.onDestroy()
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (top * resources.displayMetrics.density).toInt()
        }
}
