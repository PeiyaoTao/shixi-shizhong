# 0.2.0 验证记录

- assembleDebug、assembleDebugAndroidTest、lintDebug：通过。
- 真机：Samsung SM-F971U1，Android 17。
- 40 个渲染组合：10 种组件 × 4 种布局，通过。
- 3 项布局/组件持久化检查，通过。
- 4 项专注倒计时状态检查，通过。
- 已观察表盘页、今日预设及双栏全屏，无裁切。
- 天气 HTTPS 实机请求：Read timed out，未验证成功取数。
- 音乐/日历权限未代为开启，真实内容及播放控制尚待用户验证。
- 测试 APK 已移除，最终应用已安装。
- 本次未重新验证自动帐篷触发与外屏恢复；已有逻辑及 0.1.1 外屏识别修复保留。
- 证据截图：gallery-device.png、clock-today-device.png。
