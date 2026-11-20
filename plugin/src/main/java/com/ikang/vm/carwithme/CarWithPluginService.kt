package com.ikang.vm.carwithme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import com.ikang.vm.plugin.api.ICarWithPlugin
import com.ikang.vm.plugin.api.ICarWithPluginCallback
import com.ikang.vm.plugin.api.PluginApi
import com.ikang.vm.plugin.api.PluginConfig

/**
 * 宿主绑定的插件服务：整条「CarWith 七路 → 解密 → 组帧」都在本进程里跑，
 * 帧通过 [FrameServer] 的 loopback socket 交给宿主，控制面走 AIDL。
 *
 * ## 准入
 * 两道：Manifest 里的 normal 权限（防误绑，但不防恶意者自授）+ 调用方包名白名单
 * （[PluginApi.HOST_PACKAGES]）。包名校验在 [onBind] 与每次受理会话前都做一次：
 * 一旦拿到的 IBinder 被转手，第二个门槛仍然拦得住。
 *
 * ## 会话代数（generation）
 * 每次 start/stop 都 +1。旧一代 CarWithSource 的延迟回调（VIDEO 线程上的 onLinked /
 * onDisconnected）因此不会污染新一代的状态，也不需要再维护一个 stopping 标志。
 *
 * ## 为什么要进前台
 * MIUI/HyperOS 会把"没有前台服务、也没有可见界面"的进程整个冷冻（`cgroup.freeze=1`）。
 * 本插件唯一的界面就是打开它自己，所以只要用户停在宿主预览页，插件随时可能被冷冻。
 * 冷冻**不是**"变慢"：所有线程一起停摆 —— VIDEO 读线程不再读 socket（手机侧继续推流，
 * 内核 Recv-Q 涨到 1MB 以上），看门狗与体检线程一个 tick 都不跑，表现就是
 * "预览卡在一帧、点一下才动"、甚至"不手动打开插件就连不上"。
 * 所以会话期间必须 [enterForeground]：前台服务进程不进入可冷冻状态。
 */
class CarWithPluginService : Service() {

    companion object {
        private const val TAG = "CarWithPluginService"

        /** 前台服务通知（会话期间常驻）。 */
        private const val CHANNEL_ID = "carwith_session"
        private const val NOTIF_ID = 0x0C7
    }

    private val callbacks = RemoteCallbackList<ICarWithPluginCallback>()

    /**
     * 回调派发专用线程。
     *
     * [RemoteCallbackList.beginBroadcast] **同一时刻只允许一个在飞的广播**（它内部只有一个
     * 计数器）：第二个 beginBroadcast，无论是同线程重入还是别的线程并发，都会抛
     * `IllegalStateException: beginBroadcast() called while already in a broadcast`。
     * 而本进程有三条线程会 emit：FrameServer 的写线程（每秒 stats）、
     * CarWithSource 的 supervisor/VIDEO 线程（重连日志、状态）、Binder 线程（start/stop 结果）。
     * 实测就是它们在 7240 重连失败时撞在一起，未捕获异常直接把插件进程打死；
     * 进程一死，MIUI 又拦着不让我方重建，`bindService` 从此恒为 false。
     *
     * 因此所有回调统一投递到这条线程串行派发（入队顺序就是 emit 顺序，端口/状态不会乱序）。
     */
    private val emitThread = HandlerThread("CarWithPlugin-emit").apply { start() }
    private val emitHandler = Handler(emitThread.looper)

    private val sessionLock = Any()
    private var frameServer: FrameServer? = null
    private var source: CarWithSource? = null
    private var generation = 0

    @Volatile
    private var pluginState = PluginApi.STATE_IDLE

    /** 是否已在当前会话里进了前台（只有进了才需要 [leaveForeground]）。 */
    private var foreground = false

    @Volatile
    private var canvasW = 0

    @Volatile
    private var canvasH = 0

    private val binder = object : ICarWithPlugin.Stub() {

        override fun getApiVersion(): Int = PluginApi.API_VERSION

        override fun getState(): Int = pluginState

        override fun getCanvasWidth(): Int = canvasW

        override fun getCanvasHeight(): Int = canvasH

        /** 0 = 帧服务未启动（宿主据此判启动失败，而不是去连端口 0）。 */
        override fun getFramePort(): Int = synchronized(sessionLock) { frameServer?.port ?: 0 }

        override fun registerCallback(callback: ICarWithPluginCallback?) {
            if (callback == null) return
            if (!isTrustedCaller()) return
            callbacks.register(callback)
        }

        override fun unregisterCallback(callback: ICarWithPluginCallback?) {
            if (callback == null) return
            runCatching { callbacks.unregister(callback) }
        }

        override fun start(config: PluginConfig?) {
            if (!isTrustedCaller()) {
                emitState(PluginApi.STATE_ERROR, "调用方未授权：${callerDescription()}")
                return
            }
            if (config == null) {
                emitState(PluginApi.STATE_ERROR, "会话参数为空")
                return
            }
            handleStart(config)
        }

        override fun stop() {
            if (!isTrustedCaller()) return
            handleStop()
        }

        /**
         * 只回答“要启动哪个入口”，startActivity 由宿主发。
         *
         * 插件进程没有可见窗口，自己 startActivity 会被后台启动限制静默丢弃
         * （不抛异常、也不启动），表现出“命令成功、手机上却没反应”。
         */
        override fun getSourceLaunchIntent(): Intent? {
            if (!isTrustedCaller()) return null
            val intent = CarWithLauncher.buildEntryIntent(this@CarWithPluginService)
            emitLog(
                if (intent != null) "已把 CarWith 入口交给宿主拉起"
                else "未找到 CarWith 入口组件（${PluginApi.SOURCE_PACKAGE} 未安装？）",
            )
            return intent
        }

        override fun requestKeyframe() {
            if (!isTrustedCaller()) return
            synchronized(sessionLock) { source }?.requestKeyframe()
        }

        override fun injectTouch(action: Int, x: Int, y: Int) {
            if (!isTrustedCaller()) return
            val protocolAction = toProtocolTouch(action) ?: return
            synchronized(sessionLock) { source }?.sendTouch(protocolAction, x, y)
        }

        override fun injectKey(androidKeyCode: Int) {
            if (!isTrustedCaller()) return
            val hardKey = toProtocolKey(androidKeyCode) ?: return
            synchronized(sessionLock) { source }?.sendHardKey(hardKey)
        }

        // P2 才由插件渲染预览；P1 的画面统一由宿主自己的解码器出。
        override fun setPreviewSurface(surface: Surface?, viewWidth: Int, viewHeight: Int) = Unit

        override fun setPreviewVisible(visible: Boolean) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != PluginApi.ACTION_BIND) return null
        if (!isTrustedCaller()) {
            Log.w(TAG, "拒绝绑定：调用方未授权（${callerDescription()}）")
            return null
        }
        return binder
    }

    override fun onDestroy() {
        handleStop()
        callbacks.kill()
        // 派发线程也是本进程的：Service 销毁后不能留一条空转的 Looper。
        emitHandler.removeCallbacksAndMessages(null)
        emitThread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 会话

    private fun handleStart(config: PluginConfig) {
        // 第一件事就是进前台：冷冻一旦发生，后面所有线程（包括收帧）都会一起停摆。
        enterForeground()
        // 先在锁外收掉上一代：它的状态回调由 generation 失效，但线程/socket 必须真的退出。
        // 必须锁外停 —— stop() 会 join accept 线程，而 accept 线程的 onClientAttached 要同一把锁。
        val (staleSource, staleServer) = retireCurrent()
        staleSource?.let { runCatching { it.stop() } }
        staleServer?.stop()
        emitState(PluginApi.STATE_CONNECTING, "拨号中")

        synchronized(sessionLock) {
            val gen = generation
            val server = FrameServer(
                token = config.token,
                onLog = ::emitLog,
                onStats = ::emitStats,
                onClientAttached = {
                    // 宿主刚接上：缓存的关键帧可能已过期，请手机立刻产一帧 IDR。
                    synchronized(sessionLock) { source }?.requestKeyframe()
                },
            )
            if (!server.start()) {
                emitState(PluginApi.STATE_ERROR, "帧通道监听失败")
                return
            }
            frameServer = server

            // 帧在 CarWith 的 VIDEO 读线程上产生：先过代数校验，再入 FrameServer 的有界队列。
            val created = CarWithSource(
                width = config.width,
                height = config.height,
                fps = config.fps,
                onFrame = { frame ->
                    if (gen == generation) server.submit(frame)
                },
                onLog = ::emitLog,
                onLinked = { width, height ->
                    if (gen == generation) {
                        canvasW = width
                        canvasH = height
                        emitCanvas(width, height)
                        emitState(PluginApi.STATE_LINKED, "已连接 ${width}x$height")
                    }
                },
                onDisconnected = {
                    if (gen == generation) {
                        canvasW = 0
                        canvasH = 0
                        emitState(PluginApi.STATE_CONNECTING, "已断开，重连中")
                    }
                },
            )
            source = created
            created.start()
            // 端口必须在 start 之后回传：宿主拿到它才去连帧通道。
            emitFramePort(server.port)
        }
    }

    private fun handleStop() {
        val (staleSource, staleServer) = retireCurrent()
        staleSource?.let { runCatching { it.stop() } }
        staleServer?.stop()
        emitState(PluginApi.STATE_IDLE, "已停止")
        leaveForeground()
    }

    // ---------------------------------------------------------------- 前台服务

    /**
     * 把进程提到前台，免疫 MIUI 的冷冻。
     *
     * 通知走 MIN 优先级：它只是维持进程优先级的手段，不需要响铃/上浮。
     * 失败必须记清楚：Android 12+ 对"后台起前台服务"有硬限制，若被拒，进程就仍然会被
     * 冷冻 —— 那时候日志里必须能直接看到原因，而不是继续默默卡首帧。
     */
    private fun enterForeground() {
        if (foreground) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CarWith 投屏", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    enableVibration(false)
                },
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("CarWith 投屏运行中")
            .setContentText("正在把手机画面推给仪表")
            .setOngoing(true)
            .build()
        foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure {
            Log.e(TAG, "进入前台失败（进程仍可能被冷冻）：${it.javaClass.simpleName}(${it.message})", it)
        }.isSuccess
        Log.i(TAG, "进入前台：$foreground")
    }

    private fun leaveForeground() {
        if (!foreground) return
        foreground = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        Log.i(TAG, "退出前台")
    }

    /**
     * 让当前代数失效，并摘下 source/frameServer，**不负责停它们**。
     *
     * 调用方必须在锁外调 `stop()`：那是 join 线程的阻塞操作，而被 join 的 accept 线程
     * 可能正等着这把锁（onClientAttached 要读 source），持锁 join 会白等一个超时，
     * 连带把 injectTouch / getFramePort 这些低频调用一起卡住。
     */
    private fun retireCurrent(): Pair<CarWithSource?, FrameServer?> = synchronized(sessionLock) {
        generation++
        val stale = source to frameServer
        source = null
        frameServer = null
        canvasW = 0
        canvasH = 0
        stale
    }

    // ---------------------------------------------------------------- 协议映射

    /**
     * 契约的中立触摸编号 → CarWith 协议值。
     *
     * 陷阱：两者顺序**不同**（契约 DOWN/MOVE/UP = 0/1/2，CarWith 是 DOWN/UP/MOVE = 0/1/2）。
     * 这张表只允许存在于此：宿主任何位置都不出现协议值。
     */
    private fun toProtocolTouch(action: Int): Int? = when (action) {
        PluginApi.TOUCH_DOWN -> CarLifeProtocol.TOUCH_DOWN
        PluginApi.TOUCH_MOVE -> CarLifeProtocol.TOUCH_MOVE
        PluginApi.TOUCH_UP -> CarLifeProtocol.TOUCH_UP
        else -> null
    }

    /** Android keycode → CarWith 硬键码（BACK=14 与 KEYCODE_BACK=4 不是一个值）。 */
    private fun toProtocolKey(androidKeyCode: Int): Int? = when (androidKeyCode) {
        KeyEvent.KEYCODE_BACK -> CarLifeProtocol.HARD_KEY_BACK
        else -> null
    }

    // ---------------------------------------------------------------- 准入

    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull() ?: return false
        return packages.any { it in PluginApi.HOST_PACKAGES }
    }

    private fun callerDescription(): String {
        val uid = Binder.getCallingUid()
        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
        return "uid=$uid pkg=${packages?.joinToString(",") ?: "?"}"
    }

    // ---------------------------------------------------------------- 回调

    private fun emitState(state: Int, detail: String) {
        pluginState = state
        broadcast { it.onState(state, detail) }
    }

    private fun emitCanvas(width: Int, height: Int) {
        broadcast { it.onCanvas(width, height) }
    }

    private fun emitFramePort(port: Int) {
        broadcast { it.onFramePort(port) }
    }

    private fun emitLog(message: String) {
        Log.i(TAG, message)
        broadcast { it.onLog(message) }
    }

    private fun emitStats(text: String) {
        broadcast { it.onStats(text) }
    }

    /**
     * 投递到 [emitThread] 串行派发；调用方在视频/帧/supervisor 线程上，绝不阻塞。
     *
     * 不能是 inline：block 要跨线程交给 [emitHandler]，inline 参数没法这样逃逸。
     */
    private fun broadcast(block: (ICarWithPluginCallback) -> Unit) {
        emitHandler.post { dispatchCallbacks(block) }
    }

    /**
     * 真正做广播（只在 [emitThread] 上执行）。
     *
     * 单个回调死亡（宿主进程重启）不能影响其它订阅者，更不能打断会话线程。
     */
    private fun dispatchCallbacks(block: (ICarWithPluginCallback) -> Unit) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                runCatching { block(callbacks.getBroadcastItem(index)) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }
}
