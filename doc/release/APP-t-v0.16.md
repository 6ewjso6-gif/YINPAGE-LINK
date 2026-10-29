## YINPAGE-LINK v0.16 更新公告

本次以**官方 App 反编译结论**为准重做了蓝牙协议层，并修复 issue #3 的全部 6 项。

### 协议（对齐官方 Android 实现）
- 控制通道确证为经典蓝牙 SPP（手机做 client、仅 `00001101`、insecure 建链），不再按 iOS SDK 的 BLE GATT 假设打补丁
- 分包大小由 15 改为官方实际的 250，并按设备 MTU 响应回填 `值 - 5`
- 帧序号改为**按物理帧**递增（此前按消息递增，多分包时设备会丢帧）
- `DEVICE_INFO(0x27)` / 通知容器 `0x28` 响应按 TLV `[infoId][len][data…]` 解析，修正电量/ANC/EQ 全部错位
- 连上后只发一条 MTU 查询；状态同步合并为一条 `DeviceInfoRequest`，请求严格串行
- SPP 改为 insecure 优先（官方路径不强制配对）

### issue #3 修复
- 设置页切换降噪改走 `ModuleIpc.broadcastAncSelect()`，补上此前漏传的鉴权令牌（设置页 ANC 不再被丢弃）
- SPP 候选改为按 insecure / secure 两轮扫描，靠后的 UUID 不再因预算被前面哑通道吃光而永远试不到
- 应用内安装的准备（签名解析 + APK 拷贝 + fsync）移到 IO 线程，消除 ANR 风险
- 运行日志改写入 `noBackupFilesDir`，并在备份/迁移规则中显式排除
- 旧框架 `onPackageLoaded` 回退路径补生成 IPC 令牌；本地构建支持 `YINPAGE_VERSION_NAME` 覆盖版本名
- 丢弃候选时作废旧读循环代次，残留字节不会再让下一个候选被误判「有响应」
- Release 说明改由 `doc/release/<tag>.md` 提供，避免 workflow 里写死过期公告

### 兼容性
- 版本号 APP-t-v0.16（1800016000），可直接覆盖安装 v0.15，数据不丢失
- 旧版本用户可在「设置 → 检查更新」自动升级
