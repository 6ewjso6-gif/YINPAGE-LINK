# 交付总结

> YINPAGE-LINK v1.0.0 · 2026-10-03
> 仓库：https://github.com/6ewjso6-gif/YINPAGE-LINK
> Release：https://github.com/6ewjso6-gif/YINPAGE-LINK/releases/tag/v1.0.0

---

## 一、交付物清单

| # | 交付物 | 位置 | 状态 |
|---|---|---|---|
| 1 | Android 工程源码 | 仓库 `main` 分支，55 个文件 / 342 KB | ✅ 已推送 |
| 2 | 可安装 APK | [Release v1.0.0](https://github.com/6ewjso6-gif/YINPAGE-LINK/releases/tag/v1.0.0)，16.12 MB | ✅ 已上传 |
| 3 | README | [README.md](../README.md) | ✅ |
| 4 | 协议逆向文档 | [doc/PROTOCOL.md](PROTOCOL.md) | ✅ |
| 5 | 开发说明（自主/引用/用户提供） | [doc/DEVELOPMENT.md](DEVELOPMENT.md) | ✅ |
| 6 | 构建与验证记录 | [doc/BUILD-AND-VERIFICATION.md](BUILD-AND-VERIFICATION.md) | ✅ |
| 7 | MIT 许可证 | [LICENSE](../LICENSE) | ✅ |

**APK 校验值**

```
文件名   YINPAGE-LINK-v1.0.0-debug.apk
大小     16.12 MB (16,903,739 bytes)
SHA256   B0F0DB6D7CA7B9583FBF234284E94F1FB320094403AC7E5ABF3D004093A551EF
包名     com.yinpage.link
```

## 二、工程规模

| 项 | 数量 |
|---|---|
| Kotlin 源文件 | 32 个 |
| Kotlin 代码行数 | 6,430 行 |
| 资源文件 | 13 个（含 127 条中文文案） |
| 文档 | 3 篇 + README |
| 逆向中间产物 | `_rev/`（未入库，含官方 APK 与 157 个类 dump） |

## 三、自主开发 / 引用 / 用户提供

### 3.1 自主开发

**架构与契约层**（全部自主设计）

- `protocol/State.kt` — 数据模型，采用 **delta 增量更新**（因为耳机常只上报单耳电量）
- `protocol/Transport.kt` — `PodTransport` / `PodCodec` / `ProtocolRegistry` 三层抽象
- `core/Contracts.kt`、`core/AppState.kt`、`core/Models.kt`、`core/EventLog.kt`
- `config/ConfigManager.kt`

**协议逆向**（自主完成）

- 获取官方 APK `com.yscoco.yinpage` v1.4.27（MD5 `D31F2892…`）
- 无 jadx 环境下自写 `DexScan.java` 提取 DEX 字符串池
- **确证**耳机为**中科蓝讯 Bluetrum AB 系**（157 个类）
- **确证**帧格式：`[seq][command][type][chunk][len][payload]`，5 字节头，无 CRC
- 导出 **24 条请求命令 + 33 条设备信息子码**完整表
- 确证电量布局（`bit7=充电中` / `bit0..6=电量`）与 ANC 三档取值

**协议实现**（先推测、后按确证重写）

- 第一阶段实现了参数化的 `FrameFormat`（4 个候选变体）让全链路先跑通
- 反编译出结果后**删除推测实现**，按确证结论重写 `protocol/bluetrum/`（3 个文件）
- UI / 传输 / 会话三层**一行未改**——这正是分层设计的收益

**传输层与应用层**

- `transport/RfcommTransport.kt`（SPP 多 UUID 候选遍历）
- `transport/BleGattTransport.kt`（GATT 服务树枚举 + 特征值打分 + MTU 分片）
- `core/SessionCoordinator.kt`（AUTO 通道回退、握手、异常翻译、超时看门狗）
- `service/` 前台服务与广播接收器、`enhance/ShizukuEnhancer.kt`（纯反射零依赖）

### 3.2 引用的开源项目

**架构设计参考**（仅借鉴思路，未复制代码）

| 项目 | 借鉴点 |
|---|---|
| [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) | **主要参考对象**：分层方式、`ConfigManager` 组织、反射工具思路 |
| [OppoPods](https://github.com/1812z/OppoPods) / [OppoPods-Enhanced](https://github.com/Leaf-lsgtky/OppoPods) | RFCOMM 控制器与 SPP UUID 候选策略 |
| [HyperPods](https://github.com/Art-Chen/HyperPods) | 第三方耳机接入的形态 |
| [PuddingPods](https://github.com/Xposed-Modules-Repo/rongyi.puddingpods) | SPP 接入细节与作用域清单 |

**第三方库**：AndroidX Core/Lifecycle/Activity、Jetpack Compose + Material 3、Kotlin Coroutines、Kotlinx Serialization（全部 Apache-2.0）。

**未使用**：OkHttp、Retrofit、统计/广告 SDK、Shizuku 依赖。

### 3.3 用户提供的信息

| 项 | 内容 |
|---|---|
| 目标耳机 | YINPAGE 音贝奇 **Feel 1 Pro** |
| 目标仓库 | https://github.com/6ewjso6-gif/YINPAGE-LINK |
| 参考项目 | https://github.com/KiriChen-Wind/HyperOriG |
| **形态决策** | **明确要求不要 root**（因此从 LSPosed 模块改为独立 App） |
| 可选增强 | 允许使用 Shizuku（实现为可选，非强依赖） |
| 仓库初稿 | README 标题「为音贝奇适配小米澎湃os的融合中心」 |
| 推送凭据 | GitHub Personal Access Token |

### 3.4 与参考项目的根本差异（诚实说明）

HyperOriG 等**全部是 LSPosed 模块**，需要 root，做法是往系统进程注入代码，
把第三方耳机塞进系统耳机卡片。

**本项目不做任何注入**：

| 维度 | HyperOriG 等 | 本项目 |
|---|---|---|
| 运行形态 | LSPosed 模块 | 独立 App |
| 权限 | root + LSPosed | 仅官方蓝牙运行时权限 |
| 设置页耳机卡片 | ✅ | ❌ **物理上不可能**（不 root 就无法注入系统 UI） |
| 控制能力 | ✅ | ✅ 等价（本项目主功能） |
| 系统升级风险 | 高（依赖系统内部类名） | 低（只用公开 API） |

## 四、验证证据

### 4.1 已实际验证 ✅

```
> Task :app:compileDebugKotlin
BUILD SUCCESSFUL in 9m 52s

> Task :app:packageDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 2m 25s
37 actionable tasks: 5 executed, 32 up-to-date

APK: app/build/outputs/apk/debug/app-debug.apk  16.12MB
```

- 全部 32 个 Kotlin 文件编译通过（仅 2 条图标弃用警告）
- 资源与清单通过 aapt2 链接
- APK 成功打包并签名
- GitHub Release 上传成功，下载直链返回 `HTTP 200`

### 4.2 未验证 ⚠️（诚实声明）

| 项 | 原因 |
|---|---|
| 真机连接与协议收发 | 开发环境**无 Android 设备、无 adb**，无法联调 |
| UI 实际渲染效果 | 同上 |
| RFCOMM channel / 自定义 SPP UUID | 需真机 SDP 查询或抓包 |

协议结论来自**静态字节码分析**（每个结论都有 `code_off` 级证据），
但**未经真机抓包复核**。若耳机不响应，排查顺序见
[doc/BUILD-AND-VERIFICATION.md](BUILD-AND-VERIFICATION.md) 第 4.2 节。

## 五、后续建议

1. **真机验证**：安装 APK → 系统蓝牙配对耳机 → App 内连接 → 看调试面板是否有 RX 数据
2. **抓包复核**（推荐）：开启 HCI snoop，用官方 App 操作一轮，对照 `doc/PROTOCOL.md` 第 2 节
3. **补充 gradle wrapper**：仓库未内置 `gradlew`（本机无 Gradle 安装无法生成），
   在装有 Gradle 的机器上执行一次 `gradle wrapper` 即可补全；当前可直接用 `build.sh`
   或 Android Studio 构建
4. **可选扩展**：查找耳机、关机、自定义按键、EQ 十段增益（命令码已确证，可直接实现）
