# YINPAGE-LINK

> 为 **YINPAGE 音贝奇 Feel 1 Pro** 蓝牙耳机打造的 Android 控制应用
> —— 免 root、免 LSPosed，直连耳机读写状态与控制指令。

<p align="left">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-27-brightgreen">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-35-green">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-blue">
  <img alt="root" src="https://img.shields.io/badge/root-not%20required-success">
</p>

---

## 这是什么

音贝奇（YINPAGE）的官方 App 只能做基础控制，而各家手机厂商的「融合中心 / 耳机卡片」生态只认自家耳机。
本项目把 Feel 1 Pro 的能力**从官方 App 里解放出来**，做成一个完全独立、只用 Android 公开 API 的控制应用：

| 功能 | 说明 |
|---|---|
| 🔋 三路电量 | 左耳 / 右耳 / 充电盒，独立电量环 + 充电状态角标 |
| 🎧 四档降噪 | 关闭 / 通透 / 标准降噪 / 深度降噪，与系统耳机卡片档位语义一致 |
| 🎚 EQ 音效 | 均衡 / 重低音 / 人声 / 高音增强 / 现场 / 游戏增强 |
| 🎮 游戏模式 | 低延迟开关 |
| 👂 佩戴检测 | 入耳检测开关 |
| 🔗 双设备连接 | 多点连接开关 |
| 🌬 抗风噪 | 独立开关 |
| 📊 调试面板 | 收发字节流实时十六进制显示，协议格式可运行时切换 |

## 为什么不需要 root

本项目**只使用 Android 公开 API**：

- 运行时权限：`BLUETOOTH_SCAN`（`neverForLocation`）、`BLUETOOTH_CONNECT`
- 经典蓝牙 RFCOMM/SPP：`BluetoothAdapter` + `BluetoothSocket`
- BLE GATT：`BluetoothGatt` + `BluetoothLeScanner`
- 前台服务保活：`foregroundServiceType="connectedDevice"`

没有 Xposed、没有隐藏 API 反射、没有系统签名、没有 Shizuku 强依赖。

> **能力边界（诚实说明）**：正因为不 root，本应用**无法**把耳机卡片注入到澎湃 OS 的
> 设置页 / 控制中心 / 灵动岛里——那需要往 `com.android.settings`、`com.android.systemui`
> 等系统进程注入代码，只有 root + LSPosed 才能做到。
> 本项目的定位是：**用独立 App 提供等价甚至更完整的功能控制**。
>
> Shizuku（可选）能提供 shell 级权限，用于读取系统蓝牙管理器里的设备信息作为补充，
> 但它同样无法注入系统 UI。

## 技术路线

```
        ┌──────────────────────────────────────────────┐
        │  UI 层（Jetpack Compose + Material 3）        │
        │  HomePage / DevicePage / SettingsPage / Debug │
        └───────────────────────┬──────────────────────┘
                                │ 只依赖 AppState
        ┌───────────────────────▼──────────────────────┐
        │  core：AppState（状态协调器）                  │
        │  扫描 / 连接 / 心跳轮询 / 乐观更新 / 事件日志    │
        └───────────────────────┬──────────────────────┘
                                │ PodCoordinator 契约
        ┌───────────────────────▼──────────────────────┐
        │  SessionCoordinator（连接状态机）              │
        │  握手 · 读循环 · 异常翻译 · AUTO 通道回退       │
        └──────────┬───────────────────────┬───────────┘
                   │ PodTransport          │ PodCodec
        ┌──────────▼──────────┐  ┌─────────▼───────────┐
        │ RfcommTransport     │  │ BluetrumCodec       │
        │ BleGattTransport    │  │ （中科蓝讯 AB 系，已确证）│
        └─────────────────────┘  └─────────────────────┘
```

设计要点：

- **协议可插拔**：`PodCodec` 是接口，新增品牌只需实现一个类并 `ProtocolRegistry.register(...)`。
- **帧格式独立**：`BtFrameCodec` 只负责「帧 ↔ 字节」与流式分帧（粘包/半包/多包重组），
  不掺任何业务语义；`BluetrumCodec` 只负责命令表与状态解析。换品牌时前者可完全复用。
- **传输可替换**：`PodTransport` 抽象了 SPP 与 BLE，会话层不关心物理通道。
- **乐观更新**：控制指令先本地生效给即时反馈，耳机回包后再以真实状态纠正。
- **`(type, command)` 路由**：AB 系存在同码跨方向复用（如 `0x2C` 既是 ANC 设置也是 ANC 查询），
  只看 command 会串台，本项目所有分支都按二元组路由。

## 构建

### 环境要求

- JDK 17
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`
- Gradle 8.9（仓库内提供 wrapper，也可用系统 Gradle）

### 命令行

```bash
# debug APK
./gradlew :app:assembleDebug

# release（未签名，需自行配置签名）
./gradlew :app:assembleRelease

# 只编译 Kotlin（定位错误最快）
./gradlew :app:compileDebugKotlin
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### Android Studio

直接 `File → Open` 本仓库根目录，等待 Gradle Sync 完成后运行即可。

## 项目结构

```
app/src/main/java/com/yinpage/link/
├── YinpageApp.kt                 Application：装配传输 + 协议 + 会话
├── config/
│   └── ConfigManager.kt          配置持久化（SharedPreferences + StateFlow）
├── core/
│   ├── AppState.kt               ★ UI 唯一依赖的状态协调器
│   ├── SessionCoordinator.kt     连接状态机、握手、读循环、AUTO 回退
│   ├── Contracts.kt              PodCoordinator / TransportKind / ConnectResult
│   ├── Models.kt                 BluetoothDeviceItem / PodUiState
│   └── EventLog.kt               环形缓冲日志 + 十六进制收发记录
├── protocol/
│   ├── State.kt                  ★ 全部数据模型（电量/降噪/EQ/连接状态）
│   ├── Transport.kt              ★ PodTransport / PodCodec / ProtocolRegistry
│   └── yscoco/
│       ├── FrameFormat.kt        帧格式参数化 + 命令码表 + 候选变体
│       ├── YscocoFrameCodec.kt   流式分帧（粘包/半包）+ 四种校验
│       ├── YscocoCodec.kt        状态解析与控制指令编码
│       └── ByteUtil.kt           字节 / 校验工具
├── transport/
│   ├── RfcommTransport.kt        经典蓝牙 SPP
│   ├── BleGattTransport.kt       BLE GATT
│   └── TransportFactoryImpl.kt   通道工厂
├── service/
│   ├── PodConnectionService.kt   前台服务保活
│   └── BluetoothStateReceiver.kt 蓝牙状态广播
├── enhance/
│   └── ShizukuEnhancer.kt        Shizuku 可选增强（反射调用，零依赖）
└── ui/                           Compose 页面 / 组件 / 主题
```

## 耳机协议

Feel 1 Pro 的私有协议**没有任何公开文档**（厂商甚至把 FCC 的原理图申请了长期保密），
所以协议部分是本项目投入最多精力的地方。详见 **[doc/PROTOCOL.md](doc/PROTOCOL.md)**。

**逆向结论（已确证，非推测）**——通过对官方 App `com.yscoco.yinpage` v1.4.27 的完整反编译：

| 项 | 结论 |
|---|---|
| 传输通道 | **经典蓝牙 SPP/RFCOMM**，UUID `00001101-0000-1000-8000-00805F9B34FB` |
| 耳机主控 | **中科蓝讯 Bluetrum AB 系列**（`com.bluetrum.devicemanager` SDK，157 个类） |
| 帧格式 | 5 字节头 + payload，**无 CRC**：`[seq][command][type][chunk][len][payload…]` |
| 分包 | 默认每包 payload 15 字节，`chunk` 字节高 4 位 = 总包数-1、低 4 位 = 当前包序号 |
| 序号 | bits0-3，0..15 循环，按"消息"递增而非按帧 |
| 电量 | payload = `[左, 右, 盒]`，每字节 `bit7=充电中`、`bit0..6=电量` |
| ANC | `0x00` 关闭 / `0x01` 降噪开 / `0x02` 通透（`ANC_MODE = 0x2C`） |
| EQ | `0x20`，payload = `[bandCount, mode, gains…]`，`mode ≥ 0x20` 为自定义 |
| 查询 | 统一走 `DEVICE_INFO(0x27)`，payload = `[INFO_x, 0x00]`，共 33 个子码 |
| 能力位图 | `0xFE`：bit0=TWS、bit1=3D、bit2=多点、bit3=ANC |
| 跨方向复用 | `0x2B/0x2C/0x40/0x41` 请求与通知同码，**路由必须用 `(type, command)`** |

> 完整命令表（24 条请求命令 + 33 条 `INFO_*` 子码）与证据见 [doc/PROTOCOL.md](doc/PROTOCOL.md)。
>
> ⚠️ 仍待真机确认的项：RFCOMM channel 号、是否使用自定义 SPP UUID、
> EQ 列表精确字节序、电量 bit7 的边界语义。这些不影响主流程。

## 隐私

- 不联网、不上传任何数据、无第三方统计 SDK
- 所有配置仅存本机应用私有目录
- 蓝牙扫描结果只在本地内存中使用

## 致谢

本项目在架构设计上参考了以下优秀开源项目：

- [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) — 原道 OriG in 接入澎湃 OS（分层与协议组织方式的主要参考）
- [OppoPods](https://github.com/1812z/OppoPods) / [OppoPods-Enhanced](https://github.com/Leaf-lsgtky/OppoPods)
- [HyperPods](https://github.com/Art-Chen/HyperPods)
- [PuddingPods](https://github.com/Xposed-Modules-Repo/rongyi.puddingpods) — SPP 接入细节

## 许可

见 [LICENSE](LICENSE)。
