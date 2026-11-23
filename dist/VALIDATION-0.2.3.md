# 0.2.3 通知显示

- 开启姿态检测不再自动申请 POST_NOTIFICATIONS 权限。
- 设置页增加「通知显示」，跳转到系统应用通知设置。
- 已在连接的 Fold8 主用户下关闭 Fold 的通知权限（granted=false）。
- 前台服务仍保留 startForeground 通知对象，符合 Android 服务运行要求；通知权限关闭时由系统隐藏通知栏条目，系统运行中应用列表仍可显示服务。
- assembleDebug、lintDebug 通过，已安装更新。
- 待用户解锁开启检测后核实服务运行。
- Android 官方依据：https://developer.android.com/develop/ui/compose/notifications/notification-permission
