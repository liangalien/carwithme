// 顶层构建文件：只有插件（application）与契约（library）两个模块。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
