# 许可证

本仓库包含两个版权与授权状态**不同**的模块。

## `plugin/` —— GNU GPL v3.0 only

插件本体（含 CarWith 协议适配层）以 **GPL-3.0-only** 分发，全文见根目录 [LICENSE](LICENSE)，
APK 内另附一份 `assets/GPL-3.0.txt`。

协议适配层派生自 `cardisplay2`（GPL-3.0），已按本项目需要 Kotlin 化并重写；派生与修改说明见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## `plugin-api/` —— 自有版权，不采用 GPL

`plugin-api/` 是宿主 ⇄ 插件之间的**公共契约**：AIDL 接口、Parcelable 实体与回环帧的线格式编解码。
它不含任何 CarWith 协议实现，是本项目作者的自有版权代码，**不随本仓库的 GPL-3.0 授权**，
具体说明见 [plugin-api/NOTICE.md](plugin-api/NOTICE.md)。

这样划分的原因：宿主 VirtualMirroring 是闭源程序，它以同一份契约源码编译自己的 AIDL 客户端。
若把 `plugin-api/` 也置于 GPL 之下，会与"宿主通过进程边界与 GPL 插件聚合、互不传染"的隔离叙述冲突。

## 分发二进制的义务

按 GPL-3.0 第 6 条，分发插件 APK 时必须同时提供其完整对应源码（含修改）。对应源码即本仓库；
`assets/SOURCE-OFFER.txt` 与应用内信息页均写明了获取方式。只发二进制不发源码不符合许可证。
