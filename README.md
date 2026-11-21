# Wi-Fi Auth Cleanup

**English** | [简体中文](README_zh-CN.md)

An LSPosed module for cleaning up failed Wi-Fi configurations on Xiaomi HyperOS.

Android Settings saves a Wi-Fi configuration before authenticating its credentials. If authentication fails, the incorrect configuration may remain in Saved networks. This module tracks connections submitted by Settings and removes the corresponding configuration after a confirmed authentication failure.

> The module detects HyperOS through `ro.mi.os.version.name`. It installs no hooks when that property is absent.

## Features

- Removes newly saved Wi-Fi configurations after authentication failure.
- Tracks only connections just submitted by Settings; ordinary background reconnections are ignored.
- Combines the HyperOS connection-failure broadcast with Supplicant authentication errors.
- Stops tracking immediately after a real successful connection.
- Retries removal when the framework is still finishing the failed connection.
- Supports automatic hot reload with Xposed API 102.
- Displays the module description and runtime notification in Chinese or English according to the default system language.
- Does not read password contents, persist connection history, or request network permissions.
- Has no settings screen, launcher icon, or background service.

## Compatibility

| Item | Value |
| --- | --- |
| Package | `io.github.aetherpx.wifiauthcleanup` |
| Xposed API | 102 (minimum / target) |
| Default scope | `com.android.settings` |
| `minSdk` | 33 |
| `targetSdk` / `compileSdk` | 37 |
| Primary target | Android 17 / HyperOS, `com.android.settings` version 17 |
| HyperOS property | `ro.mi.os.version.name` |

`minSdk` represents the Android baseline used by HyperOS; it does not imply compatibility with every Android 13+ ROM. The module remains inactive and installs no hooks on MIUI, AOSP, and other vendor ROMs.

The framework must support Xposed API 102. API 101 frameworks cannot load this version.

## Installation and usage

1. Install the generated APK.
2. Enable **Wi-Fi Auth Cleanup** in LSPosed.
3. Confirm that Settings (`com.android.settings`) is the selected scope.
4. Force-stop Settings or reboot once to inject the module.
5. Connect to Wi-Fi normally from system Settings.

After the system confirms an authentication failure, the module removes the newly saved network and displays:

```text
Authentication failed. This network was not saved.
```

On a Chinese system, the message is shown as:

```text
密码认证失败，已取消保存该网络
```

Before the first test, manually forget any existing network with the same SSID so an old configuration cannot affect the result. Depending on the router and system, reporting an authentication failure can take several seconds.

## How it works

### Connection tracking

The module hooks these framework APIs:

```text
WifiManager.save(WifiConfiguration, ActionListener)
WifiManager.connect(WifiConfiguration, ActionListener)
```

`save()` marks a configuration just submitted by Settings, while `connect()` starts tracking the attempt. A previously saved network that was not resubmitted does not create a cleanup task.

### Failure detection

Two signals verified on the target HyperOS build are used:

1. **HyperOS broadcast**
   `miui.intent.action.WIFI_CONNECTION_FAILURE` carries the failed `WifiConfiguration`, allowing the exact network to be removed first.
2. **Supplicant authentication error**
   `ERROR_AUTHENTICATING` from `SUPPLICANT_STATE_CHANGED_ACTION` identifies the active attempt by SSID and schedules removal.

A connection is considered successful only after a real `NetworkInfo.CONNECTED` state. Seeing the target SSID alone does not mean authentication has completed.

### Removal strategy

After authentication failure, the module calls:

```text
WifiManager.removeNetwork(networkId)
```

It does not continuously poll Wi-Fi state. Saved networks are queried only after an explicit authentication-failure signal. If the framework temporarily refuses removal, the module retries every 1.5 seconds. An attempt is tracked for at most 120 seconds.

### API 102 hot reload

The module declares exactly one Java entry and sets `autoHotReload=true` in `module.prop`. Before reloading, the old generation:

1. Stops accepting new hook events.
2. Unregisters its dynamic broadcast receiver.
3. Removes pending main-thread and worker callbacks.
4. Stops and joins its `HandlerThread`.
5. Clears connection state owned by the old class loader.

The new generation does not wait for package lifecycle callbacks to be replayed. Stable hook IDs atomically replace the old `save()` and `connect()` hooks. A Wi-Fi attempt already in progress during an update is intentionally discarded; later attempts use the new code.

Only the main `com.android.settings` process installs hooks. Auxiliary Settings processes keep an idle lifecycle entry so LSPosed can complete hot reload for every scoped process without reporting a detached-entry failure.

When upgrading from the API 101 release for the first time, the old generation cannot approve hot reload. Force-stop Settings or reboot once after installing this release. Subsequent API 102 updates can reload automatically.

Version 1.3.1 detached auxiliary process entries. After upgrading from 1.3.1, force-stop all Settings processes or reboot once; subsequent updates from 1.3.2 or newer can hot reload every scoped process normally.

Automatic updates still require the old and new APKs to use the same signing certificate. The repository's fixed test key satisfies this requirement for test builds.

## Building

### GitHub Actions

The workflow is defined in `.github/workflows/build.yml` and runs on:

- pushes to `main`
- pull requests
- manual `workflow_dispatch`

Download `WifiAuthCleanup-release` from the completed workflow's Artifacts.

### Local build

Requirements: JDK 21, Android SDK Platform 37, and Build Tools 37.0.0.

```bash
./gradlew :app:assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

Release builds enable R8 optimization, obfuscation, and resource shrinking. AGP 9 adds Kotlin stdlib even to an otherwise Java-only module; R8 removes that unreachable code while preserving the Xposed entry point.

| Build | APK size | DEX classes |
| --- | ---: | ---: |
| Before optimization | 2,088,273 bytes | 1,090 |
| API 102 + R8 | 21,697 bytes | 8 |

The optimized APK is about **99%** smaller. Exact size may vary slightly between AGP/R8 versions. The R8 mapping is written to:

```text
app/build/outputs/mapping/release/mapping.txt
```

The Android 17 SDK package is named `platforms;android-37.0`. If AGP looks for `platforms/android-37`, install the platform and create a compatibility link:

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
ln -sfn android-37.0 "$ANDROID_HOME/platforms/android-37"
```

The GitHub Actions workflow performs the same SDK installation and compatibility setup automatically.

## Signing

No private key or password is stored in the repository. Official GitHub Actions builds reconstruct the original PKCS#12 key from these Repository Secrets:

| Secret | Purpose |
| --- | --- |
| `SIGNING_KEY_BASE64` | Base64-encoded PKCS#12 file |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

Push and manual builds fail if the secrets are missing. Pull requests without secret access use a temporary certificate and are not update-compatible.

For local builds, create an ignored `signing.properties` file and keep the keystore outside the repository. See [signing/README.md](signing/README.md) for the required format.

Certificate SHA-256:

```text
0D:22:1D:15:FC:FC:54:A6:35:2C:FC:D9:3C:2C:21:AF:9A:90:42:9A:41:25:FB:35:D7:6A:68:AC:A1:42:2E:99
```

## Logs and troubleshooting

Logs are available from the module log page in LSPosed. A typical failed-password cleanup produces:

```text
Loaded into com.android.settings on HyperOS ...
Monitoring connection attempt for "..."
Supplicant authentication failure matched "..."
removeNetwork(...) via authentication failure -> true
Removed network "..." because authentication failed
```

A successful hot reload produces:

```text
Preparing API 102 hot reload
Installed Wi-Fi hooks
Hot reloaded on HyperOS ...
```

On a non-HyperOS system, only `HyperOS not detected; module disabled` is logged. If HyperOS shows successful module loading and hook installation but no `Monitoring connection attempt`, that Settings version did not call the hooked connection entry points. Preserve the complete LSPosed log when requesting adaptation.

## Known limitations

- Weak signal, an AP rejection, or an authentication timeout may also be classified as an authentication failure and cause cleanup.
- Only connections initiated by system Settings are handled; third-party Wi-Fi managers are not covered.
- Killing Settings before its authentication-failure callback arrives can prevent cleanup.
- MIUI, AOSP, and non-HyperOS vendor ROMs are not supported.
- HyperOS updates can change broadcasts or connection flows and may require adaptation.

## License

Licensed under the [Apache License 2.0](LICENSE).
