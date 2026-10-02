# 澎湃 OS 融合设备中心接入方案

> 让 **YINPAGE 音贝奇 Feel 1 Pro** 出现在小米澎湃 OS 的蓝牙设置页与控制中心耳机卡片里。
>
> 本文的结论分两类，全文严格标注：
> - ✅ **已确证**：读到参考项目源码 / 反编译官方 App 得到，附来源
> - ⚠️ **待真机验证**：静态分析得出但本机无 HyperOS 设备，未实测

---

## 0. 三个必须先纠正的认知

### 0.1 系统**不读**蓝牙数据库

HyperOriG / OppoPods 全程**没有修改任何蓝牙配置文件**（`bt_config.conf` / `config.xml`）。
所谓"接入"完全是在 5 个系统进程里，把系统运行时 API 的返回值伪造成
"这是一只小米高端 TWS"。证据：`MiLinkServiceHook.kt` 全文纯 `hookAfter` 返回值改写，零文件写入。

**这对我们很有利**：不需要 root 去改系统文件，风险面小得多。

### 0.2 `01010607` 不是某个真实型号 ID

它是 HyperOS 侧一个**通用的「高级耳机能力兼容位」**。PuddingPods 适配文档原文：

> `01010607` 仅用于 HyperOS 高级耳机能力兼容，不代表真实水月雨型号 ID。

水月雨、原道、OPPO 三家不同品牌耳机**都用同一个值**。
→ 音贝奇直接复用，无需自己去找型号 ID。

### 0.3 "受支持"的判据是一条 24 字符能力串

```kotlin
// HyperOriG config/ConfigManager.kt（逐字照抄）
const val DEFAULT_FAKE_DEVICE_ID = "01010607"
fun fakeSupport(): String = "${fakeDeviceId()},000000000000000010000000"
```

格式：`<8位设备ID>,<24位十六进制能力位掩码>`。
第二个字段三家共用，说明它是"标准小米 TWS 全功能集"的固定值。
⚠️ 具体位含义未确证，**照抄常量最安全**，不要自己改。

---

## 1. 系统侧识别链路

```
A2DP 连接事件
   ↓
蓝牙进程内的 MiuiHeadset Binder 服务被跨进程查询
   ↓  checkSupport(device) → "01010607,0000...10000000"   ← 支持性判定的唯一真相来源
MiLink 运行时把设备登记成 MiTWS
   ↓  getDeviceId / isMiTWS / checkIsMiTWS
Settings / SystemUI 渲染卡片与四档控件
   ↓  MiuiHeadsetActivity / MiuiHeadsetFragment / DeviceInfoWrapper
状态用回调反向推入
   ↓  callback.refreshStatus(address, 16字段payload)
```

**关键认识**：判定发生在**三个不同进程**，各自有独立函数，必须分别 Hook
（`com.android.bluetooth` / `com.android.settings` / `com.milink.service`）。

---

## 2. 已实现的 Hook 点

本项目在 `module/` 下实现了 7 组 Hook，作用域与参考项目逐条一致：

```
com.android.bluetooth      ← 核心：身份伪装 + SPP 通信 + 状态推送
com.milink.service         ← 设备中心 / 电量 / ANC 三态
com.android.settings       ← 蓝牙设置页高级耳机界面
com.android.systemui       ← 设备中心卡片
com.xiaomi.bluetooth       ← 连接通知（复用 MiLink Hook）
```

| 作用域 | Hook 点 | 我们的行为 | 实现文件 |
|---|---|---|---|
| bluetooth | `BluetoothHeadsetService.onBind/onCreate` | 拿到 Binder 真实类名（不写死混淆名） | `BluetoothUpstreamHeadsetHook` |
| bluetooth | `BinderC6776v`/`v`.`checkSupport(BluetoothDevice)` | 返回 `fakeSupport()` ← **总开关** | 同上 |
| bluetooth | `getDeviceInfo(String)` / 无参变体 | 返回 `fakeSupport()` | 同上 |
| bluetooth | `isSupportAudioSwitch`/`mo19775z1`/`z1` | `"1"` | 同上 |
| bluetooth | `isMiTWS`/`mo19771O0`/`O0` | `true` | 同上 |
| bluetooth | `checkIsMiTWS`/`mo19766B`/`B` | `true` | 同上 |
| bluetooth | `getRingFindState`/`mo19772m0`/`m0` | `false` | 同上 |
| bluetooth | `setCommonCommand(Int,String,BluetoothDevice)` | `123→"4"`、其余 `"1"` | 同上 |
| bluetooth | `connect`/`getDeviceConfig`/`getCommonConfig` | 吞掉 + 触发状态刷新 | 同上 |
| bluetooth | `changeAncMode(Int,BluetoothDevice)` | 解析模式码 → 转 SPP 命令 | 同上 |
| bluetooth | `changeAncLevel(String,BluetoothDevice)` | 解析 MIUI level 码 → 转 SPP | 同上 |
| bluetooth | `register`/`registerCallbackDevice`/`unregister` | 缓存回调、不真注册、主动补推状态 | 同上 |
| bluetooth | `A2dpService.handleConnectionStateChanged` | 连接→拉起 SPP；断开→关闭 SPP | 同上 |
| milink | `MxBluetoothService`/`MxBluetoothManager` 15 个方法 | 返回值伪造 | `MiLinkServiceHook` |
| milink | `AncBatteryController.setAncStateBlock` | **双向核心**：转 SPP + 回灌 + 通知 UI | 同上 |
| milink | `AncBatteryController.getBatteryLevelCache` | 返回 6 元素电量表 | 同上 |
| milink | `HeadsetInfo.getDeviceId/getPowers/getMode` (+`component3/4/5`) | 返回值伪造 | 同上 |
| settings | `HeadsetIDConstants.checkSupport` | **返回 `true`**（放行系统页） | `SettingsHeadsetHook` |
| settings | `MiuiHeadsetActivity(.Plugin).onCreate` | 打 Intent 补丁 | 同上 |
| settings | `IMiuiHeadsetService$Stub$Proxy` 12 个方法 | 跨进程代理再拦一遍 | 同上 |
| settings | `MiuiHeadsetFragment.refreshStatus` | 吞掉 `MMA_CONNECTION_FAILED` | 同上 |
| systemui | `PluginFactory.createPluginContext` | 拿 `miui.systemui.plugin` 的 ClassLoader | `SystemUIHook` |
| systemui | `DeviceInfoWrapper.performClicked` | 识别 `third_headset` 卡片 | 同上 |

### 2.1 为什么必须区分 `(方法名, 参数类型)`

AB 系与 MIUI 存在**同码跨方向复用**（例如 `0x2C` 既是 ANC 设置也是 ANC 查询），
系统侧同样有 `isMiTWS(String)` 与 `checkIsMiTWS(BluetoothDevice)` 这种同义不同签名的成对方法。
因此本项目所有 Hook 都按"多候选方法名 + 精确参数类型"安装，任一不存在则静默跳过。

---

## 3. 数据通路

### 3.1 电量：三套不同的编码，绝不能混用

| 消费方 | 编码 | 说明 |
|---|---|---|
| MiLink (`getBatteryLevelCache`) | `List[6] = [盒, 左, 右, 盒充电, 左充电, 右充电]`，**未连接 = -1** | 顺序已确证 |
| Settings (16 字段 payload) | **未连接 = `"255"`，充电 = `值 or 128`** | 已确证 |
| Settings (`MiuiHeadsetBattery`) | 同上，另有 `onBatteryChanged(int,int,int)` 重载 | 已确证 |

本项目在 `ModuleIpc.PodSnapshot` 里用统一的"内部表示"（`UNKNOWN_LEVEL = -1`），
由各消费方按需翻译，避免把三套编码搞混。

### 3.2 系统 UI 真正读的是这 16 个字段

```kotlin
// 格式已确证，两个参考项目逐字一致
values[0] = 左耳电量      // "255" 表示未连接；充电则 值 or 128
values[1] = 右耳电量
values[2] = 充电盒电量
values[7] = ANC 档位码    // "0000"/"0100"/"0101"/"0102"/"0103"/"0200"/"0201"
values[8] = "true"
values[11] = "00"
values[13] = "00"
values[14] = "00"
// 其余为空串，逗号连接
```

### 3.3 MIUI level 码表（"四档"的真正定义）

```
0103 = Smart（自适应）      0101 = Light（轻度）
0100 = Medium（中度）       0102 = Deep（深度）
0200 = Transparency（通透） 0201 = 通透 + 人声增强
0000 = OFF
```

**系统 UI 显示几档，由模块声明几个码决定**：
```kotlin
callMethod(fragment, "updateAtUiInfo", "ANC码|0100;0101;0102;0103;0200;0201|电量|00")
```
→ 音贝奇 Feel 1 Pro 走"复用系统原生页"路线，所以四档 ANC UI 是**免费获得**的。

### 3.4 耳机侧映射（本项目实际实现）

中科蓝讯 AB 系确证只有三档 ANC：`0x00` OFF / `0x01` ON / `0x02` TRANSPARENCY。

```
MIUI 0100/0101/0102/0103 (四档降噪)  ──→  AB 系 ANC = 0x01 (ON)
MIUI 0200/0201           (通透)      ──→  AB 系 ANC = 0x02 (TRANSPARENCY)
MIUI 0000                (关闭)      ──→  AB 系 ANC = 0x00 (OFF)
```

反向（耳机上报 → MIUI）：
```
0x00 → "0000"    0x02 → "0200"    0x01 → "0100"
```

> 需要区分降噪强度时，可用已确证的 `ANC_GAIN(0x30)` 命令调节增益。

---

## 4. 设备身份伪装

```kotlin
// module/src/main/java/com/yinpage/link/module/ModuleConfig.kt
fun supportString(): String = "$fakeDeviceId,$SUPPORT_CAPABILITY"
// fakeDeviceId 默认 "01010607"
// SUPPORT_CAPABILITY = "000000000000000010000000"
```

**接管范围控制**（本项目新增，参考项目没有）：
`ModuleConfig.shouldHookDevice(name, address)` 的规则：

1. 配置了 `targetAddress` → 只接管该地址（精确匹配，忽略大小写）
2. 未配置 → 接管"名称不含小米自家关键词"的设备，**避免把用户的小米耳机也伪装掉**
3. 名称为空 → 不接管（避免误伤）

小米关键词白名单：`Xiaomi` / `Redmi` / `Mi Buds` / `小米` / `红米` / `Air2` … 等。

---

## 5. HyperOS 3 与 4 的差异

| 项 | HyperOS 3 | HyperOS 4 | 本项目的处理 |
|---|---|---|---|
| LSPosed API | 101（`onPackageLoaded`，`defaultClassLoader`） | 102（`onPackageReady`，`classLoader`） | **两个回调都实现** |
| Binder 实现类 | `...headset.v` | `...headset.BinderC6776v` | 两个候选名都试 |
| 方法混淆名 | `isMiTWS` 等本名 | `mo19771O0` / `O0` 等别名 | 多候选名依次探测 |
| SystemUI 插件 | `loadPlugin` 取 ClassLoader | `PluginFactory.createPluginContext` 时机更早 | 优先 OS4 路径 + 回退 |
| 通知内部类字段 | JADX 别名 `f18107b…` | R8 短名 `b/c/d/e` | 多候选字段名 + 类型校验 |
| SystemUI 耳机图标 | 已有 | `CentralSurfacesImpl.start()` 会**隐藏** `wireless_headset` | ⚠️ 未实现（见 §7） |

**作用域热重载限制**：这 5 个进程是系统常驻进程，
LSPosed 的"作用域变更"对已加载的 Hook **不生效**，必须重启这 5 个进程。

---

## 6. 本项目的实现选择

### 6.1 与参考项目的路线差异

| 决策 | HyperOriG | 本项目 | 理由 |
|---|---|---|---|
| 设置页 UI | 自绘 PopupActivity，**阻止**系统页打开 | **复用**系统原生高级耳机页 | Feel 1 Pro 有四档 ANC，系统页白拿四档 UI |
| ANC 档位 | 3 档（自家码 1..6） | 系统四档 → 耳机三档映射 | 尊重耳机真实能力 |
| 灵动岛 | 自建 + 官方模式改写 | ⚠️ 暂未实现 | 见 §7 待办 |
| 设备接管范围 | 按名称关键字 | 支持精确 MAC + 小米白名单排除 | 避免误伤用户其它耳机 |

### 6.2 代码结构

```
module/src/main/java/com/yinpage/link/module/
├── YinpageModule.kt              入口：按包名分派到各 Hook
├── ModuleConfig.kt               配置（remote preferences 跨进程共享）
├── ModuleActivity.kt             配置界面（纯 View，无 Compose 依赖）
├── ModuleStatusProvider.kt       供独立 App 查询模块是否生效
├── hook/
│   ├── HookContext.kt            反射工具层（所有查找可空 + 静默降级）
│   ├── BluetoothUpstreamHeadsetHook.kt   ★ P0 核心
│   ├── MiLinkServiceHook.kt              设备中心 / 电量 / ANC
│   ├── SettingsHeadsetHook.kt            设置页
│   └── SystemUIHook.kt                   设备中心卡片
├── ipc/ModuleIpc.kt              跨进程广播协议 + 状态快照
└── pods/RfcommController.kt      SPP 控制器（复用共享协议层）
```

协议层通过 `sourceSets` 直接引用 `app/src/main/java/com/yinpage/link/protocol`，
**与独立应用共享同一份实现**，不复制文件。

---

## 7. 诚实边界：未实现与未验证

### 7.1 ⚠️ 未实现的功能

| 功能 | 状态 | 说明 |
|---|---|---|
| 灵动岛 / 焦点通知 | **未实现** | 需要 `com.xzakota.hyper.notification:focus-api`，已声明依赖但未写构建代码 |
| 状态栏耳机图标 | **未实现** | 需 Hook `CentralSurfacesImpl.start()` 接管 `wireless_headset` 槽位 |
| 佩戴状态 | 降级 | 恒返回 `"0,0"`（参考项目同款取舍） |
| 查找耳机 / 关机 / 按键自定义 | 未实现 | 协议命令码已确证，可随时补 |

### 7.2 ⚠️ 本机无法验证的部分（最重要）

**本机没有 HyperOS 真机，也没有 root 环境**，因此：

- ❌ 没有任何一个 Hook 在真机上跑过
- ❌ 所有类名/方法名来自**静态分析**参考项目与官方文档，**未在你的设备上核对**
- ❌ 无法确认 `com.android.bluetooth` 里 SPP 通道能否与耳机建立
- ❌ 无法验证设备中心卡片是否真的出现

**协议层的可信度更高一些**：帧格式、命令码、电量布局都有官方 App 反编译的
`code_off` 级证据（见 [PROTOCOL.md](PROTOCOL.md)），但仍未经真机抓包复核。

### 7.3 真机验证步骤（请按顺序做）

1. **前置**：手机已 root（Magisk / KernelSU），已装 LSPosed，系统为 HyperOS 3 或 4
2. **安装**：装上 `YINPAGE-LINK-module` APK，在 LSPosed 里勾选模块，
   作用域勾选那 5 个包
3. **重启作用域**：手动结束这 5 个进程（或重启手机）
   ```bash
   su -c 'killall com.android.bluetooth com.milink.service com.android.settings com.android.systemui com.xiaomi.bluetooth'
   ```
4. **配对耳机**：先系统蓝牙配对 Feel 1 Pro
5. **观察日志**：
   ```bash
   adb logcat -s YINPAGE-LINK:V
   ```
   应该看到 `模块加载：pkg=com.android.bluetooth` 以及后续 Hook 安装日志
6. **验证卡片**：设置 → 蓝牙 → 点耳机 → 看是否出现"高级耳机设置"入口
7. **验证 ANC**：在卡片上切四档，看耳机是否响应（用官方 App 交叉确认）

### 7.4 故障排查建议

| 现象 | 可能原因 | 排查 |
|---|---|---|
| 日志里完全没有 `YINPAGE-LINK` | 模块未激活 / 作用域没勾 / 进程没重启 | 检查 LSPosed 作用域，重启进程 |
| 有日志但 `未找到 BluetoothHeadsetService.onBind` | 你的 HyperOS 版本类名不同 | 用 `adb shell dumpsys` 或 jadx 核对系统 APK，把新类名加进候选列表 |
| 卡片出现但点不动 | Settings 侧 Hook 未生效 | 看 `com.android.settings` 进程的日志 |
| 电量不动 | SPP 通道没建立 | 看 `com.android.bluetooth` 进程的 `Rfcomm` 日志 |
| 四档点了没反应 | ANC 映射或命令码不对 | 开调试日志看 `ANC 已切换` 与 SPP 发送记录 |

---

## 8. 参考项目与来源

| 项目 | 借鉴了什么 |
|---|---|
| [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) | 主要模板：作用域、Hook 点、`fakeSupport()` 机制、HyperOS 4 适配细节 |
| [OppoPods](https://github.com/1812z/OppoPods) | 「复用系统原生耳机页」路线、`IMiuiHeadsetService` transaction code 表、MMA 失败吞掉 |
| [PuddingPods](https://github.com/Xposed-Modules-Repo/rongyi.puddingpods) | `01010607` 含义的权威说明、通知通道时序经验 |
| [HyperPods](https://github.com/Art-Chen/HyperPods) | 整体形态交叉验证 |

**本项目只借鉴设计思路与已确证的常量，未复制代码。**
所有实现均为重写，并针对音贝奇耳机的三档 ANC 真实能力做了映射适配与设备接管范围收紧。
