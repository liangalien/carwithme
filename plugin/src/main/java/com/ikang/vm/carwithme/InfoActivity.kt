package com.ikang.vm.carwithme

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ikang.vm.plugin.api.PluginApi
import java.io.IOException

/**
 * 插件的信息页。**不是产品界面**：本插件没有自己的画面，装好后由宿主在 CarWith 模式下绑定。
 */
class InfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        column.addView(TextView(this).apply {
            text = buildSpannedInfo()
            textSize = 14f
            setTextIsSelectable(true)
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun buildSpannedInfo(): String = buildString {
        appendLine("CarWithMe插件")
        appendLine("本插件无任何界面，建议通过隐藏应用把本插件隐藏起来")
        appendLine("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("提交 ${BuildConfig.GIT_COMMIT}")
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}
