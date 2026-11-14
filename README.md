# WiFi 密码保护（LSPosed 模块）

连接 Wi-Fi 时，如果输入的密码不正确，系统设置会先把网络配置（包括密码）保存下来，然后再去连接。于是错误的网络会一直留在“已保存网络”里，需要手动“忘记”。本模块用于在认证失败后自动删除这个刚保存的错误网络。

- Xposed API：**101**
- 作用域：`com.android.settings`
- 目标：Android 17 / HyperOS 的设置（`com.android.settings` version 17）
- 纯 LSPosed 模块，不包含 Activity，也不会在桌面显示启动图标

## 工作原理

连接时框架会先把配置（包括密码）写入已保存网络，再发起认证，因此没法在保存前判断密码是否正确。模块改为“先记住刚提交的配置，认证失败后再删掉它”：

1. Hook `WifiManager.save(WifiConfiguration, ActionListener)` 和 `WifiManager.connect(WifiConfiguration, ActionListener)`，记录本次由设置界面提交的配置（SSID）。只有“刚刚保存过的配置”或“全新的网络”会被跟踪，因此普通地连接一个早已保存的网络不会被误删。
2. 认证失败后删除对应网络，使用三种互补信号：
   - **MIUI/HyperOS**：监听 `miui.intent.action.WIFI_CONNECTION_FAILURE` 广播。该广播携带失败的 `WifiConfiguration`，可以精确删除刚尝试的网络。
   - **Supplicant 认证错误**：监听 `SUPPLICANT_STATE_CHANGED_ACTION` 中的 `ERROR_AUTHENTICATING`；一旦系统明确报告认证失败，就直接清理该次提交的配置，不等待隐藏状态字段更新。
   - **通用回退**：轮询 `WifiManager.getConfiguredNetworks()`，检查 `NetworkSelectionStatus`。当禁用原因为 `DISABLED_AUTHENTICATION_FAILURE`（3）或 `DISABLED_BY_WRONG_PASSWORD`（8）时删除网络。
3. 删除成功后弹出提示：`Wi-Fi 密码错误，已取消保存该网络`。

只有由设置界面刚刚保存 / 提交的配置才会被跟踪，因此不会去清理后台自动重连的旧网络。

## 构建

### 使用 GitHub Actions

把本文件夹作为仓库根目录上传到 GitHub，Actions 会自动执行 `.github/workflows/build.yml`，在 `Artifacts` 中下载 `WifiPasswordGuard-release`。

### 本地构建

需要 JDK 21 和 Android SDK（compileSdk 37）：

```bash
./gradlew :app:assembleRelease
```

Android 17 的 SDK 平台包名是 `platforms;android-37.0`（不存在 `platforms;android-37`），
安装后的目录是 `platforms/android-37.0`。如果 Gradle 提示找不到 `android-37`，
创建一个兼容链接：

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
ln -s android-37.0 "$ANDROID_HOME/platforms/android-37"
```

GitHub Actions 工作流中已经自动完成上述安装与链接。

APK 输出：

```text
app/build/outputs/apk/release/app-release.apk
```

release 构建固定使用仓库中的 `signing/wifi-password-guard.p12`。因此无论在本地还是
GitHub Actions 构建，APK 签名都保持一致，可以直接覆盖升级。证书 SHA-256：

```text
03:3A:6B:A5:75:AD:6A:56:E6:32:08:9A:3A:1F:52:B5:1E:F7:80:B8:2B:5E:EC:33:22:F8:6A:85:FB:40:CA:09
```

这个私钥随公开源码提供，只用于保证测试构建签名稳定，不能作为正式发布身份凭据。
正式发布前应替换为自行保管的私钥，并妥善保存；更换签名后无法覆盖安装旧签名 APK。

## 安装与使用

1. 安装 APK。若设备上装有 1.0.1 或更早的随机 debug 签名版本，需要先卸载旧版一次；
   从 1.0.2 开始，后续构建可以直接覆盖升级。
2. 在 LSPosed 中启用模块。
3. 作用域勾选“设置”（`com.android.settings`）。
4. 强制停止“设置”或重启手机。
5. 在系统设置中连接 Wi-Fi，输入一个错误密码测试。

本模块没有配置界面，安装后不会在桌面生成图标，所有启用和作用域操作均在 LSPosed 中完成。

查看日志：

```bash
adb logcat -s WifiPasswordGuard
```

也可以在 LSPosed 的日志页面查看模块日志。

## 注意事项

- 如果修改一个**已保存网络**的密码，并且新密码错误，该网络会被移除（因为错误密码已经覆盖了旧配置）。如果希望保留旧密码，需要额外保存并恢复旧配置，这需要读取 Wi-Fi 凭据，模块目前不做这件事。
- 若密码正确，但因为信号太差、AP 拒绝或认证超时被框架判定为认证失败，网络同样可能被移除，重新输入密码即可。
- 模块只处理通过系统设置发起的连接。如果刚输入密码就立刻强杀设置进程，认证失败回调来不及执行，网络可能仍会保留。
- 不同 ROM 的 Wi-Fi 实现可能不同；本项目以 Android 17 / HyperOS 的反编译设置代码为参考，并做了 AOSP 风格的通用回退。
- 本项目仅用于学习和个人设备定制，请遵守当地法律法规。
