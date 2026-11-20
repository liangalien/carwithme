/*
 * CarWith 协议适配层 —— 派生自 cardisplay2（GNU GPL v3.0），已按本项目需要修改。
 * 许可证全文见模块根 LICENSE，并随 APK 打包在 assets/GPL-3.0.txt。
 * 本模块整体按 GPL-3.0 分发；与宿主（app）之间只通过 :plugin-api 的 AIDL 契约往来。
 */
package com.ikang.vm.carwithme

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.ikang.vm.plugin.api.PluginApi

/**
 * 拼出“打开手机侧小米 CarWith 的 CarLife 入口”的 intent。
 *
 * 为什么要主动拉它：CarWith 只有被拉起后才会在 7240/8240/... 七路上**开始监听**，
 * 我们这边才是那个去 dial 的角色（与 cardisplay `CarWithController.launchCarWith` 一致：
 * 先起帧源，再发这个 intent，等 CarWith 把端口开出来）。
 *
 * ## 为什么只“给 intent”，不自己 startActivity
 * 插件是独立 APK，它的进程**没有可见窗口**。Android 10+ 的后台启动限制会把插件发起的
 * startActivity 静默丢掉（不抛异常、组件也不会起），于是 manifest 上看一切正常、
 * startActivity 也返回了，但手机上 CarWith 根本没启动 —— 只能等七路超时。
 * 真正的启动方必须是当时在前台的**宿主**，所以这里只负责回答“要启动哪个组件”，
 * 由宿主的进程去发（见 ICarWithPlugin.getSourceLaunchIntent）。
 */
internal object CarWithLauncher {

    private const val TAG = "CarWithLauncher"

    /** 小米 CarWith 包名（契约里已定义，宿主做安装预检时用的是同一个值）。 */
    const val PACKAGE = PluginApi.SOURCE_PACKAGE

    /** CarWith 的 CarLife 本机桥入口（ADB/USB 调试通道的那个 Activity）。 */
    private const val ENTRY = "com.miui.carlink.castfwk.usb.CarlifeAdbActivity"

    /** 手机上是否装了 CarWith（没装就没必要启动服务，直接提示用户）。 */
    fun isInstalled(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    /**
     * 拼出 CarWith 入口的 intent；**不在这里 startActivity**（原因见类注释）。
     *
     * 顺便做一次组件可解析校验：入口不存在 / 被禁用 / 未导出时立即在日志里说明白，
     * 而不是把“启动后没反应”留给用户和七路超时去猜。
     *
     * @return null = 手机上没装 CarWith（或 `queries` 没声明导致不可见），或入口组件不可解析
     */
    fun buildEntryIntent(context: Context): Intent? {
        if (!isInstalled(context)) {
            Log.w(TAG, "未安装 CarWith（$PACKAGE），没有可拉起的入口")
            return null
        }
        val intent = Intent()
            .setComponent(ComponentName(PACKAGE, ENTRY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // flags 用 0：这里只想知道“这个显式组件存不存在、能不能被我们看到”，
        // 任何额外过滤（如 MATCH_DEFAULT_ONLY）都可能在显式组件上误报不可解析。
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(intent, 0)
            }
        }.getOrNull()
        if (resolved == null) {
            Log.w(TAG, "入口组件不可解析：$PACKAGE/$ENTRY（不存在、被禁用或未导出？）")
            return null
        }
        return intent
    }
}
