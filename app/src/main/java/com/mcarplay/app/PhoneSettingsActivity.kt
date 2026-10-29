package com.mcarplay.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mcarplay.app.launcher.settings.SettingsMenus
import com.mcarplay.app.launcher.settings.SettingsToastHost

/**
 * 手机侧「车机设置」二级菜单页（M3 暗色）：
 * 列出五个设置入口，点击进入内容 Fragment——与车机 SettingsActivity 双栏共用同一套面板。
 */
class PhoneSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_settings)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.menuList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = MenuAdapter()
    }

    private inner class MenuAdapter : RecyclerView.Adapter<MenuHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MenuHolder {
            val view = layoutInflater.inflate(R.layout.item_phone_settings_menu, parent, false)
            return MenuHolder(view)
        }

        override fun getItemCount() = SettingsMenus.ALL.size

        override fun onBindViewHolder(
            holder: MenuHolder,
            position: Int,
        ) {
            val item = SettingsMenus.ALL[position]
            holder.icon.setImageResource(item.iconRes)
            holder.title.setText(item.title)
            holder.desc.setText(item.desc)
            holder.itemView.setOnClickListener {
                SettingsContentActivity.start(this@PhoneSettingsActivity, item)
            }
        }
    }

    private class MenuHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.menuIcon)
        val title: TextView = view.findViewById(R.id.menuTitle)
        val desc: TextView = view.findViewById(R.id.menuDesc)
    }
}

/**
 * 手机侧设置内容宿主：承载与车机双栏共用的内容 Fragment。
 */
class SettingsContentActivity :
    AppCompatActivity(),
    SettingsToastHost {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val menuId = intent.getIntExtra(EXTRA_MENU, SettingsMenus.DESKTOP)
        setContentView(R.layout.activity_settings_content)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.contentTitle).setText(SettingsMenus.titleOf(menuId))

        if (supportFragmentManager.findFragmentById(R.id.settingsContainer) == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, SettingsMenus.createFragment(menuId))
                .commit()
        }
    }

    override fun showToast(text: String) {
        android.widget.Toast
            .makeText(this, text, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    companion object {
        private const val EXTRA_MENU = "menu"

        fun start(
            context: Context,
            item: SettingsMenus.Item,
        ) {
            context.startActivity(
                Intent(context, SettingsContentActivity::class.java)
                    .putExtra(EXTRA_MENU, item.id),
            )
        }
    }
}
