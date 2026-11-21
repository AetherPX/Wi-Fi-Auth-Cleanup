# Wi-Fi Auth Cleanup

[English](README.md) | **简体中文**

一个仅适用于 Xiaomi HyperOS 的 LSPosed Wi-Fi 认证失败清理模块。

系统设置在连接 Wi-Fi 时会先保存网络配置，再进行密码认证。如果密码错误，配置仍可能留在“已保存网络”中。本模块会跟踪由系统设置提交的连接，在确认认证失败后自动删除对应配置。

> 模块通过 `ro.mi.os.version.name` 检测 HyperOS。属性不存在时不会安装任何 Hook。

## 功能

- 自动移除认证失败后残留的 Wi-Fi 配置。
- 只跟踪系统设置刚提交的连接，不清理普通后台自动重连记录。
- 使用 HyperOS 认证失败广播和 Supplicant 认证错误双重检测。
- 正确连接时立即结束跟踪，不影响正常保存的网络。
- 删除失败时自动重试，成功后显示提示。
- 支持 Xposed API 102 自动热重载，更新 APK 后无需重启“设置”进程。
- 模块说明与运行时提示根据系统默认语言显示中文或英文。
- 不读取密码内容，不持久化连接记录，也不申请网络权限。
- 不包含配置界面、桌面图标或后台服务。

## 兼容性

| 项目 | 值 |
| --- | --- |
| 应用包名 | `io.github.aetherpx.wifiauthcleanup` |
| Xposed API | 102（minimum / target） |
| 默认作用域 | `com.android.settings` |
| `minSdk` | 33 |
| `targetSdk` / `compileSdk` | 37 |
| 主要测试目标 | Android 17 / HyperOS，`com.android.settings` version 17 |
| HyperOS 检测属性 | `ro.mi.os.version.name` |

`minSdk` 对应 HyperOS 的 Android 基线，但不代表兼容所有 Android 13+ 系统。模块在 MIUI、AOSP 和其他厂商 ROM 中保持空闲，不安装任何 Hook。

模块要求框架支持 Xposed API 102；API 101 框架无法加载此版本。

## 安装与使用

1. 安装构建得到的 APK。
2. 在 LSPosed 中启用 **Wi-Fi Auth Cleanup**。
3. 确认作用域为“设置” (`com.android.settings`)。
4. 强制停止“设置”应用，或重启设备以重新注入模块。
5. 在系统设置中正常连接 Wi-Fi。

密码错误并被系统判定为认证失败后，模块会删除刚保存的网络并提示：

```text
密码认证失败，已取消保存该网络
```

英文系统中显示：

```text
Authentication failed. This network was not saved.
```

首次测试前建议先手动忘记同名测试网络，以免旧配置干扰结果。部分路由器或系统可能需要数秒至数十秒才会报告认证失败。

## 实现原理

### 连接跟踪

模块 Hook 以下系统 API：

```text
WifiManager.save(WifiConfiguration, ActionListener)
WifiManager.connect(WifiConfiguration, ActionListener)
```

`save()` 用于标记刚由设置提交的配置，`connect()` 用于开始跟踪连接。对于早已保存且本次没有重新提交的网络，模块不会建立清理任务。

### 失败检测

模块组合使用两种已在目标系统验证的信号：

1. **HyperOS 广播**
   监听 `miui.intent.action.WIFI_CONNECTION_FAILURE`，优先使用广播中的 `WifiConfiguration` 精确删除失败网络。
2. **Supplicant 认证错误**
   监听 `SUPPLICANT_STATE_CHANGED_ACTION` 中的 `ERROR_AUTHENTICATING`，再按本次连接的 SSID 定位并删除配置。

只有收到真实的 `NetworkInfo.CONNECTED` 状态后，连接才会被判定为成功。仅出现目标 SSID 不代表认证完成，这可以避免在握手阶段提前停止监控。

### 删除策略

认证失败后调用：

```text
WifiManager.removeNetwork(networkId)
```

模块不会持续轮询 Wi-Fi 状态。只有收到明确的认证失败信号后，才查询已保存网络并执行删除；如果框架仍在结束失败连接而暂时拒绝删除，则每 1.5 秒重试，最长跟踪 120 秒。

### API 102 热重载

模块只声明一个 Java 入口类，满足 API 102 的热重载要求；`module.prop` 启用了 `autoHotReload=true`。APK 更新触发热重载时，旧代码会：

1. 停止接收新的 Hook 事件。
2. 注销动态广播接收器。
3. 清空主线程和工作线程回调。
4. 停止并等待模块的 `HandlerThread` 退出。
5. 清空仅存在于旧类加载器中的连接状态。

新代码不会等待包生命周期回调重放，而是通过固定 Hook ID 原子替换旧的 `save()` 和 `connect()` Hook。更新发生时正在进行的 Wi-Fi 尝试会被放弃跟踪，之后的新连接会使用新代码。

只有 `com.android.settings` 主进程安装 Hook。设置的辅助进程会保留一个不执行功能的生命周期入口，使 LSPosed 能为作用域内的每个进程完成热重载，而不会报告入口已脱离。

从 API 101 版本首次升级到本版本时，旧代码无法批准热重载，因此仍需强制停止一次“设置”或重启设备。完成首次 API 102 加载后，后续更新可自动热重载。

1.3.1 曾让辅助进程入口调用 `detach()`。从 1.3.1 升级后，需要强制停止所有“设置”进程或重启一次；从 1.3.2 开始，后续更新可让作用域内所有进程正常热重载。

自动更新仍要求新旧 APK 使用相同签名；仓库中的固定测试密钥满足当前测试构建的覆盖安装要求。

## 构建

### GitHub Actions

工作流文件为 `.github/workflows/build.yml`，支持以下触发方式：

- 推送到 `main`
- Pull Request
- 手动运行 `workflow_dispatch`

成功后从该次工作流的 Artifacts 下载 `WifiAuthCleanup-release`。

### 本地构建

需要 JDK 21、Android SDK Platform 37 和 Build Tools 37.0.0：

```bash
./gradlew :app:assembleRelease
```

APK 输出位置：

```text
app/build/outputs/apk/release/app-release.apk
```

Release 构建启用了 R8 代码优化、混淆和资源压缩。AGP 9 即使在纯 Java 模块中也会向未压缩构建加入 Kotlin 标准库；R8 会删除这些不可达代码，同时保留 Xposed 入口类。当前环境的实测结果：

| 构建 | APK 大小 | DEX 类数量 |
| --- | ---: | ---: |
| 优化前 | 2,088,273 字节 | 1,090 |
| API 102 + R8 | 21,697 字节 | 8 |

体积仍比未优化版本减少约 **99%**。不同 AGP/R8 版本生成的具体字节数可能略有变化。R8 映射文件位于：

```text
app/build/outputs/mapping/release/mapping.txt
```

Android 17 SDK 的包名为 `platforms;android-37.0`。如果 AGP 查找的是 `platforms/android-37`，可安装平台后创建兼容链接：

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
ln -sfn android-37.0 "$ANDROID_HOME/platforms/android-37"
```

GitHub Actions 已自动执行相同的 SDK 安装与目录兼容处理。

## 构建签名

仓库不再保存私钥或密码。GitHub Actions 正式构建通过以下 Repository Secrets 还原原 PKCS#12 密钥：

| Secret | 用途 |
| --- | --- |
| `SIGNING_KEY_BASE64` | PKCS#12 文件的 Base64 内容 |
| `SIGNING_STORE_PASSWORD` | 密钥库密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 私钥密码 |

Push 和手动构建缺少 Secrets 时会直接失败。无法访问 Secrets 的 Pull Request 使用临时证书，因此其 APK 不能覆盖正式版本。

本地构建时需要创建已被 Git 忽略的 `signing.properties`，并将密钥库保存在仓库外。配置格式见 [signing/README.md](signing/README.md)。

证书 SHA-256：

```text
0D:22:1D:15:FC:FC:54:A6:35:2C:FC:D9:3C:2C:21:AF:9A:90:42:9A:41:25:FB:35:D7:6A:68:AC:A1:42:2E:99
```

## 日志与排查

模块日志位于 LSPosed 的模块日志页面。一次错误密码清理通常会出现以下顺序：

```text
Loaded into com.android.settings on HyperOS ...
Monitoring connection attempt for "..."
Supplicant authentication failure matched "..."
removeNetwork(...) via authentication failure -> true
Removed network "..." because authentication failed
```

热重载成功时会出现：

```text
Preparing API 102 hot reload
Installed Wi-Fi hooks
Hot reloaded on HyperOS ...
```

非 HyperOS 系统只会记录 `HyperOS not detected; module disabled`。在 HyperOS 上，如果只有加载和 Hook 成功日志，却没有 `Monitoring connection attempt`，说明当前设置版本没有调用所 Hook 的连接入口；如果已有监控日志但没有认证失败记录，请保留完整 LSPosed 日志用于适配。

## 已知限制

- 信号过差、AP 主动拒绝或认证超时也可能被系统归类为认证失败，此时网络同样会被清理。
- 模块只处理系统设置进程发起的连接，不处理第三方 Wi-Fi 管理应用。
- 在认证失败回调到达前强制结束“设置”进程，可能导致清理来不及执行。
- 本模块不支持 MIUI、AOSP 或其他厂商 ROM。
- HyperOS 更新可能改变广播或连接流程，需要根据新日志重新适配。

## License

本项目使用 [Apache License 2.0](LICENSE)。
