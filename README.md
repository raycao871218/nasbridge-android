# 麻雀会 · NASBridge Android

原生 Android 鸟巢客户端（Java/XML），Android 6.0+，当前版本 0.16.3。应用名为「麻雀会」，采用与「咕咕咕」一致的暖纸蜡笔风和麻雀自适应图标，设计说明见 [BRANDING.md](BRANDING.md)。首次安装不包含 NAS Host 或访问凭据；在家同步预填本项目使用者的 HA 地址及实体，可在设置页修改。

## 功能

- 总览：Mihomo 状态、手动通过 NAS/Mihomo 检测 Google、今日未读文章、证书有效期。
- 每日任务：最近任务默认展开，查看阶段状态、百分比、心跳、起止时间、耗时、媒体/概要处理结果和失败原因；历史运行可展开并保留展开状态，运行中每 15 秒刷新，离开页面停止刷新。最近概要可进入全文和关联视频，产出不推测归属于某次运行。旧记录无阶段数据时明确提示。
- 服务：Mihomo、犀牛哥视频、文章、短信、每日任务、证书与反代、阿里云配置状态和 ZeroTier 网络信息。
- 文章：摘要分页、全文阅读、已读文章回看，用户主动点击时才提交已读状态。
- 视频：缩略图列表、Range 分段播放、倍速、全屏、左右快进/快退、左右两侧分别调亮度/音量；系统解码器决定可用格式。
- 短信：只读归档、分页搜索和全文，明确识别到验证码后可复制；复制内容标记为敏感。
- 配置：家庭 Wi-Fi SSID、内网 Host/端口及公网 Host2/端口。匹配家庭 Wi-Fi 时 ICMP 探测内网 Host，失败或其他网络使用 Host2。
- 网络环境变化时更新路由；业务页面不要求先手动诊断。
- 在家状态同步：复用 App 内外网路由，内网开启 HA input_boolean；持续使用外网 4 小时后关闭（测试模式为 1 分钟，默认开启），支持后台检测、失败重试及状态核对。
- 底部“通知”栏目暂为空，没有手机推送接收服务。

业务管理页面以读取为主；文章已读状态是用户主动触发的写入，启用在家同步后会自动写入指定 HA 实体。App 不执行云资源或 NAS 基础设施管理。Google 检测测试的是 NAS 代理链路，播放和短信功能需要对应的服务端接口。

## 服务端

需要自行部署 [NASBridge](https://github.com/raycao871218/NASBridge/tree/master/docker/nasbridge)，包括移动端与短信读取接口。数据库、视频和短信转存来源由服务端配置。本仓库不重复维护历史后端快照。

在 App 的“配置 → 网络配置”填写自己的地址。内网可使用 HTTP；公网应使用 HTTPS 及适当的访问控制。HTTP 诊断先 HEAD，遇到 405/501 才回退 GET；诊断结果不会阻止业务访问。SSID 识别需要前台定位权限及系统定位开关，不上传位置信息。

## 扫码配置

在 NAS 后台打开“App 扫码配置”，填写家庭 Wi-Fi、内外网 NAS 地址（包含端口），按需填写 HA 地址、实体、令牌及测试模式，并勾选携带当前围栏。生成二维码后，在 App “配置 → 扫码导入配置”扫描，核对预览并确认导入。取消或扫码失败不会修改设置；令牌不显示在预览中，导入后使用 Android Keystore 加密保存。

后台“家庭 Wi-Fi 名称”可以留空。App 0.16.1+ 扫码后自动读取手机当前 Wi-Fi，并在预览中标注来源；确认是家里的 Wi-Fi 后再导入。首次需授权精确定位并打开系统定位。读取失败可重试或手动填写，取消不会保存；只读取已连接的 Wi-Fi 名称，不读取密码。后台已填写 SSID 时保持该名称。首次打开未配置的网络设置页，已有定位权限时也会自动填入当前 Wi-Fi。

二维码含访问令牌时请只提供给自己的手机。后台可“保存配置”：配置和令牌加密保存在 NAS，刷新后自动加载，令牌留空保留原值；可下载二维码留作新手机配置，下载文件仍可导入。自动同步在导入后停止，需在“在家状态同步”启用。相机、定位、通知等系统权限和电池限制无法通过二维码授权，仍需在手机设置。

## 在家状态同步

在“配置 → 在家状态同步”填写 HA HTTPS 地址、`input_boolean` 实体和长期访问令牌，点击“保存并启用”。家庭 Wi-Fi 使用“网络配置”里填写的 SSID；围栏中心和半径从 NAS 读取，每 5 分钟同步一次，也可手动同步。

- 连上家庭 Wi-Fi，立即设为在家（HA `on`），取消定位。此判断不依赖 NAS 的 ping 结果。
- 断开家庭 Wi-Fi，每 **30 分钟**定位一次。仍在围栏内、边缘位置不确定或定位失败，保留原状态，继续检查。
- 确认在围栏外，设为离家（HA `off`），停止自动定位，直到重新连上家庭 Wi-Fi。离家结果在服务重启后保留；没有“超时自动离家”。
- **测试模式**开关立即生效：开启为每 **1 分钟**检查，关闭恢复 **30 分钟**，从切换时刻重新计时。
- **立即测试当前状态**跳过等待，马上检查 Wi-Fi；不在家庭 Wi-Fi 时立即定位并显示结果。会保存当前 HA 设置、启用同步并按判断结果更新 HA。已离家后也可手动再测一次。

NAS 未启用围栏或配置读取失败时保留原状态；已有有效围栏缓存可继续使用。旧 NAS 配置里的最长时长、检测间隔和测试模式不再控制手机，手机页的测试开关决定间隔。位置只在手机判断，不上传坐标；为避免边缘误判，距离超过半径加定位精度才确认离开。每次定位最长 30 秒，仅使用一分钟内的有效位置。

后台运行使用常驻通知。Android 休眠可能延后检测，恢复后只检查一次，不集中补测。无互联网时仍可判断 Wi-Fi 和定位，HA 上报失败后重试；Wi-Fi 名称无法读取时保留状态。停止同步保留 HA 最后状态。重启恢复需要“始终允许”定位权限，部分手机还需允许自启动并取消电池限制；强行停止后需重新打开 App。

HA 令牌使用 Android Keystore 加密保存在本机，填写处留空可保留原令牌。只接受 HTTPS HA 地址并使用系统证书验证，上报后读取实体确认结果。

接口依据 [HA REST API](https://developers.home-assistant.io/docs/api/rest/)，后台服务遵循 [Android location 前台服务要求](https://developer.android.com/develop/background-work/services/fgs/service-types#location)。

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

`-PpresenceTests` 选择 PresenceInstrumentation，仅在专用模拟器运行：验证本地 HA fixture 的开关、认证和重定向处理、令牌加密、设置页面和前台服务启停；不会调用真实 HA，验证 NAS 圆形配置解析/缓存、原生模拟位置、围栏内外、更新配置和失败保留、1 分钟周期和立即测试、离家停止及服务恢复；结束后恢复 HA、围栏缓存和网络配置。运行命令为 `./gradlew :app:assembleDebugAndroidTest -PpresenceTests`，安装后使用 `adb -e shell am instrument -w com.nas.dashboard.test/com.nas.dashboard.PresenceInstrumentation`。

`-PvideoTests` 选择 VideoInstrumentation；不传服务地址时只测试本地 Range 数据源。`-PsmsTests` 选择 SmsInstrumentation，必须显式传入 `-e base` 指定自己的测试 NAS；它读取该服务的短信并操作模拟器剪贴板，结束后恢复配置。真实服务测试不应使用不愿读取的私人数据，且不会写入短信表。

XML 调色板位于 `app/src/main/res/values/colors.xml`；动态内容使用同一配色。按 [MIT](LICENSE) 开源，第三方说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。

扫码导入的独立模拟器验证：`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PsetupTests -PuiPreview`，安装两份 APK 后运行 `adb -e shell am instrument -w com.nas.dashboard.preview.test/com.nas.dashboard.SetupInstrumentation`。测试使用虚构配置和后台实际渲染的二维码，验证预览/取消、导入、令牌加密及自动同步保持停止。`uiPreview` 使用独立应用 ID，不覆盖个人配置。JUnit 验证命令：`./gradlew :app:testDebugUnitTest`。

每日任务展示测试：`./gradlew :app:testDebugUnitTest` 验证终止阶段状态及北京时间/耗时；`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PuiPreview -PtaskTests` 生成隔离预览测试包，运行 `com.nas.dashboard.preview.test/com.nas.dashboard.DailyTasksInstrumentation` 验证任务详情、展开恢复、进度和空状态。使用虚构只读数据。
