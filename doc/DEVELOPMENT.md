# 开发说明（自主开发内容 / 引用项目 / 数据来源）

> ## 📌 开发声明
>
> **本项目由 DSH（DeepSeek Harness）自主开发完成。**
>
> 从耳机协议逆向、架构设计、全部源代码编写、构建调试到文档撰写，均由 DSH 自主完成。
> 用户提供的是：目标设备（YINPAGE 音贝奇 Feel 1 Pro）、参考项目（HyperOriG）、
> 目标仓库、形态决策（不要 root）与实测反馈。
>
> ---
>
> 本文档回答三个问题：**哪些是自主开发的、引用了什么、用户提供了什么。**
> 目的是让任何接手的人都能清楚看到工程边界与可信度来源。

---

## 一、自主开发的部分

### 1.1 架构与契约层（100% 自主设计）

| 文件 | 说明 |
|---|---|
| `protocol/State.kt` | 全部数据模型：`PodState` / `BatteryState` / `NoiseMode` / `EqMode` / `PodUpdate` / `PodCommand`。采用 **delta（增量更新）** 而非全量快照，因为耳机常常只上报单耳电量，全量覆盖会把另一只的电量擦成 0 |
| `protocol/Transport.kt` | `PodTransport` / `PodCodec` / `ProtocolRegistry` 抽象。传输层不理解协议语义，协议层不关心物理通道 |
| `core/Contracts.kt` | `PodCoordinator` 会话契约 + `ConnectResult` 结果模型 |
| `core/AppState.kt` | UI 唯一依赖的状态协调器：扫描、连接、心跳轮询、乐观更新、状态同步 |
| `core/Models.kt` / `core/EventLog.kt` | UI 共享模型 + 环形缓冲日志（十六进制收发记录） |
| `config/ConfigManager.kt` | 配置持久化（SharedPreferences + StateFlow 双向同步） |

**设计取舍说明：**

- **为什么用 `IntArray` 而不是 `ByteArray` 传字节**：Kotlin 的 `Byte` 有符号，`0xFF` 会变成 `-1`，
  在协议解析里极易出错。统一用 `Int`（0..255）表示无符号字节，只在 Stream/Characteristic
  读写边界做一次转换，把类型坑收敛到一个地方。
- **为什么状态更新用 delta**：见上表 `State.kt` 说明。
- **为什么要"乐观更新"**：白牌 TWS 的命令-响应延迟可达数百毫秒，如果等回包才改按钮状态，
  用户会觉得"点了没反应"。所以先本地生效，回包后以真实状态纠正。

### 1.2 协议逆向（自主完成）

| 工作 | 产出 |
|---|---|
| 获取官方 APK | `com.yscoco.yinpage` v1.4.24，132.8 MB |
| 无 jadx 环境下的 DEX 字符串提取 | 自写 `_rev/DexScan.java`（纯 JDK，解析 DEX string_ids 的 MUTF-8 长度前缀格式） |
| 协议家族判定 | 确证为中科蓝讯 **Bluetrum AB 系** `devicemanager` SDK |
| 通道判定 | 确证为 **SPP/RFCOMM，标准 UUID `00001101-...`** |
| 指令集枚举 | 提取 20+ 个 `*Request` 类名，得到完整能力清单 |
| 状态语义 | 电量三路（左/右/盒）、ANC 四档 + AUTO、GAME_MODE |
| 结论整理 | `doc/PROTOCOL.md`（严格区分已确证 / 待验证） |

### 1.3 协议实现（先推测、后按确证结论重写）

| 文件 | 说明 |
|---|---|
| `protocol/bluetrum/BtCommand.kt` | 命令码表（24 条请求命令 + 33 条 `INFO_*` 子码）、ANC/EQ 取值、能力位图、电量编解码 |
| `protocol/bluetrum/BtFrameCodec.kt` | **与业务语义无关的帧编解码**：5 字节头、流式分帧（粘包/半包）、多包重组、发送序号管理 |
| `protocol/bluetrum/BluetrumCodec.kt` | `PodCodec` 实现：握手序列、命令编码、状态解析、置信度评估 |

**实现演进的诚实记录**：

1. **第一阶段（推测）**：逆向尚未出结果时，按同类白牌 TWS 方案的结构性推断，
   实现了 `protocol/yscoco/`（4 个文件），把帧格式参数化为 `FrameFormat` 数据类，
   内置 4 个候选变体（`0x4E` / `0xAA55` / `0x55` / `0x5A`），可在调试面板运行时切换。
   这一步的价值是让整个 App 的其余部分（传输、会话、UI）能先跑通并编译通过。
2. **第二阶段（确证后重写）**：反编译拿到真实帧格式后，发现**推测是错的**——
   AB 系帧头没有魔数，而是 `[seq][command][type][chunk][len]` 五段式，且无 CRC。
   于是**删除** `protocol/yscoco/`，按确证结论重写为 `protocol/bluetrum/`。

这个"先搭可替换骨架、拿到证据后替换内核"的过程，正是把协议层与业务层分离的收益：
UI、传输、会话三层**一行都没改**。

### 1.4 传输层与应用层

| 文件 | 说明 |
|---|---|
| `transport/RfcommTransport.kt` | 经典蓝牙 SPP，多 UUID 候选遍历（安全/非安全通道都试） |
| `transport/BleGattTransport.kt` | BLE GATT，服务树枚举 + 特征值打分选择 + MTU 分片 |
| `transport/TransportFactoryImpl.kt` | 通道工厂 + `TransportEnv`（Context 注入） |
| `core/SessionCoordinator.kt` | 连接状态机：AUTO 通道回退（RFCOMM → BLE）、握手、读循环、异常翻译、超时看门狗 |
| `service/PodConnectionService.kt` | 前台服务保活 |
| `service/BluetoothStateReceiver.kt` | 系统蓝牙广播 → AppState 状态同步 |
| `enhance/ShizukuEnhancer.kt` | Shizuku 可选增强（纯反射，零依赖） |

---

## 二、引用的开源项目

### 2.1 架构设计参考（重要说明：**仅借鉴设计思路，未复制代码**）

| 项目 | 借鉴了什么 | 许可 |
|---|---|---|
| [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) | **主要参考对象**。分层方式（`pods/` 协议层 + `hook/` 注入层 + `ui/` 界面层）、`ConfigManager` 的配置组织、`HookContext` 的反射工具思路 | 见其仓库 |
| [OppoPods](https://github.com/1812z/OppoPods) / [OppoPods-Enhanced](https://github.com/Leaf-lsgtky/OppoPods) | RFCOMM 控制器与 SPP UUID 候选策略的思路 | 见其仓库 |
| [HyperPods](https://github.com/Art-Chen/HyperPods) | 第三方耳机接入澎湃 OS 的整体形态 | 见其仓库 |
| [PuddingPods](https://github.com/Xposed-Modules-Repo/rongyi.puddingpods) | 公开文档 `PUDDING_ADAPTATION.md` 中的 SPP 接入细节与作用域清单 | 见其仓库 |
| [NiceHCK_Controller](https://github.com/ZaeXT/NiceHCK_Controller) | 耳机私有协议解析的组织方式 | 见其仓库 |

### 2.2 与本项目的根本差异（必须说明）

上述项目**全部是 LSPosed 模块**，需要 root，做法是往
`com.android.settings` / `com.android.systemui` / `com.milink.service` 等系统进程**注入代码**，
从而把第三方耳机伪装成"受支持设备"塞进系统耳机卡片。

**本项目不做任何注入**：

| 维度 | HyperOriG 等 | 本项目 |
|---|---|---|
| 运行形态 | LSPosed 模块（注入系统进程） | 独立 App |
| 权限要求 | root + LSPosed | 仅官方蓝牙运行时权限 |
| 设置页耳机卡片 | ✅ 有 | ❌ 做不到（物理上不可能，见下） |
| 控制中心/灵动岛 | ✅ 有 | ❌ 做不到 |
| ANC/EQ/电量控制 | ✅ | ✅（本项目主功能） |
| 系统兼容性风险 | 高（依赖系统内部类名，版本升级易失效） | 低（只用公开 API） |

> **能力边界**：不 root 就无法把界面注入系统进程，这是 Android 的安全模型决定的，
> 不是实现问题。所以本项目选择把"控制能力"做完整，而不是追求系统界面的融合。

### 2.3 第三方库

| 库 | 用途 | 许可 |
|---|---|---|
| AndroidX Core / Lifecycle / Activity | 基础组件 | Apache-2.0 |
| Jetpack Compose（BOM 2024.12.01）+ Material 3 | 全部 UI | Apache-2.0 |
| Kotlin Coroutines | 异步与并发 | Apache-2.0 |
| Kotlinx Serialization | 配置序列化 | Apache-2.0 |

**未使用**：OkHttp、Retrofit、任何统计/广告 SDK、任何需要额外权限的库。
Shizuku 也未作为依赖引入——`ShizukuEnhancer` 用纯反射调用，缺失时安全降级为 no-op。

---

## 三、用户提供的信息

| 项 | 内容 |
|---|---|
| 目标耳机 | **YINPAGE 音贝奇 Feel 1 Pro** |
| 目标仓库 | https://github.com/6ewjso6-gif/YINPAGE-LINK |
| 参考项目 | https://github.com/KiriChen-Wind/HyperOriG |
| 形态决策 | **明确要求不要 root**，改为工作区内的独立 App |
| 可选增强 | 允许使用 Shizuku（已实现为可选，非强依赖） |
| 仓库 README 初稿 | "为音贝奇适配小米澎湃os的融合中心"（作为项目定位输入） |

---

## 四、工程环境说明（可复现性）

本机为 **2 核 / 5.6 GB 内存 / Windows 11** 的普通工作站，无云资源，因此：

| 组件 | 来源 |
|---|---|
| JDK 17 | 华为云镜像（`mirrors.huaweicloud.com/openjdk`） |
| Gradle 8.9 | 腾讯云镜像（`mirrors.cloud.tencent.com/gradle`） |
| Android SDK cmdline-tools | `dl.google.com` |
| `platforms;android-35` / `build-tools;35.0.0` | 经 sdkmanager 安装（预置 license 文件以跳过交互） |
| Gradle 依赖 | 阿里云镜像优先，官方源兜底 |

遇到的坑（对后来者有参考价值）：

1. **`GRADLE_USER_HOME` 必须指向工作区内**——默认的用户目录受沙箱限制，
   会导致 `Could not initialize native services / native-platform.dll` 初始化失败。
2. **`sdkmanager --licenses` 是交互式的**，在非交互 shell 里会卡死；
   改成预写 `licenses/*` 文件绕过。
3. **清华镜像 TLS 会报 `CRYPT_E_REVOCATION_OFFLINE`**（吊销列表检查离线），
   改用华为云镜像。
