package com.yinpage.link.module

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * ============================================================================
 *  模块配置界面
 * ============================================================================
 *  故意做得极简（纯 Android View，不引入 Compose）：
 *  模块 APK 本身只是个"配置壳"，真正的逻辑全部运行在被 Hook 的系统进程里。
 *
 *  配置写入 SharedPreferences，被 Hook 进程通过 LSPosed 的
 *  `getRemotePreferences` 读取（见 [ModuleConfigStore]）。
 * ============================================================================
 */
class ModuleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleConfigStore.localPrefs(this)
        ModuleConfigStore.reload()
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title(getString(R.string.status_title)))
        root.addView(body(getString(R.string.status_active)))
        root.addView(body(getString(R.string.status_hint)))

        root.addView(title(getString(R.string.config_title)))

        // ---- 目标耳机地址 ----
        root.addView(body(getString(R.string.config_target)))
        val addressInput = EditText(this).apply {
            hint = getString(R.string.config_target_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(ModuleConfigStore.current().targetAddress)
        }
        root.addView(addressInput)
        root.addView(small(getString(R.string.config_target_desc)))

        // ---- 开关项 ----
        val ancSwitch = switchRow(root, getString(R.string.config_anc), getString(R.string.config_anc_desc), ModuleConfigStore.current().hookAnc)
        val islandSwitch = switchRow(root, getString(R.string.config_island), getString(R.string.config_island_desc), ModuleConfigStore.current().focusIsland)
        val nativeSwitch = switchRow(root, getString(R.string.config_native_page), getString(R.string.config_native_page_desc), ModuleConfigStore.current().useNativeHeadsetPage)

        // ---- 日志级别 ----
        root.addView(body(getString(R.string.config_log_level) + "：${logLevelText()}"))
        val logButton = Button(this).apply {
            text = "切换日志级别"
            setOnClickListener {
                val next = (ModuleConfigStore.current().logLevel + 1) % 3
                ModuleConfigStore.update { it.copy(logLevel = next) }
                Toast.makeText(this@ModuleActivity, "日志级别：${logLevelText()}", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }
        root.addView(logButton)

        // ---- 保存 ----
        val save = Button(this).apply {
            text = getString(R.string.about_apply)
            setOnClickListener {
                ModuleConfigStore.update {
                    it.copy(
                        targetAddress = addressInput.text.toString().trim().uppercase(),
                        hookAnc = ancSwitch.isChecked,
                        focusIsland = islandSwitch.isChecked,
                        useNativeHeadsetPage = nativeSwitch.isChecked,
                    )
                }
                Toast.makeText(this@ModuleActivity, getString(R.string.about_saved), Toast.LENGTH_LONG).show()
            }
        }
        root.addView(save)

        root.addView(title(getString(R.string.about_title)))
        root.addView(body(getString(R.string.about_body)))

        return ScrollView(this).apply { addView(root) }
    }

    private fun logLevelText(): String = when (ModuleConfigStore.current().logLevel) {
        ModuleConfig.LOG_OFF -> getString(R.string.log_off)
        ModuleConfig.LOG_DEBUG -> getString(R.string.log_debug)
        else -> getString(R.string.log_basic)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setPadding(0, 24, 0, 8)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 4, 0, 4)
    }

    private fun small(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, 2, 0, 8)
    }

    private fun switchRow(
        parent: LinearLayout,
        title: String,
        desc: String,
        checked: Boolean,
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val sw = Switch(this).apply {
            text = title
            isChecked = checked
        }
        row.addView(sw)
        row.addView(small(desc))
        parent.addView(row)
        return sw
    }
}
