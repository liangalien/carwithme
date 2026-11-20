package com.ikang.vm.carwithme

import android.app.Activity
import android.os.Bundle

/**
 * 只负责「把插件进程叫起来」的空 Activity：透明、无内容、进到 onCreate 就 finish。
 *
 * ## 为什么需要它
 * MIUI/HyperOS 的「自启动」会在 AMS 层拒掉**其它应用**对本插件 Service 的绑定：
 * logcat 是 `WakePathChecker: MIUILOG-AutoStart, Service/Provider/Broadcast Reject`，
 * 而 `bindService` 只返回 false、不抛异常。用户手动启动插件后一切正常 —— 说明被拦的只是
 * 「被别的应用拉活」这一条，而用户可见的前台应用发起的 Activity 启动通常放行。
 *
 * 所以宿主绑定失败时会先拉起本 Activity 把进程带起来，再重试绑定：
 * 这是纯普通应用手段（没有 Shell / Root / Shizuku），代价是极短的一次窗口事务。
 *
 * 它**不可见**：透明主题 + 立即 finish + 不进最近任务（见 Manifest）。
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
