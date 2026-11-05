package com.mcarplay.app

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mcarplay.app.settings.DisplayPanelFragment
import com.mcarplay.app.settings.SettingsToastHost
import com.mcarplay.app.ui.EdgeToEdge

/**
 * 车机显示设置页：承载 [DisplayPanelFragment]（流画布缩放、帧率、息屏、自动连接、兼容兜底、HID 日志等）。
 * 随系统浅/深色切换，颜色全部来自 `ui_*` 令牌。
 */
class SettingsContentActivity :
    AppCompatActivity(),
    SettingsToastHost {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_content)
        EdgeToEdge.padContent(this)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.contentTitle).setText(R.string.lay_display)

        if (supportFragmentManager.findFragmentById(R.id.settingsContainer) == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, DisplayPanelFragment())
                .commit()
        }
    }

    override fun showToast(text: String) {
        android.widget.Toast
            .makeText(this, text, android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}
