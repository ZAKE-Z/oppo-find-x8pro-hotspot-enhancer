# HotspotEnhancer

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

An Xposed / LSPosed / Vector module designed for ColorOS / Android to unlock dual-band SoftAP (2.4 GHz + 5 GHz concurrency) and enable custom static gateway IP configuration for the mobile hotspot.

### Features
1. **Dual-Band SoftAP Concurrency**
   - Adds a native `双频` (Dual-Band) selection in hotspot settings alongside standard 2.4 GHz and 5 GHz options.
   - Preserves standard system text without intrusive descriptions.
   - Seamlessly persists dual-band configuration across app sessions and system reboots.
   - Automatically provisions concurrent AP instances (`ap0` on 2.4 GHz + `ap1` on 5 GHz) when active as an LTE/5G cellular gateway.
   - Protects settings from being clobbered or overridden by single-band defaults during SSID/password modification.

2. **Custom Static Gateway IP**
   - Adds a dedicated "Gateway Address" section directly in the hotspot setup UI.
   - Allows pinning a custom static gateway IPv4 address (e.g. `192.168.2.1`).
   - Leave empty to let the system automatically assign a randomized subnet.
   - Validates input format and detects IP/subnet conflicts before applying.

### Target Scope
- `com.android.networkstack.tethering`
- `com.oplus.wirelesssettings`
- `android` (system_server)

### Build
Standard Gradle / Android Studio project structure:
```bash
./gradlew assembleRelease
```
The compiled APK will be output to `app/build/outputs/apk/release/app-release.apk`.

### Requirements
- Android 10+ (tested on Android 14 / ColorOS 14 & 15 & 17)
- Xposed framework environment (LSPosed, Vector, etc.)

### Notes & Limitations
- Hardware DBS (Dual-Band Simultaneous) chip architecture governs concurrent radio operation.
- In cellular hotspot mode, dual-band AP (2.4G + 5G) operates fully concurrently.
- When connected to an external Wi-Fi router (Wi-Fi STA mode), underlying MediaTek/vendor drivers prioritize STA throughput and may fallback to single AP mode depending on hardware radio lane availability.

---

<a name="chinese"></a>
## 中文

专为 ColorOS / Android 设计的 Xposed / LSPosed / Vector 模块。整合“双频热点并发解锁”与“热点固定网关 IP 地址”两大核心能力。

### 主要功能
1. **双频热点并发（2.4 GHz + 5 GHz）**
   - 在系统热点频段设置弹窗中补齐“双频”选项，保留系统原汁原味的 `2.4 GHz（兼容性好）` 与 `5 GHz（网速快）` 文案。
   - 彻底解决设置页面选频置灰、修改密码/名称后频段被单频覆盖的问题。
   - 配置落盘与开机持久化，重启后不丢双频设置。
   - 在蜂窝移动数据共享模式下，底层真正拉起两个独立的射频实例（`ap0` 广播 2.4 GHz，`ap1` 广播 5 GHz），旧设备与高速设备各取所需。

2. **热点网关 IP 自定义（固定网关）**
   - 在热点设置界面内无缝注入“网关地址”分类与输入框。
   - 支持自定义固定网关 IP（如 `192.168.2.1`），方便固定车机、物联网设备或局域网服务地址。
   - 留空时由系统自动随机分配，输入实时做有效性校验与子网冲突检测。

### 模块作用域
- `com.android.networkstack.tethering`（网络栈服务）
- `com.oplus.wirelesssettings`（无线设置界面）
- `system`（系统核心服务 `android`）

### 项目编译
标准 Android Studio / Gradle 工程结构，可直接导入开发环境或通过命令行构建：
```bash
./gradlew assembleRelease
```
编译产物位于 `app/build/outputs/apk/release/app-release.apk`。

### 运行环境
- Android 10 及以上（已实测适配 Android 14 / 15 及 ColorOS 17）
- Xposed / LSPosed / Vector 运行环境

### 运行机制说明
- 模块底层依赖芯片硬件物理天线与射频链路。
- 在通过蜂窝网络（5G/4G）共享热点时，2.4G + 5G 双频实例完全并发运行。
- 在连接外部路由器 Wi-Fi（STA 模式）共享时，受限于天玑平台底层闭源驱动的射频资源分配策略，系统会优先保障 Wi-Fi 接收通道而可能动态回退为单频。
