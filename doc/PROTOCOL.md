# 耳机协议逆向记录（Feel 1 Pro）

> 本文档记录 **YINPAGE 音贝奇 Feel 1 Pro** 私有协议的逆向过程与结论。
> **严格区分「已确证」与「待验证」**——这是本项目的核心工程纪律：
> 协议写错会让耳机不响应甚至进入异常状态，宁可标注不确定，也不能编造字节。

---

## 0. 背景与难点

| 项 | 情况 |
|---|---|
| 官方 App | `com.yscoco.yinpage`，v1.4.24（小米应用商店 / 应用宝在架） |
| 公开资料 | **无任何抓包、文档或开源实现** |
| FCC 资料 | `2BNT7-REAL1PRO` 等档案的 Block Diagram / Schematics / Operational Description **申请了长期保密** |
| CMIIT 核准 | 只列调制方式，不含芯片型号 |
| 结论 | 必须自己从官方 App 逆向 |

## 1. 已确证结论

逆向对象：官方 APK（132.8 MB）解包后的 `classes.dex` / `classes2.dex` 字符串池。

### 1.1 传输通道：BLE GATT（已修订，⚠️ 旧"SPP"结论被真机推翻）

**2026-10-05 重大修订：控制通道其实是 BLE GATT，不是 SPP。**

旧结论（基于官方 APK 字符串池）认为走 SPP UUID `00001101`，但真机实测：
SPP 能连上、握手帧能发出，**但耳机不响应任何控制命令**，且 SPP 上收到的数据
（如 `01 AD 93 47 CA F7 ...` 17B）无法按 AB 帧解析。

对照官方 **ABMate iOS SDK 源码**（`ABMate/Models/ABEarbuds.swift` + `Utils/BLEConstants.swift`）确证：

| 项 | 值 |
|---|---|
| 传输 | **BLE GATT**（CoreBluetooth `CBPeripheral`） |
| 服务 | `FDB3`（`0000FDB3-0000-1000-8000-00805F9B34FB`） |
| 写特征 | `FF17`（`0000FF17-...`） |
| 读/通知特征 | `FF18`（`0000FF18-...`） |
| 帧格式 | 仍是 5 字节头 + payload（无加密） |

要点：
- **控制协议跑在 BLE GATT 上**；SPP 只是设备附带的串口（连得上≠控制通道）。
- 设备地址若为 **BLE 随机静态地址**（首字节最高两位 `01`，如 `7A:...`），
  几乎必然以 BLE 为主。
- 本项目 v0.11 起（撤销 v0.10 的地址启发）：**AUTO 一律 SPP 优先、BLE 兜底**。
  原因：真机（YINPAGE Relink `7A:70:96:4E:48:14`）实测 BLE GATT 枚举 10s 超时未完成，
  而 SPP 通道能稳定建连——地址类型只是启发，BLE 优先只会白耗 15~20s 超时。

**v0.11 新增「握手活性看门狗」（连上≠控制通道，这是本次修复的核心）：**
- 设备常暴露多个 RFCOMM 服务：标准串口 `00001101` 可能只是**哑通道**
  （连得上、但收到的是串口垃圾或干脆静默，不处理协议帧）。
- SPP 连接候选**顺序即优先级**：`0000A100-...CKCTRL`（厂商自定义，UUID 尾部
  `434B4354524C` = ASCII "CKCTRL"，module 版与诊断均列为候选）→ `00001101` → `00001102` …
- 通道建立 + 握手帧（6 帧查询）发出后，4 秒内**一个字节都没收到** → 判定哑通道，
  自动断开并尝试下一个候选（下个 UUID / BLE 兜底）。设备回任何数据（哪怕加密无法
  解析）都视为通道正确，避免在真通道上误换。

旧证据（保留备查）：

```
00001101-0000-1000-8000-00805F9B34FB      ← 标准 Serial Port Profile UUID
00001101-0000-1000-8000-00805f9b34fb
createRfcommSocketToServiceRecord          ← 安全通道
createInsecureRfcommSocketToServiceRecord  ← 非安全通道
```

结论（旧）：耳机走**标准 SPP UUID 的 RFCOMM 通道**……（该结论已被上方"BLE GATT"修订推翻；
APK 里出现 SPP 类只说明 SDK 同时内置 SPP 实现，真机控制通道实测是 BLE GATT。）

### 1.2 耳机主控方案：中科蓝讯 Bluetrum AB 系列 ✅

证据（`classes2.dex` 字符串池中的类描述符）：

```
Lcom/bluetrum/devicemanager/DeviceCommManager;
Lcom/bluetrum/devicemanager/DefaultDeviceCommManager;
Lcom/bluetrum/devicemanager/bluetooth/BluetoothSppService;      ← SPP 服务实现
Lcom/bluetrum/devicemanager/models/ABDevice;
Lcom/bluetrum/devicemanager/models/DevicePower;
Lcom/bluetrum/devicemanager/models/DeviceComponentPower;
Lcom/bluetrum/devicemanager/cmd/Request;
Lcom/bluetrum/abaudiotool/AudioTool;
```

这是**中科蓝讯官方 `devicemanager` SDK**，不是厂商自研协议栈。
意义：指令集有明确的工程边界，可参照该 SDK 的公开形态来组织实现。

> 同一个 APK 里还打包了 `com.jieli.jl_bt_ota`（杰理 RCSP）与 `com.jieli.bluetooth`，
> 说明 App 同时兼容杰理方案。**但带完整 ANC / EQ / 电量语义的是 bluetrum 这套**，
> 本项目按 AB 系实现。

### 1.3 指令家族（SDK 类名即语义）✅

`classes2.dex` 中 `com.bluetrum.devicemanager.cmd.request.*` 的完整类名列表：

| 类名 | 语义 | 本项目对应能力 |
|---|---|---|
| `AncModeRequest` | 降噪模式 | 🎧 四档 ANC |
| `AncGainRequest` | 降噪深度增益 | 进阶（未实现） |
| `NoiseRequest` | 噪声控制 | 🎧 降噪 |
| `EqRequest` | EQ 均衡器 | 🎚 EQ |
| `BassBoostRequest` | 低音增强 | 可映射到 EQ 档位 |
| `VocalBoostRequest` | 人声增强 | 可映射到 EQ 档位 |
| `SoundEffect3dRequest` | 3D 音效 | 可映射到 EQ 档位 |
| `DynamicAudioRequest` | 动态音频 | 未实现 |
| `WorkModeRequest` | 工作模式 | — |
| `DualDeviceRequest` | 双设备连接 | 🔗 双连 |
| `LDACRequest` | LDAC 编解码 | 状态展示 |
| `VolumeGearRequest` | 音量档位 | 未实现 |
| `DeviceInfoRequest` | 设备信息（固件/型号） | 固件版本展示 |
| `FindDeviceRequest` | 查找耳机 | 未实现 |
| `ShutdownDeviceRequest` | 关机 | 未实现 |
| `FactoryResetRequest` | 恢复出厂 | 未实现 |
| `MusicControlRequest` | 媒体控制 | 未实现 |
| `KeyRequest` / `CloseKeyRequest` / `ResetKeyRequest` / `CallStatusKeyRequest` | 按键自定义 | 未实现 |

响应与载荷处理器：

```
cmd/payloadhandler/KeyPayloadHandler
cmd/payloadhandler/TlvResponsePayloadHandler     ← TLV 结构！
cmd/payloadhandler/MtuPayloadHandler
cmd/payloadhandler/BooleanPayloadHandler
```

> `TlvResponsePayloadHandler` 的存在说明**响应载荷是 TLV（Type-Length-Value）编码**，
> 这解释了为什么电量、ANC 等不同字段可以复用同一个响应包。

### 1.4 状态量语义 ✅

证据（调试字符串与数据模型）：

```
ancMode =                dealAncLevel 
notifyBatteryInfo1 =     notifyBatteryInfo2 =
BatteryInfo{leftBattery= , rightBattery= , caseBattery= }
ANC_SWITCH   AUTO_ANC   LIGHT_ANC   MODERATE_ANC   HEAVY_ANC
GAME_MODE
```

结论：

- 电量有三个维度：`leftBattery` / `rightBattery` / `caseBattery` → 对应本项目的三路电量环 ✅
- ANC 档位枚举：`ANC_SWITCH` / `LIGHT_ANC` / `MODERATE_ANC` / `HEAVY_ANC` (+ `AUTO_ANC`)
  → 与系统四档控件（关闭 / 通透 / 标准 / 深度）能天然对应 ✅
- 存在 `GAME_MODE` → 游戏模式 ✅

## 2. 帧格式（已确证）✅

逆向方式：jadx 反编译官方 APK，把发送路径（`DeviceCommManager.a()@3692332`）与接收路径
（`com.bluetrum.devicemanager.f.b()@3711352`）**逐指令对齐**得出。

> ✅ 2026-10-05 追加确证：与官方 **ABMate iOS SDK 源码**
> （`DeviceManager/DeviceManager/Command/RequestHandler.swift` / `ResponseHandler.swift`）完全一致：
> 发送与接收均为 5 字节头 `[seq][cmd][cmdType][chunk][len]` + payload，**无任何加密**；
> 分片时**每个物理帧的 seq 都递增**（每条消息按帧数推进），接收端按 `seq` 从 0 递增严格校验。

```
偏移   字段     宽度   说明
----   -------  ----   --------------------------------------------------
 [0]   seq      1B     bits0-3 = 发送序号 0..15，发送后 (x+1) and 0x0F，初值 0
 [1]   command  1B     命令码（见 §3）
 [2]   type     1B     1 = 请求(REQ) / 2 = 响应(RESP) / 3 = 通知(NOTIFY)
 [3]   chunk    1B     高 4 位 = (总包数 - 1)；低 4 位 = 当前包序号
 [4]   len      1B     本包 payload 长度（0..255）
 [5..] payload  len B   载荷
```

- **整帧长度 = 5 + len**
- **无 CRC、无校验、无转义**（全 bluetrum 反汇编中没有任何 CRC 调用，仅 OTA 状态码用到）
- **分包**：默认每包 payload 15 字节（类 `e.a` 初值 `const/16 #15`），
  可由 `INFO_MAX_PACKET_SIZE(0xFF)` 查询结果覆盖
- **序号按"消息"递增而非按帧**：一条消息拆成 N 个物理帧时共用同一序号
- 接收端校验：`Frame seq mismatch: Expected … but got …`、`The length of payload mismatch: Expected …`、
  `The length of received data is too short…`（< 5 字节直接丢弃）

> 与首次推测的差异：最初按同类白牌方案推测为「`0x4E` 魔数 + 小端长度」，
> **该推测是错的**——AB 系帧头没有魔数，而是 `seq/command/type/chunk/len` 五段式。
> 本项目已按确证格式重写协议层（`protocol/bluetrum/`），旧的推测实现已删除。

## 3. 指令表（已确证）✅

来源：`com.bluetrum.devicemanager.cmd.Command` 类 `static_values` 全量导出。

### 3.1 请求命令（type = 1）

| 命令码 | 常量 | 语义 | payload |
|---|---|---|---|
| `0x20` | `EQ` | EQ 设置 | `[bandCount, mode, gains…]`（2 参构造器固定 10 段） |
| `0x21` | `MUSIC_CONTROL` | 媒体控制 | 见 §3.2 |
| `0x22` | `KEY` | 按键自定义 | key 值 |
| `0x23` | `AUTO_SHUTDOWN` | 自动关机 | 档位 |
| `0x24` | `FACTORY_RESET` | 恢复出厂 | 空 |
| `0x25` | `WORK_MODE` | 工作模式 | `[mode]` |
| `0x26` | `IN_EAR_DETECT` | 入耳检测开关 | `[0\|1]` |
| `0x27` | `DEVICE_INFO` | **设备信息查询** | `[INFO_x, 0x00]`（可重复拼接） |
| `0x2A` | `FIND_DEVICE` | 查找耳机 | 空 |
| `0x2B` | `AUTO_ANSWER` | 自动接听 | `[0\|1]` |
| `0x2C` | `ANC_MODE` | **ANC 模式设置** | `[ancMode]` |
| `0x2D` | `BLUETOOTH_NAME` | 蓝牙名 | 字符串 |
| `0x2E` | `LED_MODE` | LED 开关 | `[0\|1]` |
| `0x2F` | `CLEAR_PAIR_RECORD` | 清除配对 | 空 |
| `0x30` | `ANC_GAIN` | ANC 增益 | `[gain]` |
| `0x31` | `TRANSPARENCY_GAIN` | 通透增益 | `[gain]` |
| `0x32` | `SOUND_EFFECT_3D` | 3D 音效 | `[mode]` |
| `0x33` | `ONE_DRAG_TWO` | 一拖二 | `[0\|1]` |
| `0x38` | `BASS_BOOST_MODE` | 低音增强 | `[mode]` |
| `0x39` | `VOCAL_BOOST_MODE` | 人声增强 | `[mode]` |
| `0x3C` | `VOLUME_GEAR` | 音量档位 | `[gear]` |
| `0x3D` | `DUAL_DEVICE` | **双设备连接** | `[0\|1]` |
| `0x3F` | `RESET_KEY` | 重置按键 | 空 |
| `0x40` | `CUSTOM` | 自定义 | 透传 |
| `0x41` | `NOISE_REDUCTION` / `SHUTDOWN` | 降噪强度 / 关机 | `[level]` / 空 |

> ⚠️ `0x2B`、`0x2C`、`0x40`、`0x41` **跨方向复用**（同一码在请求/通知方向含义不同），
> 因此路由必须用 `(type, command)` 二元组，不能只看 command。

### 3.2 设备信息子码（`DEVICE_INFO(0x27)` 的 payload[0]）

| 子码 | 常量 | 语义 |
|---|---|---|
| `0x01` | `DEVICE_POWER` | **电量**：响应 payload = `[左, 右, 盒]` |
| `0x02` | `FIRMWARE_VERSION` | 固件版本 |
| `0x03` | `BLUETOOTH_NAME` | 蓝牙名 |
| `0x04` | `EQ_SETTING` | 当前 EQ |
| `0x05` | `KEY_SETTINGS` | 按键设置 |
| `0x06` | `DEVICE_VOLUME` | 音量 |
| `0x07` | `PLAY_STATE` | 播放状态 |
| `0x08` | `WORK_MODE` | 工作模式 |
| `0x09` | `IN_EAR_STATUS` | 入耳状态 |
| `0x0A` | `LANGUAGE_SETTING` | 语言 |
| `0x0B` | `AUTO_ANSWER` | 自动接听 |
| `0x0C` | `ANC_MODE` | 当前 ANC 模式 |
| `0x0D` | `IS_TWS` | 是否 TWS |
| `0x0E` | `TWS_CONNECTED` | 双耳是否已连 |
| `0x0F` | `LED_SWITCH` | LED 开关 |
| `0x10` | `FW_CHECKSUM` | 固件校验 |
| `0x11` | `ANC_GAIN` | ANC 增益 |
| `0x12` | `TRANSPARENCY_GAIN` | 通透增益 |
| `0x13` | `ANC_GAIN_NUM` | ANC 增益档位数 |
| `0x14` | `TRANSPARENCY_GAIN_NUM` | 通透增益档位数 |
| `0x15` | `ALL_EQ_SETTINGS` | 全部 EQ 设置 |
| `0x16` | `MAIN_SIDE` | 主耳侧 |
| `0x17` | `PRODUCT_COLOR` | 产品配色 |
| `0x19` | `ONE_DRAG_TWO` | 一拖二 |
| `0x26` | `BASS_BOOST_MODE` | 低音增强 |
| `0x27` | `VOCAL_BOOST_MODE` | 人声增强 |
| `0x28` | `CALL_STATUS_UI_INFO` | 通话状态 |
| `0x29` | `ONE_CLOSE_KEY` | 一键关闭按键 |
| `0x31` | `DEVICE_SN` | 序列号 |
| `0x32` | `LISTEN_TIP` | 提示音 |
| `0x33` | `VOLUME_LIMIT` | 音量上限 |
| `0xFE` | `DEVICE_CAPABILITIES` | **能力位图** |
| `0xFF` | `MAX_PACKET_SIZE` | 单包最大长度 |

### 3.3 状态字段语义（已确证）✅

**电量**（`DEVICE_POWER` 响应 payload = `[左, 右, 盒]`，每字节）：

```
bit7      = 充电中（1 = 正在充电）
bit0..6   = 电量百分比 0..127
```

**ANC 模式**（`ANC_MODE` payload[0]）：

| 值 | 语义 | 映射到本项目 UI 四档 |
|---|---|---|
| `0x00` | `OFF` 关闭 | 关闭 |
| `0x01` | `ON` 降噪开 | 标准降噪 / 深度降噪 |
| `0x02` | `TRANSPARENCY` 通透 | 通透模式 |

> 耳机只有三档，而系统控件是四档。本项目映射策略：`NORMAL` 与 `DEEP` 都下发 `ON`，
> 需要区分强度时再用 `ANC_GAIN(0x30)` 调整增益。

**能力位图**（`DEVICE_CAPABILITIES` = `0xFE`）：

```
bit0 = TWS
bit1 = 3D 音效
bit2 = 多点连接
bit3 = ANC
```

**EQ**：写入 `[bandCount, mode, gains…]`；`mode >= 0x20` 表示自定义 EQ，
`customIndex = mode and 0xE0`。

## 4. 仍未确证的部分 ⚠️

| 项 | 现状 | 如何确证 |
|---|---|---|
| RFCOMM channel 号 | 未知 | 真机 `sdptool browse <MAC>` 或 SDP 查询 |
| 是否使用自定义 SPP UUID | 未知（BLE 广播里有 `POS_CUSTOM_SPP_UUID` 字段，说明**可能**自定义） | 抓包 / SDP |
| EQ 列表精确字节序 | 单侧证据 | dump `INFO_ALL_EQ_SETTINGS(0x15)` 原始 payload |
| 电量字节 bit7 的边界语义 | 推断为充电中 | 真机对照（充电盒内外各读一次） |
| 入耳状态取值语义 | 推断非零为开 | 真机对照 |

> 这些都不影响主流程：电量、ANC、EQ、双连、入耳开关的核心路径已经确证。

> 参考：中科蓝讯 AB 系（AB53xx/AB56xx 等）在同类产品中的常见帧形态是
> `0xAB` 开头的定长/变长帧，但**没有证据表明 Feel 1 Pro 一定如此**，
> 所以本项目没有硬编码 0xAB，而是把魔数做成可配置项。

## 3. 协议层的工程应对

正因为有大量未确证项，本项目把协议层做成三层可替换结构：

```
FrameFormat（数据类）         ← 魔数/长度字段/命令码宽度/校验方式，全部参数化
      ↓
YscocoFrameCodec             ← 与语义无关的「帧 <-> 字节」+ 流式分帧
      ↓
YscocoCodec（PodCodec 实现）  ← 命令码表 + 状态解析，语义层
```

好处：

1. **新增一个变体只需加一个 `FrameFormat` 数据对象**，不动任何解析逻辑；
2. 调试面板支持运行时切换变体，可以在真机上逐个试；
3. 一旦拿到确证的帧格式，改一处即可全线生效。

`YscocoVariants` 内置的候选：

| 变体 id | 魔数 | 长度 | 命令码 | 校验 |
|---|---|---|---|---|
| `yscoco-4e` | `0x4E` | 2 字节小端（剩余长度） | 2 字节小端 | 无 |
| `yscoco-aa55` | `0xAA 0x55` | 1 字节（整帧） | 1 字节 | 累加和 |
| `yscoco-55` | `0x55` | 1 字节（整帧） | 1 字节 | 无 |
| `yscoco-5a` | `0x5A` | 1 字节（整帧） | 1 字节 | 异或 |

## 4. 下一步确证路线（按性价比排序）

1. **jadx 反编译 bluetrum 包**（最高优先）
   ```bash
   jadx -d out_jadx --deobf yinpage.apk
   # 重点看：
   #   com/bluetrum/devicemanager/bluetooth/BluetoothSppService.java
   #   com/bluetrum/devicemanager/cmd/Request.java
   #   com/bluetrum/devicemanager/cmd/request/*.java   ← getCommand() 返回值就是命令码
   ```
2. **HCI snoop 抓包**（最直接）
   - 开发者选项 → 启用蓝牙 HCI 信息收集日志
   - 用官方 App 依次操作：连接 → 看电量 → 切 ANC 四档 → 切 EQ → 开游戏模式
   - **每步停顿 5 秒**，便于在 btsnoop 里切分
   - 导出 `/sdcard/btsnoop_hci.log`，用 Wireshark 过滤 `btrfcomm`
3. **真机通道探测**
   ```bash
   adb shell dumpsys bluetooth_manager | grep -A5 <耳机名>
   ```
   确认 SPP 连接的服务记录与 RFCOMM channel。
4. 用抓到的真实字节替换 `YscocoOp` 的命令码表与 `parseBattery()` 的字段布局。

## 5. 对用户的意义

即使协议尚未 100% 确证，本 App 仍然是有用的：

- 帧框架、分帧、校验、变体切换、传输层（RFCOMM + BLE GATT）、连接状态机、
  异常翻译、心跳轮询、UI 全部是**真实可工作**的；
- 一旦协议确证，只需替换一张命令码表；
- 调试面板会把收到的每个字节以十六进制 + 可打印字符展示，
  **用户可以自己抓包后对照调整**，无需重新编译（帧格式可在设置页切换）。

---

## 附：证据文件位置

逆向中间产物保存在 `_rev/out/`（已加入 `.gitignore`，不入库）：

| 文件 | 内容 |
|---|---|
| `_rev/yinpage.apk` | 官方 App 安装包（132.8 MB） |
| `_rev/out/classes.dex.strings.txt` | classes.dex 字符串池（1197 KB） |
| `_rev/out/classes2.dex.strings.txt` | classes2.dex 字符串池（388 KB） |
| `_rev/out/*.ascii.txt` | 带 DEX 偏移的 ASCII 串（用于定位字节码常量） |
| `_rev/out/*.utf8.txt` | 中文字符串（型号名 / 功能文案） |
| `_rev/DexScan.java` | 自写的 DEX 字符串提取工具（无 jadx 时的替代方案） |
