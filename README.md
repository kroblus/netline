# NETLINE

个人自用的原生 Android 网络流量记录器。Kotlin / Jetpack Compose / Room / DataStore，Android 10+，Target SDK 36。

1.2 新增：40dp 悬浮球（48dp 触控范围）、应用内中文/English 切换、历史记录长按删除。

## 使用

- **MONITOR**：开始、停止真实设备流量记录，查看最近 30 分钟曲线。
- **HISTORY**：选择任意单个会话并生成报告；中断的会话保留已提交数据。
- **HISTORY → 长按记录 → 删除记录**：确认后删除该会话、采样和分钟汇总；取消不删除，正在记录的会话需先停止，已保存的报告图片不受影响。
- **SETTINGS → 语言 / LANGUAGE**：选择中文或 English，立即更新界面、悬浮菜单及通知；新报告使用所选语言，选择保存在 DataStore，重开应用后保留。首次使用跟随系统的中文或回退到英文。
- **SETTINGS → Enable Floating Ball**：先授权悬浮窗。单击开始/停止，拖动后吸边，长按打开主界面或隐藏。停止后悬浮球继续待机，隐藏不会结束记录。
- **EXPORT CHART / SHARE REPORT**：完整会话、末尾 60 秒 / 5 分钟 / 15 分钟 / 1 小时或自定义本地日期时间。默认最近结束的会话，也可选择正在记录的会话。
- **GENERATE PNG**：固定 1080 × 1920 瑞士风格海报；预览可双指缩放。**SAVE IMAGE** 保存到 `Pictures/NETLINE/`；**SHARE IMAGE** 直接打开系统分享，不要求先保存。

不申请互联网、相册读取、通用存储、无障碍或开机广播权限。通知权限可拒绝，Android 仍可能在系统任务管理中显示前台服务。

## 统计与精度

TrafficStats 总收发计数器以 1 秒间隔采样，使用单调时钟计算字节/秒。结果描述设备计数器流量，不是测速服务器测得的带宽；VPN、接口、厂商系统、休眠可能影响结果。

- 一个 `MonitorService` 持有唯一采样器；界面、悬浮球、通知通过同一个 `MonitorController` 控制。
- `IDLE → STARTING → RECORDING → STOPPING → IDLE`；数据库成功创建会话后才进入 RECORDING，停止等待剩余数据提交。
- 无效计数器、计数器重置、超过 2.5 秒的采样间隔保留为缺失区间，不用零填补。设备时钟突变时安全停止会话。
- 平均速度 = 有效区间字节总数 / 实际有效采样时长；合法零速率可以是谷值。极值重复时取最早时间。
- 只统计完全落在选择范围内的区间；边界不按比例捏造字节数。报告显示所选时长与有效采样时长。
- 图表按真实时间映射；降采样保留每列两个方向的最大、最小及端点，先拆间断再降采样。
- 原始采样在冷启动时清理已结束会话中超过 7 天的完整分钟桶；持续中的会话保留原始数据直到结束并在后续冷启动清理。分钟汇总保留。旧汇总使用范围线和均值点，不重造秒级曲线。
- Room schema v2 提供 `MIGRATION_1_2`。旧字段缺失的谷值、峰谷时间、覆盖信息保持 NULL/未知；不破坏已有历史。因本次从零搭建，没有外部旧版 NETLINE 数据库可验证兼容；已测试本仓库定义的 v1 基线迁移。
- 分享临时缓存最多保留最近 12 张，生成时清理超过 24 小时文件；设置支持手动清理，保留当前预览。相册正式文件不受清理影响。

## Android 16 服务边界

前台服务类型使用 `specialUse`，Manifest 明确描述用户主动开始、本地可见、随时可停止的设备流量记录和跨应用悬浮控制。没有使用 `dataSync`、自启动、循环拉活或隐藏 API。

用户从可见 Activity 启用服务。待机悬浮球保留相应前台通知，不进行采样；点击可见球直接调用已运行的服务，不另起采样器或新的后台前台服务。关闭悬浮球且没有记录时移除视图并停止服务。

Android 15+ 依赖 SYSTEM_ALERT_WINDOW 例外从后台启动前台服务必须已经存在可见悬浮窗。持有权限不是通用后台启动豁免。本实现不依赖该权限来循环重启服务。

系统可在锁屏、系统授权页、安全界面或受保护窗口隐藏悬浮球；厂商或用户可终止服务。`START_NOT_STICKY`，不承诺永久存活。重新打开 NETLINE，在设置显式重新启用即可恢复；中断会话按最后提交的采样关闭。无唤醒锁，休眠导致的缺失不会伪装成连续数据。

仅个人侧载使用，不提交 Google Play；若将来上架，`specialUse` 的用途和声明须接受 Play 审核，当前代码不代表审核通过。

官方资料（2026-10-16 核对）：

- [Foreground service types / Special use](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
- [Restrictions on starting foreground services from background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 15 overlay restrictions](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed)

## 构建与测试

需要 JDK 17、Android SDK 36。将自己的 SDK 路径写入不跟踪的 `local.properties`，也可配置 `ANDROID_HOME`。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 连接已允许 USB 调试和 USB 安装的手机，手动授予 NETLINE 悬浮窗权限。
# 个人使用的手机优先用以下路径，保留安装、权限和已有数据：
.\gradlew.bat :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.netline.app.test/androidx.test.runner.AndroidJUnitRunner
```

`connectedDebugAndroidTest` 默认可能在测试结束或安装失败后清理安装包，使数据与权限重置，勿直接用于已有重要记录的个人手机。测试迁移、PNG 和异常数据使用隔离数据库；悬浮操作测试会在实际历史中留下短暂的真实采样会话。

当前版本：1.2.0。Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。Debug 使用当前电脑的默认调试签名，换电脑签名后可能无法覆盖安装，切勿为覆盖安装直接卸载而丢失记录。

测试分层：JVM 精确统计与计数器异常、设备 Room 迁移 / PNG / MediaStore / 跨 UID 图片读取，以及真机上的悬浮操作、中英文切换与长按删除。合成数据只存在隔离测试数据库，真实用户会话不会混入演示数据。详见 [1.2 验收记录](docs/VALIDATION-1.2.md)；[1.1 验收记录](docs/VALIDATION.md) 保留原版证据。

## 代码入口

`data/` 为 Room、迁移、Repository 和 DataStore；`monitor/` 为共享状态机与唯一服务；`overlay/` 为原生 56dp 视图、手势和 WindowManager 生命周期；`export/` 为图表公共数据、精确统计、离屏 Canvas 渲染与图片保存/分享；`ui/` 为 Compose 与 ViewModel。
