# 第三方来源与修改说明

## CarWith 协议适配层 —— 派生自 cardisplay2（GPL-3.0）

`plugin/` 中的 CarWith 协议适配层（七路拨号、握手、AES 解密、protobuf 编解码、视频组帧、
触摸/硬键注入、拉起 CarWith 入口）派生自 **cardisplay2** 项目，该项目以
**GNU General Public License v3.0** 发布。

已按本项目需要做如下修改：

- 由 Java/Rust 混合实现改为纯 Kotlin（去掉 Rust 侧实现）；
- 重连、降频、看门狗等运行策略改由插件自身 supervisor 承担，不再依赖外部进程监督；
- 视频面改为"Annex-B + 回环 socket 交帧"，由宿主自行解码渲染；
- 增加契约准入（调用方包名校验）、前台服务保活与信息页。

每个派生文件的文件头都带有 GPL-3.0 与"已修改"的声明（GPL-3.0 第 5(a) 条要求）。

## 无其它第三方组件

`plugin/` 与 `plugin-api/` 不依赖任何第三方运行时库（仅 JUnit 用于单元测试）。
AndroidX / AGP / Gradle 以构建期工具链身份使用，按其各自许可证分发。

## 与宿主 VirtualMirroring 的关系

宿主 [VirtualMirroring](https://github.com/liangalien/VirtualMirroring)（`com.ikang.vm.next`）
是**独立程序**，本插件是另一个应用、另一份 APK、另一把签名密钥。两者：

- 不共享实现代码：宿主只依赖同一份 `plugin-api/` 契约源码；
- 不静态/动态链接：只有 Binder AIDL、Parcelable 与回环 socket 字节流；
- 不捆绑分发：宿主 APK 里不含本插件的任何 GPL 代码。

因此二者属于 GPL-3.0 第 5 条最后一段所称的 **aggregate**，宿主不受 GPL-3.0 传染。
