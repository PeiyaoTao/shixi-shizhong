# Fold 0.3.5 输入表单视觉与交互验证

日期：2026-11-14（America/Los_Angeles）。设备：SM-F971U1 / Android 17，三星键盘。

## 变更

- 保留程序内添加与管理待办的设计。
- 待办添加／修改、页面／场景命名、天气城市搜索与选择、经纬度设置、世界时区搜索共用深色圆角表单；统一标题、辅助文字、字段标签、薄荷绿主按钮、焦点边框及按压反馈。
- 字段错误采用行内文字与红色边框；空白待办／名称／城市不会提交，经纬度分别检查范围和非有限数值。
- 搜索、完成、下一项与键盘动作对应；禁止横屏输入法的全屏提取界面。弹窗按可用宽度收窄，表单内容可滚动，底部按钮固定，旋转与键盘变化后自动显示当前焦点字段。
- 旋转时保留已输入的内容，关闭活动时清理弹窗。HEX 输入沿用内嵌形式，统一焦点、光标和横屏键盘样式。
- 世界时区搜索改为忽略大小写的子串匹配，支持以 `Tokyo` 查到 `Asia/Tokyo`。

## 验证结果

- `assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过；lint 为 0 个错误、16 个警告，数量与 0.3.4 一致。
- `suite inputs`：42 项真机交互检查通过。包含真实键盘弹出、外屏横竖屏待办输入与旋转保留内容、空白校验、键盘完成只新增一次、修改／取消、Seattle 的实际 HTTPS 城市搜索与结果选择、经纬度范围与负数保存、经纬度横屏焦点及按钮可见、时区过滤与选择、130% 界面字号的名称输入。
- `suite scene`：10036 项断言、244 次离屏渲染通过，覆盖现有组件、场景与各尺寸工作台布局。
- 已检查本目录 `qa-0.3.5` 下 8 张真机截图。横屏键盘保留应用表单；多字段表单可滚动到当前字段，操作按钮保持可见。
- 使用隔离的 `ui_checks` 偏好设置执行输入检查，结束后恢复隔离配置。用户 `clock.xml` 在安装与检查前后的 SHA-256 均为 `15148be825d2e9df643e768cb57c04baead68e704c5f7f4a35581e5b7884cdaf`。
- 原开发签名保持一致，已覆盖安装 versionCode 12 / versionName 0.3.5。

## 截图

- `qa-0.3.5/todo-portrait-keyboard.png`：待办竖屏输入。
- `qa-0.3.5/todo-landscape-keyboard.png`：待办横屏输入。
- `qa-0.3.5/city-portrait-keyboard.png`：城市搜索。
- `qa-0.3.5/city-results.png`：城市选择结果。
- `qa-0.3.5/coordinates-portrait-keyboard.png`：经纬度行内校验。
- `qa-0.3.5/coordinates-landscape-keyboard.png`：横屏下当前坐标字段、错误与按钮。
- `qa-0.3.5/timezone-search-keyboard.png`：时区过滤。
- `qa-0.3.5/rename-large-text-keyboard.png`：130% 界面字号的名称输入。

实机交互在外屏完成；内屏尺寸由既有离屏布局检查覆盖，未逐一展开设备验证所有输入窗口。三星键盘以外的输入法未逐一验证。

安装包：`FoldStandby-0.3.5-debug.apk`。

SHA-256：`B030AF7B1E36A30A3E57E15F6673A6E18BA153683A001AE18BD1A84D91114289`。
