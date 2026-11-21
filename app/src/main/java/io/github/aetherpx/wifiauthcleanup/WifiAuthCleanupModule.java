package io.github.aetherpx.wifiauthcleanup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Removes a network saved by HyperOS Settings after Wi-Fi authentication fails. */
public final class WifiAuthCleanupModule extends XposedModule {

    private static final String TAG = "WifiAuthCleanup";
    private static final String TARGET_PACKAGE = "com.android.settings";
    private static final String HYPER_OS_VERSION_PROPERTY = "ro.mi.os.version.name";
    private static final String HOOK_ID_SAVE = "wifi-auth-cleanup:save";
    private static final String HOOK_ID_CONNECT = "wifi-auth-cleanup:connect";

    private static final String ACTION_WIFI_CONNECTION_FAILURE =
            "miui.intent.action.WIFI_CONNECTION_FAILURE";
    private static final String EXTRA_WIFI_CONFIGURATION = "wifiConfiguration";
    private static final String EXTRA_IS_SLAVE = "isSlave";

    private static final long ATTEMPT_TIMEOUT_MS = 120_000L;
    private static final long REMOVAL_RETRY_MS = 1_500L;
    private static final long MISSING_CONFIG_GRACE_MS = 10_000L;
    private static final long RECENT_SAVE_WINDOW_MS = 15_000L;

    private static final String TOAST_TEXT_EN =
            "Authentication failed. This network was not saved.";
    private static final String TOAST_TEXT_ZH = "密码认证失败，已取消保存该网络";

    private static final Map<String, Attempt> ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> RECENT_SAVES = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    private static volatile WifiAuthCleanupModule sModule;
    private static volatile WifiManager sWifiManager;
    private static volatile Context sContext;
    private static volatile Handler sWorker;
    private static volatile HandlerThread sWorkerThread;
    private static volatile Handler sMainHandler;
    private static volatile String sHyperOsVersion;
    private static volatile boolean sHooked;
    private static volatile boolean sReceiverRegistered;
    private static volatile boolean sReloading;

    private static final Runnable REMOVE_FAILED_RUNNABLE =
            WifiAuthCleanupModule::removeFailedAttempts;

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (LOCK) {
                if (sReloading) {
                    return;
                }
                String action = intent.getAction();
                if (ACTION_WIFI_CONNECTION_FAILURE.equals(action)) {
                    onHyperOsConnectionFailure(intent);
                    return;
                }
                if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
                    int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);
                    if (error == WifiManager.ERROR_AUTHENTICATING) {
                        onSupplicantAuthFailure();
                    }
                    return;
                }
                if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                    NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (info != null && info.isConnected()) {
                        onNetworkConnected();
                    }
                }
            }
        }
    };

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sModule = this;
        if (!TARGET_PACKAGE.equals(param.getProcessName())) {
            detach();
            return;
        }
        sHyperOsVersion = readSystemProperty(HYPER_OS_VERSION_PROPERTY);
        if (isHyperOs()) {
            logInfo("Loaded into " + param.getProcessName() + " on HyperOS " + sHyperOsVersion
                    + " (Xposed API " + getApiVersion() + ")");
        } else {
            logInfo("HyperOS not detected; module disabled in " + param.getProcessName());
            detach();
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!isHyperOs() || !TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        try {
            installHooks();
        } catch (Throwable t) {
            logError("Failed to install hooks", t);
        }
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        sReloading = true;
        logInfo("Preparing API 102 hot reload");
        boolean ready = releaseRuntimeResources();
        if (!ready) {
            sReloading = false;
            logInfo("Hot reload rejected because runtime resources could not be released");
        }
        return ready;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        sModule = this;
        sReloading = false;
        if (!TARGET_PACKAGE.equals(param.getProcessName())) {
            unhookAll(param.getOldHookHandles());
            detach();
            return;
        }

        sHyperOsVersion = readSystemProperty(HYPER_OS_VERSION_PROPERTY);
        if (!isHyperOs()) {
            unhookAll(param.getOldHookHandles());
            logInfo("HyperOS not detected after hot reload; module disabled");
            detach();
            return;
        }

        unhookUnknown(param.getOldHookHandles());
        try {
            installHooks();
            logInfo("Hot reloaded on HyperOS " + sHyperOsVersion);
        } catch (Throwable t) {
            unhookAll(param.getOldHookHandles());
            logError("Failed to replace hooks during hot reload", t);
        }
    }

    private void installHooks() throws Throwable {
        synchronized (LOCK) {
            if (sHooked) {
                return;
            }

            Class<?> actionListenerClass = Class.forName(
                    "android.net.wifi.WifiManager$ActionListener",
                    false,
                    WifiManager.class.getClassLoader());
            Method save = WifiManager.class.getDeclaredMethod(
                    "save", WifiConfiguration.class, actionListenerClass);
            Method connect = WifiManager.class.getDeclaredMethod(
                    "connect", WifiConfiguration.class, actionListenerClass);

            XposedInterface.HookHandle saveHandle = hook(save)
                    .setId(HOOK_ID_SAVE)
                    .intercept(chain -> {
                        onSave(chain.getThisObject(), (WifiConfiguration) chain.getArg(0));
                        return chain.proceed();
                    });
            try {
                hook(connect)
                        .setId(HOOK_ID_CONNECT)
                        .intercept(chain -> {
                            onConnect(chain.getThisObject(), (WifiConfiguration) chain.getArg(0));
                            return chain.proceed();
                        });
            } catch (Throwable hookError) {
                try {
                    saveHandle.unhook();
                } catch (Throwable cleanupError) {
                    hookError.addSuppressed(cleanupError);
                }
                throw hookError;
            }
            sHooked = true;
            logInfo("Installed Wi-Fi hooks");
        }
    }

    private static boolean releaseRuntimeResources() {
        // Wait for hook callbacks that entered module state before sReloading became visible.
        synchronized (LOCK) {
            // Memory barrier only; new callbacks stop at their sReloading checks.
        }

        boolean released = true;
        Context context = sContext;
        if (sReceiverRegistered && context != null) {
            try {
                context.unregisterReceiver(RECEIVER);
                sReceiverRegistered = false;
            } catch (IllegalArgumentException ignored) {
                // The receiver was already removed by the target process.
                sReceiverRegistered = false;
            } catch (Throwable t) {
                released = false;
                logError("Failed to unregister receiver for hot reload", t);
            }
        }

        Handler worker = sWorker;
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
        }
        HandlerThread workerThread = sWorkerThread;
        if (workerThread != null) {
            workerThread.quit();
            if (Thread.currentThread() != workerThread) {
                try {
                    workerThread.join(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    released = false;
                }
            }
            if (workerThread.isAlive()) {
                released = false;
            } else {
                sWorkerThread = null;
                sWorker = null;
            }
        }

        Handler mainHandler = sMainHandler;
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
            sMainHandler = null;
        }

        synchronized (LOCK) {
            ATTEMPTS.clear();
            RECENT_SAVES.clear();
            sWifiManager = null;
            if (!sReceiverRegistered) {
                sContext = null;
            }
        }
        return released;
    }

    private static void unhookUnknown(List<XposedInterface.HookHandle> oldHooks) {
        for (XposedInterface.HookHandle handle : oldHooks) {
            try {
                String id = handle.getId();
                if (!HOOK_ID_SAVE.equals(id) && !HOOK_ID_CONNECT.equals(id)) {
                    handle.unhook();
                }
            } catch (Throwable t) {
                logError("Failed to remove an unknown old hook", t);
            }
        }
    }

    private static void unhookAll(List<XposedInterface.HookHandle> oldHooks) {
        for (XposedInterface.HookHandle handle : oldHooks) {
            try {
                handle.unhook();
            } catch (Throwable t) {
                logError("Failed to remove an old hook", t);
            }
        }
    }

    private static void onSave(Object thisObject, WifiConfiguration config) {
        if (sReloading || config == null) {
            return;
        }
        String ssid = normalizeSsid(config.SSID);
        if (!isUsableSsid(ssid)) {
            return;
        }

        rememberSave(ssid);
        if (config.networkId < 0) {
            trackAttempt((WifiManager) thisObject, ssid, "save");
        }
    }

    private static void onConnect(Object thisObject, WifiConfiguration config) {
        if (sReloading || config == null) {
            return;
        }
        String ssid = normalizeSsid(config.SSID);
        if (!isUsableSsid(ssid)) {
            return;
        }

        boolean recentlySaved = consumeRecentSave(ssid);
        if (config.networkId >= 0 && !recentlySaved) {
            return;
        }
        trackAttempt((WifiManager) thisObject, ssid, "connect");
    }

    private static void trackAttempt(WifiManager wifiManager, String ssid, String source) {
        synchronized (LOCK) {
            if (sReloading) {
                return;
            }
            sWifiManager = wifiManager;
            if (sContext == null) {
                sContext = findContext(wifiManager);
            }
            registerReceiverIfPossible();

            Attempt attempt = new Attempt(ssid);
            ATTEMPTS.put(ssid, attempt);
            ensureWorker().postDelayed(() -> expireAttempt(ssid, attempt), ATTEMPT_TIMEOUT_MS);
            logInfo("Monitoring connection attempt for \"" + ssid + "\" (via " + source + ")");
        }
    }

    private static void expireAttempt(String ssid, Attempt attempt) {
        if (ATTEMPTS.remove(ssid, attempt)) {
            logInfo("Stopped monitoring \"" + ssid + "\" (timeout)");
        }
    }

    private static void rememberSave(String ssid) {
        synchronized (LOCK) {
            if (sReloading) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            RECENT_SAVES.entrySet().removeIf(
                    entry -> now - entry.getValue() > RECENT_SAVE_WINDOW_MS);
            RECENT_SAVES.put(ssid, now);
        }
    }

    private static boolean consumeRecentSave(String ssid) {
        synchronized (LOCK) {
            if (sReloading) {
                return false;
            }
            Long savedAt = RECENT_SAVES.remove(ssid);
            return savedAt != null
                    && SystemClock.elapsedRealtime() - savedAt <= RECENT_SAVE_WINDOW_MS;
        }
    }

    private static void onHyperOsConnectionFailure(Intent intent) {
        if (ATTEMPTS.isEmpty() || intent.getBooleanExtra(EXTRA_IS_SLAVE, false)) {
            return;
        }

        WifiConfiguration failedConfig = intent.getParcelableExtra(EXTRA_WIFI_CONFIGURATION);
        if (failedConfig == null) {
            Attempt attempt = findAttempt(null);
            if (attempt != null) {
                markAuthenticationFailure(attempt);
                scheduleRemoval(0L);
            }
            return;
        }

        String ssid = normalizeSsid(failedConfig.SSID);
        Attempt attempt = isUsableSsid(ssid) ? ATTEMPTS.get(ssid) : null;
        if (attempt == null) {
            return;
        }
        markAuthenticationFailure(attempt);

        if (failedConfig.networkId >= 0
                && removeNetwork(failedConfig, "HyperOS broadcast")) {
            if (ATTEMPTS.remove(ssid, attempt)) {
                showRemovedToast(ssid);
            }
            return;
        }
        scheduleRemoval(0L);
    }

    private static void onSupplicantAuthFailure() {
        if (ATTEMPTS.isEmpty()) {
            return;
        }

        String currentSsid = getCurrentSsid();
        Attempt attempt = findAttempt(currentSsid);
        if (attempt == null) {
            logInfo("Authentication failure did not match an active attempt"
                    + (currentSsid == null ? "" : " (SSID=\"" + currentSsid + "\")"));
            return;
        }

        markAuthenticationFailure(attempt);
        logInfo("Supplicant authentication failure matched \"" + attempt.ssid + "\"");
        scheduleRemoval(0L);
    }

    private static void onNetworkConnected() {
        if (ATTEMPTS.isEmpty()) {
            return;
        }
        String ssid = getCurrentSsid();
        if (isUsableSsid(ssid) && ATTEMPTS.remove(ssid) != null) {
            logInfo("Connection to \"" + ssid + "\" reached NetworkInfo.CONNECTED");
        }
    }

    private static void markAuthenticationFailure(Attempt attempt) {
        if (!attempt.authenticationFailed) {
            attempt.failureAt = SystemClock.elapsedRealtime();
            attempt.authenticationFailed = true;
        }
    }

    private static void removeFailedAttempts() {
        if (sReloading || ATTEMPTS.isEmpty()) {
            return;
        }

        List<WifiConfiguration> configured;
        try {
            configured = sWifiManager.getConfiguredNetworks();
        } catch (Throwable t) {
            logError("getConfiguredNetworks failed", t);
            scheduleRemoval(REMOVAL_RETRY_MS);
            return;
        }
        if (configured == null) {
            scheduleRemoval(REMOVAL_RETRY_MS);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        boolean retry = false;
        for (Attempt attempt : ATTEMPTS.values()) {
            if (!attempt.authenticationFailed) {
                continue;
            }

            boolean found = false;
            boolean removed = false;
            for (WifiConfiguration config : configured) {
                if (!ssidEquals(config.SSID, attempt.ssid)) {
                    continue;
                }
                found = true;
                if (removeNetwork(config, "authentication failure")) {
                    removed = true;
                }
            }

            if (removed) {
                if (ATTEMPTS.remove(attempt.ssid, attempt)) {
                    showRemovedToast(attempt.ssid);
                }
            } else if (!found && now - attempt.failureAt >= MISSING_CONFIG_GRACE_MS) {
                if (ATTEMPTS.remove(attempt.ssid, attempt)) {
                    logInfo("Network \"" + attempt.ssid + "\" is no longer configured");
                }
            } else {
                retry = true;
            }
        }

        if (retry) {
            scheduleRemoval(REMOVAL_RETRY_MS);
        }
    }

    private static boolean removeNetwork(WifiConfiguration config, String source) {
        if (config.networkId < 0) {
            return false;
        }
        try {
            boolean removed = sWifiManager.removeNetwork(config.networkId);
            logInfo("removeNetwork(" + config.networkId + ") via " + source + " -> " + removed);
            return removed;
        } catch (Throwable t) {
            logError("removeNetwork(" + config.networkId + ") failed", t);
            return false;
        }
    }

    private static Attempt findAttempt(String ssid) {
        if (isUsableSsid(ssid)) {
            return ATTEMPTS.get(ssid);
        }
        return ATTEMPTS.size() == 1 ? ATTEMPTS.values().iterator().next() : null;
    }

    private static String getCurrentSsid() {
        try {
            WifiInfo info = sWifiManager.getConnectionInfo();
            return info == null ? null : normalizeSsid(info.getSSID());
        } catch (Throwable t) {
            logError("getConnectionInfo failed", t);
            return null;
        }
    }

    private static void showRemovedToast(String ssid) {
        logInfo("Removed network \"" + ssid + "\" because authentication failed");
        Context context = sContext;
        if (context == null) {
            return;
        }
        ensureMainHandler().post(() -> {
            try {
                Toast.makeText(context, getToastText(), Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                logError("Toast failed", t);
            }
        });
    }

    private static void registerReceiverIfPossible() {
        Context context = sContext;
        if (context == null || sReceiverRegistered) {
            return;
        }
        synchronized (LOCK) {
            if (sReceiverRegistered) {
                return;
            }
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_WIFI_CONNECTION_FAILURE);
                filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
                filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

                context.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
                sReceiverRegistered = true;
                logInfo("Broadcast receiver registered");
            } catch (Throwable t) {
                logError("registerReceiver failed", t);
            }
        }
    }

    private static void scheduleRemoval(long delayMs) {
        Handler worker = ensureWorker();
        worker.removeCallbacks(REMOVE_FAILED_RUNNABLE);
        worker.postDelayed(REMOVE_FAILED_RUNNABLE, delayMs);
    }

    private static Handler ensureWorker() {
        Handler worker = sWorker;
        if (worker != null) {
            return worker;
        }
        synchronized (LOCK) {
            if (sWorker == null) {
                HandlerThread thread = new HandlerThread("WifiAuthCleanup");
                thread.start();
                sWorkerThread = thread;
                sWorker = new Handler(thread.getLooper());
            }
            return sWorker;
        }
    }

    private static Handler ensureMainHandler() {
        Handler handler = sMainHandler;
        if (handler != null) {
            return handler;
        }
        synchronized (LOCK) {
            if (sMainHandler == null) {
                sMainHandler = new Handler(Looper.getMainLooper());
            }
            return sMainHandler;
        }
    }

    private static Context findContext(WifiManager wifiManager) {
        try {
            Field field = WifiManager.class.getDeclaredField("mContext");
            field.setAccessible(true);
            Object value = field.get(wifiManager);
            if (value instanceof Context) {
                return ((Context) value).getApplicationContext();
            }
        } catch (Throwable ignored) {
            // Fall through to ActivityThread.
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            if (value instanceof Context) {
                return ((Context) value).getApplicationContext();
            }
        } catch (Throwable ignored) {
            // A context is only required for broadcasts and the confirmation toast.
        }
        return null;
    }

    private static String readSystemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getDeclaredMethod("get", String.class);
            get.setAccessible(true);
            Object value = get.invoke(null, key);
            return value instanceof String ? (String) value : "";
        } catch (Throwable t) {
            logError("Could not read system property " + key, t);
            return "";
        }
    }

    private static boolean isHyperOs() {
        return sHyperOsVersion != null && !sHyperOsVersion.isEmpty();
    }

    private static boolean isUsableSsid(String ssid) {
        return ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid);
    }

    private static String normalizeSsid(String ssid) {
        if (ssid != null && ssid.length() >= 2 && ssid.charAt(0) == '"'
                && ssid.charAt(ssid.length() - 1) == '"') {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private static boolean ssidEquals(String left, String right) {
        return right != null && right.equals(normalizeSsid(left));
    }

    private static String getToastText() {
        return "zh".equals(Locale.getDefault().getLanguage())
                ? TOAST_TEXT_ZH
                : TOAST_TEXT_EN;
    }

    private static void logInfo(String message) {
        sModule.log(Log.INFO, TAG, message);
    }

    private static void logError(String message, Throwable throwable) {
        sModule.log(Log.ERROR, TAG, message, throwable);
    }

    private static final class Attempt {
        final String ssid;
        volatile boolean authenticationFailed;
        volatile long failureAt;

        Attempt(String ssid) {
            this.ssid = ssid;
        }
    }
}
