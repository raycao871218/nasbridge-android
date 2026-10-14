# NASBridge Android

原生 Android NASBridge 客户端（Java/XML），Android 6.0+，当前版本 0.13.1。使用深蓝、青绿与浅蓝主题和桥形自适应图标。首次安装不包含任何真实 Host、SSID 或访问凭据。

## 功能

- 总览：Mihomo 状态、手动通过 NAS/Mihomo 检测 Google、今日未读文章、证书有效期。
- 服务：Mihomo、犀牛哥视频、文章、短信、每日任务、证书与反代、阿里云配置状态和 ZeroTier 网络信息。
- 文章：摘要分页、全文阅读、已读文章回看，用户主动点击时才提交已读状态。
- 视频：缩略图列表、Range 分段播放、倍速、全屏、左右快进/快退、左右两侧分别调亮度/音量；系统解码器决定可用格式。
- 短信：只读归档、分页搜索和全文，明确识别到验证码后可复制；复制内容标记为敏感。
- 配置：家庭 Wi-Fi SSID、内网 Host/端口及公网 Host2/端口。匹配家庭 Wi-Fi 时 ICMP 探测内网 Host，失败或其他网络使用 Host2。
- 网络环境变化时更新路由；业务页面不要求先手动诊断，不进行定时网络轮询。
- 底部“通知”栏目暂为空，没有手机推送接收服务。

业务管理页面以读取为主；文章已读状态是用户主动触发的写入。App 不执行云资源或 NAS 基础设施管理。Google 检测测试的是 NAS 代理链路，播放和短信功能需要对应的服务端接口。

## 服务端

需要自行部署 [NASBridge](https://github.com/raycao871218/NASBridge/tree/master/docker/nasbridge)，包括移动端与短信读取接口。数据库、视频和短信转存来源由服务端配置。本仓库不重复维护历史后端快照。

在 App 的“配置 → 网络配置”填写自己的地址。内网可使用 HTTP；公网应使用 HTTPS 及适当的访问控制。HTTP 诊断先 HEAD，遇到 405/501 才回退 GET；诊断结果不会阻止业务访问。SSID 识别需要前台定位权限及系统定位开关，不上传位置信息。

## 构建

依赖 JDK 17、Android SDK Platform 34、Build Tools 34.0.0。将自己的 SDK 路径写入未跟踪的 `local.properties`：

```properties
sdk.dir=/path/to/Android/sdk
```

```sh
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

发布正式 APK 时自行配置签名，勿提交密钥或密码。仓库不包含 APK、设备记录或截图。

## 测试

```sh
./scripts/test-routing.sh
./gradlew :app:assembleDebugAndroidTest
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -e shell am instrument -w com.nas.dashboard.test/com.nas.dashboard.RouterInstrumentation
```

默认仪器测试使用模拟器本地 HTTP fixture，并临时修改/恢复 Host 和 Wi-Fi；请仅在专用模拟器运行。纯 Java 测试验证路由、端口、日期、证书和 HTTP 诊断。

`-PvideoTests` 选择 VideoInstrumentation；不传服务地址时只测试本地 Range 数据源。`-PsmsTests` 选择 SmsInstrumentation，必须显式传入 `-e base` 指定自己的测试 NAS；它读取该服务的短信并操作模拟器剪贴板，结束后恢复配置。真实服务测试不应使用不愿读取的私人数据，且不会写入短信表。

XML 调色板位于 `app/src/main/res/values/colors.xml`；动态内容使用同一配色。按 [MIT](LICENSE) 开源，第三方说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。
