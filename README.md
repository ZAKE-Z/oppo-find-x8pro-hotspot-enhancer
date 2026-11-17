# OPPO Find X8 Pro Hotspot Enhancer

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

An Xposed / LSPosed / Vector module specifically developed and tested on **OPPO Find X8 Pro (ColorOS 17 / Android 15)**. It unlocks dual-band SoftAP (2.4 GHz + 5 GHz concurrency) and enables custom static gateway IP configuration for the mobile hotspot.

### Features
1. **Dual-Band SoftAP Concurrency**
   - Adds a native `Dual-Band` (or `双频` under Chinese locale) option in the hotspot band selection menu alongside system defaults (`2.4 GHz` and `5 GHz`).
   - Automatically adapts labels based on system language.
   - Reliably persists the dual-band configuration across settings updates and reboots.
   - Automatically provisions concurrent AP instances (`ap0` on 2.4 GHz + `ap1` on 5 GHz) when sharing cellular data.
   - Prevents the system from overriding the dual-band configuration with single-band defaults when changing the hotspot name or password.

2. **Custom Static Gateway IP**
   - Injects a dedicated "Gateway Address" section directly into the hotspot setup menu.
   - Allows setting a custom static gateway IPv4 address (e.g. `192.168.2.1`).
   - Leave empty to use system default randomized subnet assignment.
   - Built-in validation checks format and avoids conflicts with existing network interfaces.

### Tested Environment
- **Device**: OPPO Find X8 Pro (PKC110)
- **OS**: ColorOS 17 (Android 15)
- **Framework**: Vector / LSPosed (Zygisk)

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

### Hardware & Concurrency Notice
- Verified working on OPPO Find X8 Pro.
- In cellular hotspot mode (LTE/5G), dual-band AP (2.4 GHz + 5 GHz) runs concurrently.
- When the phone is connected to an external Wi-Fi router (Wi-Fi STA mode), underlying MediaTek firmware prioritizes STA reception and falls back to single AP mode.

---

<a name="chinese"></a>
## 中文

专为 **OPPO Find X8 Pro（ColorOS 17 / Android 15）** 实际测试与适配的 Xposed / LSPosed / Vector 模块。整合“双频热点并发解锁”与“热点固定网关 IP 地址”两大核心能力。

### 主要功能
1. **双频热点并发（2.4 GHz + 5 GHz）**
   - 在系统热点频段弹窗中无缝加入“双频”（英文语言下显示为 `Dual-Band`）选项，保留系统原有的 2.4 GHz 与 5 GHz 文案。
   - 彻底解决设置页面选频置灰、修改密码/热点名称后频段被单频覆盖的问题。
   - 配置落盘与开机持久化，整机重启后不丢双频配置。
   - 在蜂窝移动数据共享模式下，底层拉起两个独立的射频实例（`ap0` 广播 2.4 GHz，`ap1` 广播 5 GHz），旧设备与高速设备并发连接。

2. **热点网关 IP 自定义（固定网关）**
   - 在热点设置界面内无缝注入“网关地址”分类与输入框。
   - 支持自定义固定网关 IP（如 `192.168.2.1`），方便固定连接设备的网段。
   - 留空时由系统自动随机分配，输入实时做有效性校验与子网冲突检测。

### 实测环境
- **机型**：OPPO Find X8 Pro (PKC110)
- **系统版本**：ColorOS 17 (Android 15)
- **运行环境**：Vector / LSPosed (Zygisk)

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

### 运行机制与硬件说明
- 本模块实测仅在 OPPO Find X8 Pro (ColorOS 17) 验证通过，不作其他机型保证。
- 在通过蜂窝网络（5G/4G）共享热点时，2.4G + 5G 双频实例完全并发运行。
- 在连接外部路由器 Wi-Fi（STA 模式）共享时，受限于天玑平台底层闭源驱动的射频仲裁策略，系统会优先保障 Wi-Fi 接收通道而回退为单频。
