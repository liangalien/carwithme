# CarWithMe

小米 **CarWith**（手机 → 车机投屏，手机侧应用包名 `com.miui.carlink`）的**免特权**桥接插件。
它以独立 APK 的形式安装，被宿主应用 [VirtualMirroring](https://github.com/liangalien/VirtualMirroring)
在「CarWith 免特权模式」下绑定后，负责把手机侧的画面、触摸与硬键接出来 —— **不需要 Root、Shizuku 或 ADB**。

> 本仓库是直接从 VirtualMirroring 中拆出来的独立开源项目：插件本体（`plugin/`）按 **GPL-3.0-only** 分发，
> 契约模块（`plugin-api/`）为自有版权、不随 GPL 授权。详见 [LICENSING.md](LICENSING.md)。

---

## 这是什么

- **一个 APK**：包名 `com.ikang.vm.carwithme`，安装后桌面有入口（信息页），展示版本、契约版本与 GPL 源码取得声明。
- **协议适配层**：完成 CarWith 的七路拨号、握手、AES 解密、protobuf 编解码、视频组帧、触摸/硬键注入、
  以及把 CarWith 入口 Intent 交给宿主拉起。这些实现派生自 `cardisplay2` 项目（GPL-3.0），已按本项目的需要 Kotlin 化与重写。
- **没有自己的界面**：画面不在插件里渲染，帧通过本机回环 socket 交给宿主，控制面走 Binder AIDL。

## 它不做什么

- 不打包进宿主 APK，不共享实现代码，不与宿主链接 —— 两者是不同的应用、不同的进程、不同的签名。
- 不提供通用投屏能力：只服务 CarWith 这一条链路，且只接受白名单内的宿主包名绑定。
- 不收集、不上传任何数据；全部流量都在本机回环（`127.0.0.1`）上。

## 工作原理

```
宿主 App (com.ikang.vm.next)                    插件 APK (com.ikang.vm.carwithme)
        │  bindService(ACTION_BIND)                        │
        │ ─────────────── AIDL 控制面 ────────────────────▶ │  CarWith 七路拨号 / 握手
        │                                                   │  AES + protobuf 解码
        │ ◀──── getFramePort() / 状态回调 ───────────────── │  Annex-B 组帧
        │  connect(127.0.0.1:port, token 鉴权)              │
        │ ◀──────────── 回环 socket 视频面 ──────────────── │
        │  injectTouch / injectKey ───────────────────────▶ │  → 手机侧 CarWith
```

1. 宿主绑定插件 Service（权限 `com.ikang.vm.permission.CARWITH_PLUGIN`，`normal` 级）。
2. 插件侧用 `Binder.getCallingUid()` 反查调用方包名，必须命中 `PluginApi.HOST_PACKAGES`；
   鉴权不靠签名，所以**用户自编译的插件（签名不同）也能接入**。
3. 会话期间插件进入前台服务（`specialUse`），避免 MIUI/HyperOS 把无可见界面的进程整体冷冻。
4. 视频帧的线格式：`pts(8B) | flags(1B) | size(4B) | Annex-B`，端口只监听回环并要求一次性令牌。

## 兼容性

| 项 | 值 |
|---|---|
| 插件包名 | `com.ikang.vm.carwithme` |
| 契约版本 | `PluginApi.API_VERSION = 2`（与宿主不等即拒，不做兼容降级） |
| 宿主包名白名单 | `com.ikang.vm.next`、`com.ikang.vm.next.debug` |
| minSdk / targetSdk | 29 / 36 |
| 手机侧依赖 | 已安装并登录小米 CarWith（`com.miui.carlink`） |
| 系统 | MIUI / HyperOS 上实测；其它 ROM 未验证 |

插件 `versionCode` 用于覆盖安装与「装的是旧插件」提示；**契约 ABI** 只看 `API_VERSION`。
改动 AIDL 或 `PluginConfig` 字段顺序时，必须同时 `+1 API_VERSION`，并在宿主仓库同步同一份 `plugin-api/`。

## 构建

要求：JDK 21、Android SDK（compileSdk 37）、Gradle wrapper 已随仓库提供（Gradle 9.5 / AGP 9.3.1）。

```bash
# 调试包 + 单元测试
./gradlew :plugin-api:testDebugUnitTest :plugin:testDebugUnitTest :plugin:assembleDebug

# 正式包
./gradlew :plugin:assembleRelease
```

产物：

```
plugin/build/outputs/apk/debug/plugin-debug.apk
plugin/build/outputs/apk/release/plugin-release.apk
```

Windows 下把 ./gradlew 换成 .\gradlew.bat。$old SDK 路径写在 local.properties（sdk.dir=...，该文件不入库）。

### 签名

插件与宿主是**两个独立发行物**，请使用你自己的密钥。密钥从 `local.properties` 读取，
或使用同名环境变量（方便 CI）：

| local.properties | 环境变量 |
|---|---|
| `plugin.storeFile`（相对仓库根目录） | `PLUGIN_STORE_FILE` |
| `plugin.storePassword` | `PLUGIN_STORE_PASSWORD` |
| `plugin.keyAlias`（默认 `plugin`） | `PLUGIN_KEY_ALIAS` |
| `plugin.keyPassword` | `PLUGIN_KEY_PASSWORD` |

未配置时 `assembleRelease` 会**回退到 debug 签名**并打印警告 —— 仅供本地验证，不要这样分发。

## 模块结构

```
plugin/       插件本体（GPL-3.0-only）
  ├─ CarWithSource.kt        七路拨号、握手、解密、组帧、注入（核心）
  ├─ CarWithSession.kt       会话生命周期与状态机
  ├─ CarWithPluginService.kt 宿主绑定的 Service：AIDL 实现、准入、前台服务
  ├─ FrameServer.kt          回环帧服务（令牌鉴权 + Annex-B 下发）
  ├─ CarLifeProtocol.kt / CarLifeProto.kt / CarLifeCrypto.kt  协议与加解密
  ├─ CarWithVideoParser.kt   视频负载解析（SPS/PPS/IDR）
  ├─ CarWithLauncher.kt      定位并构造 CarWith 入口 Intent（由宿主 startActivity）
  ├─ InfoActivity.kt         信息页：版本 / 契约版本 / 许可证 / 源码声明
  └─ WakeActivity.kt         透明"叫醒"页：绕过 MIUI 自启动拦截后重试绑定
plugin-api/   宿主 ⇄ 插件契约（自有版权，非 GPL）
  ├─ ICarWithPlugin.aidl / ICarWithPluginCallback.aidl / PluginConfig.aidl
  ├─ PluginApi.kt            版本号、状态码、中立触摸/按键编号、宿主白名单
  ├─ PluginConfig.kt         会话参数（Parcelable，字段顺序即线格式）
  ├─ PluginFrameCodec.kt     视频面线格式编解码
  └─ PluginChannelAuth.kt    回环端口令牌握手
```

## 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` | 任何 `AF_INET` socket（含 `127.0.0.1`）都要求该权限；CarWith 七路与帧服务都走回环 |
| `FOREGROUND_SERVICE` / `_SPECIAL_USE` | 会话期间保持前台，避免被 MIUI/HyperOS 冷冻导致"预览卡一帧" |
| `POST_NOTIFICATIONS` | 前台服务通知（被拒不影响功能） |
| `queries` → `com.miui.carlink` | Android 11+ 包可见性：查询/拉起手机侧 CarWith |

## 许可证

- `plugin/`：**GNU GPL v3.0 only**（根目录 [LICENSE](LICENSE)，APK 内 `assets/GPL-3.0.txt`）。
  派生自 `cardisplay2`（GPL-3.0），修改说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- `plugin-api/`：自有版权，**不随 GPL 授权**，见 [plugin-api/NOTICE.md](plugin-api/NOTICE.md)。

分发本插件的二进制时，必须同时提供对应完整源码（GPL-3.0 第 6 条）：源码即本仓库，
APK 内 `assets/SOURCE-OFFER.txt` 与应用信息页都写明了获取方式。

## 免责声明

本项目与小米公司无关，未获其授权或背书。"CarWith"、"MIUI"、"HyperOS" 等名称归各自权利人所有。
协议适配层的实现来自对公开可得行为的复现，请自行评估合规风险后再使用或分发。
