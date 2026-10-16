NETLINE 1.1.0，个人自用版本，Android 10+，Target SDK 36。

- 真实本机流量采样、Room 历史与 Compose 实时双曲线。
- 56dp 全局悬浮球：点击开始/停止、拖动吸边、长按菜单，停止后继续待机。
- 完整会话、60 秒、5/15 分钟、1 小时及自定义范围的 1080×1920 PNG 报告。
- 精确峰谷、极值时间、有效时间加权均速与真实收发总量；缺失区间不补零，分钟历史不重造秒级曲线。
- 图片预览、MediaStore 相册保存、FileProvider 与系统 Sharesheet 分享。
- 13 项 JVM 测试、6 项 Android 16 魅族 20 Pro 手机测试通过，Lint 零错误。

总体验收：PASS WITH RISKS。仅验证一台手机及短时流程；系统/厂商可限制悬浮窗与后台服务，不承诺永久存活。微信/QQ 具体发送与隔夜省电场景未验证。详见仓库 docs/VALIDATION.md。

附件为 Debug APK，使用此电脑默认调试签名。个人侧载使用；不包含互联网、相册读取、通用存储或无障碍权限。

APK SHA-256：`7c6b850fc18c56d977b86f8427fd1559ece9080a5f85f9fdfe0aacb0e2153a63`。
