# NETLINE 1.2.0 验收

2026-10-16，Asia/Shanghai。设备：用户 USB 连接的 MEIZU 20 Pro，Android 16 / API 36。使用原电脑 JDK 17、SDK 36、Gradle 8.13 和已有依赖缓存，没有启动模拟器，没有卸载主应用或清空用户数据。

## 本次变更

- 悬浮球可见圆形由 56dp 改为 40dp，窗口/触控范围改为 48dp。拖动、吸边、单击、长按及菜单外关闭逻辑保留。
- 设置页提供 `中文` / `English`。默认采用系统中文或回退英文，明确选择后用 DataStore 持久化。Compose 界面、主界面权限提示、悬浮球内容描述/菜单、前台通知和新生成 PNG 的文字同步本地化。
- 使用 Android 标准资源 `values/strings.xml`、`values-zh/strings.xml`，通过独立配置上下文及 Compose LocalResources 解析语言，不修改系统语言，不为切换语言停止或重新启动监控服务。
- 历史行支持长按 → 删除记录 → 确认；可取消。数据库事务一次删除会话、原始采样和分钟汇总，底层拒绝删除未结束会话；没有数据库结构变化，无须新 Migration。其他历史与已保存的相册图片保留。

## 验证结果

| 项目 | 结果 | 实际证据 |
| --- | --- | --- |
| Debug APK / 单元测试 / Lint | PASS | 构建成功；原 13 项统计与计数器测试通过；Lint 零错误 |
| 真机 instrumentation | PASS | 10 项测试通过，包含原有监控、Room 迁移、PNG/相册/跨 UID 分享回归 |
| 可见尺寸 / 触控范围 | PASS | 渲染位图测得约 40dp 可见圆；真机 WindowManager 可访问窗口为 48dp，原始拖动、旋转、长按和吸边用例通过 |
| 中英文切换 | PASS | 通过实际设置界面切换中文、英文；中文界面在 Activity 重建后保留；DataStore 保存选择 |
| 记录不中断 | PASS | 切换语言、重建 Activity 后 activeSessionId 不变；悬浮球内容描述与前台通知随语言变化 |
| 报告语言 | PASS | 同一组隔离夹具分别生成中英文 1080×1920 PNG，解码成功，数据统计一致 |
| 长按删除 | PASS | 真机新建真实测试会话，长按弹出删除菜单；取消后记录仍在；确认后该会话及采样/汇总全部移除，已有的历史 ID 集合不变 |
| 记录中保护 | PASS | 正在记录的历史行提示先停止；Repository/Room 同样拒绝删除，采样数据保留 |
| 原有长期/跨机型边界 | PASS WITH RISKS | 沿用 1.1 的范围：未做隔夜省电、其他机型及微信/QQ 实际发送验证 |

当前无 BLOCKED 项；本次三项需求无 NOT IMPLEMENTED 项。

## 新增设备测试

`DataAndExportDeviceTest`：

- `deletingEndedSessionIsAtomicAndProtectsActiveAndOtherHistory`：隔离数据库验证完整删除、重复删除、活动记录保护与其他会话数据保留。
- `chineseAndEnglishReportsRenderWithoutChangingData`：同一夹具分别渲染中英文，验证资源解析、PNG 尺寸与原始统计一致。

`LanguageAndHistoryDeviceTest`：

- `compactBallDraws40DpInside48DpTouchTarget`：检查原生 View 实际绘制范围及透明触控余量。
- `switchingLanguagesKeepsRecordingAndLongPressDeletesOnlySelectedSession`：真实采样中在设置页切换两种语言，重建界面，检查通知与悬浮内容描述，确认活动行不可删除，再验证取消/确认删除及原历史完整性。只删除此测试自行新建的会话。

其余 6 项为 [上一版验收](VALIDATION.md) 中已有测试；语言相关选择器已改用资源解析后的文本。首次 UI 用例因 Android XML 资源会合并多个空格而未匹配历史行，调整测试选择器读取实际资源后通过；没有修改历史内容来迁就测试。

## 手动查看

中英文报告的字体、时间轴、完整排版，以及最终手机设置页/历史菜单均通过手机截图或导出文件查看。测试图使用隔离夹具；不将用户真实流量报告、手机状态栏和 UI 原始抓取提交到 Git。

[中文报告夹具](test-report-zh.png) 与 [英文报告夹具](test-report-en.png) 使用同一组合成采样，供查看字体及排版，不代表用户的实际网络流量。

手机使用普通 `adb install -r` 覆盖安装以保留历史，再用 `am instrument` 执行；不使用会清理个人应用安装/权限的 Gradle connected 路径。测试结束后移除独立测试 APK，保留正式 NETLINE。
