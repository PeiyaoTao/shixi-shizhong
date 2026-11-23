# 时栖时钟 0.5.2 · 名称与应用图标

日期：2026-11-17（America/Los_Angeles）。

## 变更范围

- 应用名「折光时钟」更新为「时栖时钟」；导航简称「时栖」。
- 桌面、关于、音乐访问授权标签、姿态检测通知与安装包名称同步更新。用户可见名称集中在 strings.xml。
- 替换彩色自适应前景、背景、单色主题资源及通知栏符号。Android 26 / 33 的自适应图标入口保持有效。
- 应用包名 `com.foldstandby.clock` 和原有签名保留。versionCode 22，versionName 0.5.2。
- 产品定位改为面向各种机型的桌面时钟；此版本是品牌更新，未修改屏幕方向、姿态检测、权限或机型兼容逻辑。

## 检查结果

| 检查 | 结果 |
|---|---|
| Release 构建 | 通过 |
| lintRelease | 通过：0 错误、24 条提示 |
| Debug AndroidTest 编译 | 通过；同步既有图标检查的应用名预期 |
| APK 名称与版本 | aapt 确认应用标签「时栖时钟」、版本 0.5.2 / 22 |
| 正式包配置 | 未开启 debuggable |
| APK 签名 | apksigner 验证通过，与 0.5.1 的证书 SHA-256 一致 |
| 图标视觉检查 | 检查圆角方形、圆形、连续圆角、单色主题与 24 / 32 / 48px 导出示例 |

本轮完成构建、资源与导出预览检查，未执行设备端 instrumentation；预览由 Android 矢量源路径导出，不是各厂商桌面截图。

日志：`branding-build.log`、`branding-package.log`、`branding-manifest.log`、`branding-signature.log`、`branding-previous-signature.log`。

## 产物

- [正式安装包](时栖时钟-0.5.2-正式版.apk)
- APK SHA-256：`B8A9A76991546A17BDACE55867E5169C2685E42D4CD27CA45613636732E4BFB3`。
- 签名证书 SHA-256：`9960c956df6e9974b66082d38d75ac348aae0579eff5a6669a5bdb5cc32de3fb`。
- [品牌预览与素材](branding-0.5.2/README.md)
- [改动前文件备份](branding-before-0.5.2.zip)
