# 0.2.2 横屏适配验证

- 主用户包列表仅有 com.foldstandby.clock，没有 com.foldstandby.clock.test。此次未安装测试包。
- 展开横屏采用侧边导航及两栏独立滚动；外屏/窄窗口保留单栏。大字体退回单栏。
- 表盘页左侧增加自适应预览及布局、组件、配色快捷编辑，右侧显示六套预设。
- 设置页与搭配页拆分为两栏；旋转时重新计算布局。
- assembleDebug、lintDebug 通过；已覆盖安装 0.2.2。
- 已在连接的 Fold8 上检查表盘页和设置页。截图：wide-gallery-final.png、wide-settings.png。
- 本次仅调整设置界面，未改动姿态检测与时钟运行逻辑。
