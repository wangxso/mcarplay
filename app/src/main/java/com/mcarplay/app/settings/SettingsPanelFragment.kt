package com.mcarplay.app.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

/** 设置面板宿主：页内轻提示（手机=系统 Toast）。 */
interface SettingsToastHost {
    fun showToast(text: String)
}

/** 设置内容面板基类：宿主轻提示。 */
abstract class SettingsPanelFragment : Fragment() {
    protected fun hostToast(text: String) {
        val host = activity
        if (host is SettingsToastHost) {
            host.showToast(text)
        } else {
            android.widget.Toast
                .makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        onViewCreated(view)
    }

    /** onViewCreated 简化版（本页无状态恢复需求）。 */
    protected abstract fun onViewCreated(view: View)
}
