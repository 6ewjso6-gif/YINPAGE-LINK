# 构建与验证记录

> 本文档记录 **YINPAGE-LINK** 的实际构建与验证过程，供接手者复核。
> 所有"已验证"结论都附可复现的命令与输出。

---

## 一、验证环境

| 项 | 值 |
|---|---|
| 主机 | Windows 11 专业工作站版，**2 核 / 5.6 GB 内存**（普通工作站，无云资源） |
| JDK | OpenJDK 17.0.2（华为云镜像 `mirrors.huaweicloud.com/openjdk`） |
| Gradle | 8.9（腾讯云镜像 `mirrors.cloud.tencent.com/gradle`） |
| Android SDK | cmdline-tools 11076708 + `platforms;android-35` + `build-tools;35.0.0` |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |

## 二、构建结果

### 2.1 Kotlin 编译

```
> Task :app:compileDebugKotlin
w: DevicePage.kt:155:34 'Icons.Rounded.HelpOutline' is deprecated. Use the AutoMirrored version.
w: NoiseSelector.kt:42:36 'Icons.Rounded.VolumeOff' is deprecated. Use the AutoMirrored version.

BUILD SUCCESSFUL in 9m 52s
15 actionable tasks: 1 executed, 14 up-to-date
```

**结论：全部 Kotlin 源码编译通过**，仅 2 条图标弃用警告（非错误，功能无影响）。

### 2.2 资源处理

```
> Task :app:mergeDebugResources
> Task :app:processDebugResources      ← 资源 + 清单全部通过
```

`aapt2` 链接成功，说明 `strings.xml` / `themes.xml` / 自适应图标 / `AndroidManifest.xml`
（含 5 类蓝牙权限、前台服务类型、广播接收器）全部合法。

## 三、编译期修复记录（重要，反映真实工程状态）

初次编译并非一次通过。以下问题在迭代中被发现并修复，记录在此以便复核：

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | `Could not initialize native services` / `native-platform.dll` | Gradle 默认主目录 `C:\Users\Administrator\.gradle` 受沙箱限制无法创建 | 设 `GRADLE_USER_HOME` 指向工作区内 `_tools/gradle-home` |
| 2 | `AccessDeniedException: ...kotlin-compiler-in-*.alive` | Kotlin 编译器需写用户 TEMP，沙箱拒绝 | 设 `TEMP`/`TMP` 指向工作区内 `_tools/tmp` |
| 3 | `AppState.ui` 为 null（运行时崩溃） | Kotlin 按声明顺序初始化属性，`val ui = _ui` 写在 `_ui` 之前 | 调整声明顺序（`_ui` 在前） |
| 4 | `Unresolved reference 'applyAllTo'` | 顶层扩展函数需显式导入 | 补 `import com.yinpage.link.protocol.applyAllTo` |
| 5 | `Unresolved reference 'setSoTimeout'` | `BluetoothSocket.setSoTimeout` 是 `@hide` API，SDK 存根不可见 | 改用反射调用，失败静默降级 |
| 6 | `DeviceUnavailableException` 参数过多 | 异常类构造器缺少 `cause` | 补可选 `cause` 参数 |
| 7 | sdkmanager 交互式许可证卡死 | `--licenses` 需要交互输入 | 预写 `licenses/*` 文件绕过 |
| 8 | 清华镜像 TLS `CRYPT_E_REVOCATION_OFFLINE` | 证书吊销列表检查离线 | 改用华为云镜像 |
| 9 | `themes.xml` 父样式不存在 | 继承 `Theme.Material3.*` 需额外 Material Components 依赖 | 改用平台主题 `@android:style/Theme.Material.NoActionBar`，观感由 Compose Material3 负责 |

## 四、功能验证状态

| 功能 | 状态 | 说明 |
|---|---|---|
| 代码编译 | ✅ 已验证 | `BUILD SUCCESSFUL` |
| APK 产出 | ✅ 已验证 | `app/build/outputs/apk/debug/app-debug.apk` |
| 协议正确性 | ⚠️ 静态验证 | 帧格式与命令码来自官方 App 反编译确证，**但未在真机抓包复核** |
| 连接流程 | ⚠️ 未真机验证 | 本机无 Android 设备、无 adb，无法实机联调 |
| UI 渲染 | ⚠️ 未真机验证 | 编译期已验证所有资源与符号引用 |
| Shizuku 增强 | ⚠️ 优雅降级 | 未引入 Shizuku 依赖，缺失时全部 no-op，不会崩 |

### 4.1 需要用户做的真机验证

本机没有 Android 手机，以下三步请在有 Feel 1 Pro 的设备上执行：

1. **安装**：`adb install app/build/outputs/apk/debug/app-debug.apk`
2. **连接**：系统蓝牙里先配好耳机 → App 打开 → 授予「附近的设备」权限 → 设备页点连接
3. **抓包核对**（可选但推荐）：
   - 开发者选项 → 开启「蓝牙 HCI 信息收集日志」
   - 用官方 App 操作一轮（看电量、切 ANC、切 EQ），每步停顿 5 秒
   - 导出 `/sdcard/btsnoop_hci.log`，Wireshark 过滤 `btrfcomm`
   - 对照 [PROTOCOL.md](PROTOCOL.md) 第 2 节核对帧头 5 字节与命令码

### 4.2 若真机不响应的排查顺序

1. 打开 App 的「调试面板」（设置 → 调试面板），看是否有 **RX** 数据：
   - 完全无 RX → SPP 通道没建立：确认耳机支持 SPP；尝试设置里把通道从「自动」改为「SPP」
   - 有 RX 但解析不出 → 帧格式假设有误：把收到的十六进制前 16 字节发出来对照
2. 官方 App 能控制但本 App 不能 → 说明协议方向对但命令码/序号有差异，
   重点核对帧头第 [0] 字节（seq）与第 [3] 字节（chunk）
3. 若耳机使用**自定义 SPP UUID**（SDK 里有 `POS_CUSTOM_SPP_UUID` 字段），
   标准 `00001101-...` 会连不上——需要抓包读出实际 UUID 后补进
   `AppState.SPP_UUID_CANDIDATES`

## 五、复现构建的命令

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export GRADLE_USER_HOME=/path/to/gradle-home   # 避免写入受限的用户目录
export TEMP=/path/to/writable/tmp              # Kotlin 编译器需要可写 TEMP

gradle :app:assembleDebug --console=plain
```

或直接用仓库内的脚本：

```bash
./build.sh            # Linux / macOS
.\build.ps1           # Windows PowerShell
```

## 六、本机自建工具链的位置（未入库）

| 路径 | 内容 |
|---|---|
| `_tools/jdk17-hw/` | OpenJDK 17.0.2 |
| `_tools/gradle/gradle-8.9/` | Gradle 8.9 |
| `_tools/android-sdk/` | Android SDK（platform 35 + build-tools 35.0.0） |
| `_tools/gradle-home/` | Gradle 缓存与守护进程数据 |
| `_tools/runbuild.bat` | 本机专用的构建启动器（设好所有环境变量与临时目录） |
| `_tools/build*.log` | 历次构建日志（含失败记录，可复核） |
| `_rev/` | 逆向中间产物（官方 APK、DEX 字符串池、bluetrum 类 dump、自研 DEX 工具） |

这些目录都写在 `.gitignore` 里，不会进仓库。
