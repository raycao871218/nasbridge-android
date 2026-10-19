# NASBridge Android

原生 Android NASBridge 客户端（Java/XML），Android 6.0+，当前版本 0.15.0。使用深蓝、青绿与浅蓝主题和桥形自适应图标。首次安装不包含 NAS Host 或访问凭据；在家同步预填本项目使用者的 HA 地址及实体，可在设置页修改。

## 功能

- 总览：Mihomo 状态、手动通过 NAS/Mihomo 检测 Google、今日未读文章、证书有效期。
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

## 在家状态同步

在“配置 → 在家状态同步”确认 HA HTTPS 地址和 `input_boolean` 实体，填写 HA 长期访问令牌，然后点击“保存并启用 / 立即同步”。默认实体为 `input_boolean.bowen_shi_fou_zai_jia`。内外网判断统一使用“网络配置”的家庭 SSID、Host 和 Host2，不再单独设置在家同步的 SSID。令牌可在 HA 个人资料的安全页面创建，使用 Android Keystore AES-GCM 加密保存，设置页留空表示保留原令牌。地理围栏从 NASBridge `/api/geofence/config` 读取，HA 上报仍由手机直接执行。

- 共享路由选中内网 Host：立即 `turn_on`，取消外网窗口和定位。家庭 Wi-Fi 的内网 ping 失败按共享路由回退外网。
- 每 5 分钟读取 NAS schema 2 圆形围栏（也可手动刷新）；验证中心点、半径、时间和误差设置后缓存。读取失败保留上次有效配置。修改配置重开检测窗口，避免旧围栏结果写入 HA。
- 外网按 NAS 配置每小时定位一次，最多 4 小时；以 WGS-84 大圆距离计算，距离超过“半径 + 边界缓冲 + 定位精度”才提前 `turn_off`。围栏内、边缘和无效定位保留 HA 当前值，持续外网到期仍兜底 `turn_off`。NAS 测试模式使用 15 秒间隔、60 秒窗口；本机旧测试开关仅在 NAS 围栏未启用时生效。
- 原生 LocationManager 使用网络/GPS，无需 Google Play；每次最长 30 秒（测试 7.5 秒），拒绝过期、未来或精度不足的位置，结束后移除监听。位置不上传 NAS、不保存坐标历史。需要精确定位和开启系统定位。
- 通知和设置页显示离家倒计时及下次检测时间。计时使用 elapsedRealtime，系统休眠或修改系统时间不会缩短等待；允许空闲唤醒的非精确闹钟辅助检测，系统 Doze 可推迟执行，恢复后跳过错过的检测时段，避免集中定位。回到内网/断网取消定位和窗口，路由探测保留窗口但不写 HA；服务重新创建后重开窗口，避免把未监测时间算作持续离家。
- 主动启用后运行 location 类型前台服务，显示常驻通知；网络变化触发检测，另外每分钟重新选择内外网路由并核对 HA 当前值，仅不一致时调用服务，并读取实体确认结果。断网或认证失败每 15 秒重试，恢复时根据最新路由判断，不重放旧状态。
- 从通知或设置页停止会关闭自动同步，保留 HA 最后状态。没有网络时保持未知，不累计为持续外网。
- 重启自动恢复需要 Android 10+ 的“始终允许”定位权限，可通过页面中的系统权限设置授予；未授予时重启后打开同步页恢复。部分厂商系统需要额外允许自启动、解除电池限制。重新打开 App 的任意页面时，已启用的同步会自动恢复并重新判断路由。设置页区分“已开启”与“后台运行中”，显示后台定位与电池优化状态，并提供系统电池设置入口。强行停止或厂商后台清理后不能保证自动唤醒，需重新打开 App。
- 只接受 HTTPS 地址，使用系统证书验证，禁止跟随重定向携带令牌；HTTP 状态码会显示在同步状态中。首次 HA 初始值从接口读取，不假定为固定的 `off`。

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

`-PpresenceTests` 选择 PresenceInstrumentation，仅在专用模拟器运行：验证本地 HA fixture 的开关、认证和重定向处理、令牌加密、设置页面和前台服务启停；不会调用真实 HA，验证 NAS 圆形配置解析/缓存、原生模拟位置、围栏内外、更新配置和失败保留、60 秒到期兜底；结束后恢复 HA、围栏缓存和网络配置。运行命令为 `./gradlew :app:assembleDebugAndroidTest -PpresenceTests`，安装后使用 `adb -e shell am instrument -w com.nas.dashboard.test/com.nas.dashboard.PresenceInstrumentation`。

`-PvideoTests` 选择 VideoInstrumentation；不传服务地址时只测试本地 Range 数据源。`-PsmsTests` 选择 SmsInstrumentation，必须显式传入 `-e base` 指定自己的测试 NAS；它读取该服务的短信并操作模拟器剪贴板，结束后恢复配置。真实服务测试不应使用不愿读取的私人数据，且不会写入短信表。

XML 调色板位于 `app/src/main/res/values/colors.xml`；动态内容使用同一配色。按 [MIT](LICENSE) 开源，第三方说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。
