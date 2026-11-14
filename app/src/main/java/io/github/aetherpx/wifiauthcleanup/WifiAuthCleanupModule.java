package io.github.aetherpx.wifiauthcleanup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Prevents Settings from keeping a Wi-Fi network whose password was entered incorrectly.
 *
 * <p>Android's {@code WifiManager.connect(WifiConfiguration, ...)} and {@code save(...)} persist the
 * supplied configuration before the authentication attempt is made.  When the password is wrong
 * the network therefore remains in the saved-network list.  This module hooks both methods,
 * remembers the configuration the user just submitted, and removes the network as soon as the
 * framework reports an authentication failure.</p>
 *
 * <p>Three complementary failure signals are used:</p>
 * <ol>
 *     <li>The MIUI/HyperOS {@code miui.intent.action.WIFI_CONNECTION_FAILURE} broadcast.  It
 *     carries the failed {@link WifiConfiguration}, which lets us remove exactly the network that
 *     was just tried.</li>
 *     <li>{@code SUPPLICANT_STATE_CHANGED_ACTION} with {@code ERROR_AUTHENTICATING}.  This is an
 *     explicit authentication failure and does not depend on the framework updating its hidden
 *     network-selection status first.</li>
 *     <li>Polling the configured networks and checking their
 *     {@code NetworkSelectionStatus}.  A network disabled with
 *     {@code DISABLED_AUTHENTICATION_FAILURE} or {@code DISABLED_BY_WRONG_PASSWORD} is removed.
 *     This also covers AOSP-style ROMs that do not send the MIUI broadcast.</li>
 * </ol>
 */
public final class WifiAuthCleanupModule extends XposedModule {

    private static final String TAG = "WifiAuthCleanup";
    private static final String TARGET_PACKAGE = "com.android.settings";

    /** MIUI/HyperOS broadcast sent after a connection/auth failure. */
    private static final String ACTION_MIUI_WIFI_CONNECTION_FAILURE =
            "miui.intent.action.WIFI_CONNECTION_FAILURE";
    private static final String EXTRA_WIFI_CONFIGURATION = "wifiConfiguration";
    private static final String EXTRA_IS_SLAVE = "isSlave";

    /**
     * {@code NetworkSelectionStatus} reasons that indicate an authentication failure.  Value 3 is
     * DISABLED_AUTHENTICATION_FAILURE, value 8 is DISABLED_BY_WRONG_PASSWORD.  They are kept as
     * literals because the constants are hidden API; they are stable in AOSP and are also used by
     * the decompiled Settings code.
     */
    private static final int DISABLED_AUTHENTICATION_FAILURE = 3;
    private static final int DISABLED_BY_WRONG_PASSWORD = 8;

    /**
     * A wrong password on a network that has connected before is reported with reason 3 rather
     * than 8. Keep this enabled so editing the password of an existing network is covered too.
     */
    private static final boolean REMOVE_ON_GENERIC_AUTH_FAILURE = true;

    private static final long ATTEMPT_TIMEOUT_MS = 120_000L;
    private static final long FIRST_POLL_DELAY_MS = 1_500L;
    private static final long POLL_INTERVAL_MS = 1_500L;
    private static final long NO_CONFIG_GRACE_MS = 10_000L;
    /**
     * How long a {@code WifiManager.save()} call marks a configuration as "just submitted by the
     * Settings UI".  The dialog always calls {@code save()} immediately before {@code connect()},
     * so this window only has to absorb a few milliseconds of scheduling latency.
     */
    private static final long RECENT_SAVE_WINDOW_MS = 15_000L;

    private static final String TOAST_TEXT = "Wi-Fi 密码错误，已取消保存该网络";

    private static final Map<String, Attempt> ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> RECENT_SAVES = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    /**
     * {@code WifiConfiguration#getNetworkSelectionStatus()} and
     * {@code NetworkSelectionStatus#getNetworkSelectionDisableReason()} are {@code @SystemApi}
     * hidden API, so they are not part of the public {@code android.jar} and are resolved
     * reflectively at runtime.
     */
    private static volatile Method sGetNetworkSelectionStatus;
    private static volatile Method sGetNetworkSelectionDisableReason;
    private static volatile boolean sDisableReasonUnavailable;

    private static volatile WifiAuthCleanupModule sModule;
    private static volatile WifiManager sWifiManager;
    private static volatile Context sContext;
    private static volatile Handler sWorker;
    private static volatile boolean sHooked;
    private static volatile boolean sSaveHooked;
    private static volatile boolean sReceiverRegistered;

    private static final Runnable POLL_RUNNABLE = WifiAuthCleanupModule::checkAttempts;

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (ACTION_MIUI_WIFI_CONNECTION_FAILURE.equals(action)) {
                onMiuiConnectionFailure(intent);
            } else if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
                int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);
                if (error == WifiManager.ERROR_AUTHENTICATING) {
                    onSupplicantAuthFailure();
                }
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (info != null && info.isConnected()) {
                    // WifiInfo exposes the target SSID as soon as association starts, before the
                    // password has actually been accepted. Only a CONNECTED NetworkInfo event is
                    // reliable enough to mark an attempt successful.
                    onNetworkConnected();
                }
            }
        }
    };

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sModule = this;
        logInfo("Loaded into " + param.getProcessName() + " (API " + getApiVersion() + ")");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        synchronized (LOCK) {
            if (sHooked) {
                return;
            }
            sHooked = true;
        }

        try {
            Class<?> wifiManagerClass =
                    Class.forName("android.net.wifi.WifiManager", false, param.getClassLoader());
            // WifiManager.ActionListener is hidden from the public Android SDK. Resolve it by
            // name so this project can compile against the normal platform android.jar.
            Class<?> actionListenerClass = Class.forName(
                    "android.net.wifi.WifiManager$ActionListener",
                    false,
                    param.getClassLoader());
            Method connect = wifiManagerClass.getDeclaredMethod(
                    "connect", WifiConfiguration.class, actionListenerClass);

            hook(connect)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        onConnect(chain.getThisObject(), (WifiConfiguration) chain.getArg(0));
                        return chain.proceed();
                    });

            logInfo("Hooked WifiManager.connect(WifiConfiguration, ActionListener)");

            // save() is hooked as well: the Settings dialog calls save() right before connect(),
            // and some "add network" flows only save and let the framework auto-join.  Tracking
            // saves also lets us tell a freshly entered password apart from a plain connect to an
            // already saved network.
            try {
                Method save = wifiManagerClass.getDeclaredMethod(
                        "save", WifiConfiguration.class, actionListenerClass);
                hook(save)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            onSave(chain.getThisObject(), (WifiConfiguration) chain.getArg(0));
                            return chain.proceed();
                        });
                sSaveHooked = true;
                logInfo("Hooked WifiManager.save(WifiConfiguration, ActionListener)");
            } catch (Throwable t) {
                logError("Failed to hook WifiManager.save", t);
            }
        } catch (Throwable t) {
            logError("Failed to install hooks", t);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Hooks
    // ---------------------------------------------------------------------------------------

    private static void onSave(Object thisObject, WifiConfiguration config) {
        if (!(thisObject instanceof WifiManager) || config == null) {
            return;
        }
        String ssid = normalizeSsid(config.SSID);
        if (!isUsableSsid(ssid) || !hasCredential(config)) {
            return;
        }

        WifiManager wifiManager = (WifiManager) thisObject;
        rememberSave(ssid);

        if (config.networkId == -1) {
            // Brand new network: not every Settings flow calls connect() after save(), so start
            // monitoring from the save itself.
            trackAttempt(wifiManager, ssid, "save");
        }
    }

    private static void onConnect(Object thisObject, WifiConfiguration config) {
        if (!(thisObject instanceof WifiManager) || config == null) {
            return;
        }
        String ssid = normalizeSsid(config.SSID);
        if (!isUsableSsid(ssid) || !hasCredential(config)) {
            // Open networks do not have a password to protect.
            return;
        }

        // A save() immediately followed by connect() means the user just submitted credentials.
        // If save() could not be hooked, fall back to tracking every connect() with credentials.
        if (config.networkId != -1 && sSaveHooked && !wasRecentlySaved(ssid)) {
            logInfo("Ignoring connect() to already saved network \"" + ssid + "\"");
            return;
        }

        trackAttempt((WifiManager) thisObject, ssid, "connect");
    }

    private static void trackAttempt(WifiManager wifiManager, String ssid, String source) {
        sWifiManager = wifiManager;
        if (sContext == null) {
            sContext = findContext(wifiManager);
        }
        registerReceiverIfPossible();

        ATTEMPTS.put(ssid, new Attempt(ssid));
        logInfo("Monitoring connection attempt for \"" + ssid + "\" (via " + source + ")");
        schedulePoll(FIRST_POLL_DELAY_MS);
    }

    private static boolean isUsableSsid(String ssid) {
        return !TextUtils.isEmpty(ssid) && !"<unknown ssid>".equalsIgnoreCase(ssid);
    }

    private static void rememberSave(String ssid) {
        long now = SystemClock.elapsedRealtime();
        RECENT_SAVES.entrySet().removeIf(
                entry -> now - entry.getValue() > RECENT_SAVE_WINDOW_MS);
        RECENT_SAVES.put(ssid, now);
    }

    private static boolean wasRecentlySaved(String ssid) {
        Long savedAt = RECENT_SAVES.get(ssid);
        return savedAt != null && SystemClock.elapsedRealtime() - savedAt <= RECENT_SAVE_WINDOW_MS;
    }

    // ---------------------------------------------------------------------------------------
    // Failure handling
    // ---------------------------------------------------------------------------------------

    private static void onMiuiConnectionFailure(Intent intent) {
        if (intent.getBooleanExtra(EXTRA_IS_SLAVE, false)) {
            // Secondary/slave Wi-Fi connections are not initiated through WifiManager.connect().
            return;
        }
        WifiConfiguration failedConfig = intent.getParcelableExtra(EXTRA_WIFI_CONFIGURATION);
        if (failedConfig == null) {
            schedulePoll(0L);
            return;
        }

        String ssid = normalizeSsid(failedConfig.SSID);
        if (TextUtils.isEmpty(ssid) || !ATTEMPTS.containsKey(ssid)) {
            // Not a connection attempt started by the Settings UI.
            return;
        }

        WifiManager wifiManager = sWifiManager;
        if (wifiManager == null) {
            return;
        }

        boolean removed = false;
        if (failedConfig.networkId >= 0) {
            removed = removeNetwork(wifiManager, failedConfig, "MIUI broadcast");
        }

        if (!removed) {
            removed = removeMatchingNetworks(wifiManager, ssid, failedConfig.networkId);
        }

        if (removed) {
            if (ATTEMPTS.remove(ssid) != null) {
                showRemovedToast(ssid);
            }
        } else {
            // The framework may not have written the disabled status yet; let the poller retry.
            logInfo("Could not remove \"" + ssid + "\" from the MIUI broadcast, polling");
            schedulePoll(0L);
        }
    }

    private static void checkAttempts() {
        if (ATTEMPTS.isEmpty()) {
            return;
        }
        WifiManager wifiManager = sWifiManager;
        if (wifiManager == null) {
            return;
        }

        List<WifiConfiguration> configured = null;
        try {
            configured = wifiManager.getConfiguredNetworks();
        } catch (Throwable t) {
            logError("getConfiguredNetworks failed", t);
        }

        long now = SystemClock.elapsedRealtime();
        boolean keepPolling = false;

        Iterator<Map.Entry<String, Attempt>> iterator = ATTEMPTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Attempt> entry = iterator.next();
            Attempt attempt = entry.getValue();

            if (now - attempt.startedAt > ATTEMPT_TIMEOUT_MS) {
                iterator.remove();
                logInfo("Stopped monitoring \"" + attempt.ssid + "\" (timeout)");
                continue;
            }

            if (configured == null || configured.isEmpty()) {
                keepPolling = true;
                continue;
            }

            boolean handled = false;
            boolean removed = false;
            boolean found = false;
            for (WifiConfiguration config : configured) {
                if (!ssidEquals(config.SSID, attempt.ssid)) {
                    continue;
                }
                found = true;
                int reason = getDisableReason(config);
                if (attempt.authFailureSeen
                        || reason == DISABLED_BY_WRONG_PASSWORD
                        || (REMOVE_ON_GENERIC_AUTH_FAILURE
                        && reason == DISABLED_AUTHENTICATION_FAILURE)) {
                    handled = true;
                    logInfo("Authentication failure for \"" + attempt.ssid
                            + "\" (supplicant=" + attempt.authFailureSeen
                            + ", reason=" + reason + ", netId=" + config.networkId + ")");
                    if (removeNetwork(wifiManager, config, "poll")) {
                        removed = true;
                    }
                }
            }

            if (!found && now - attempt.startedAt > NO_CONFIG_GRACE_MS) {
                // The network is no longer saved, e.g. it was already removed by another path.
                iterator.remove();
                logInfo("Network \"" + attempt.ssid + "\" is no longer configured");
            } else if (removed) {
                iterator.remove();
                showRemovedToast(attempt.ssid);
            } else if (handled) {
                // The framework can briefly reject removeNetwork() while it is still finishing
                // the failed connection. Keep retrying until the attempt timeout instead of
                // abandoning the network after the first false return value.
                keepPolling = true;
                logInfo("Network \"" + attempt.ssid
                        + "\" reported an auth failure; removal will be retried");
            } else {
                keepPolling = true;
            }
        }

        if (!ATTEMPTS.isEmpty() && keepPolling) {
            schedulePoll(POLL_INTERVAL_MS);
        }
    }

    private static void onSupplicantAuthFailure() {
        if (ATTEMPTS.isEmpty()) {
            logInfo("Supplicant reported an authentication failure with no active attempt");
            return;
        }

        String currentSsid = null;
        WifiManager wifiManager = sWifiManager;
        if (wifiManager != null) {
            try {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    currentSsid = normalizeSsid(info.getSSID());
                }
            } catch (Throwable t) {
                logError("getConnectionInfo failed after authentication failure", t);
            }
        }

        Attempt failed = isUsableSsid(currentSsid) ? ATTEMPTS.get(currentSsid) : null;
        if (failed == null && !isUsableSsid(currentSsid) && ATTEMPTS.size() == 1) {
            // Some ROMs clear WifiInfo before delivering ERROR_AUTHENTICATING. There can only be
            // one foreground Settings connection at a time, so the sole active attempt is safe.
            failed = ATTEMPTS.values().iterator().next();
        }

        if (failed != null) {
            failed.authFailureSeen = true;
            logInfo("Supplicant authentication failure matched \"" + failed.ssid + "\"");
        } else {
            logInfo("Supplicant authentication failure did not match an active attempt"
                    + (currentSsid == null ? "" : " (SSID=\"" + currentSsid + "\")"));
        }
        schedulePoll(0L);
    }

    private static void onNetworkConnected() {
        if (ATTEMPTS.isEmpty()) {
            return;
        }
        WifiManager wifiManager = sWifiManager;
        if (wifiManager == null) {
            return;
        }

        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            String connectedSsid = info == null ? null : normalizeSsid(info.getSSID());
            if (!isUsableSsid(connectedSsid)) {
                logInfo("CONNECTED event had no usable SSID; keeping attempts active");
                return;
            }

            Attempt completed = ATTEMPTS.remove(connectedSsid);
            if (completed != null) {
                logInfo("Connection to \"" + connectedSsid
                        + "\" reached NetworkInfo.CONNECTED");
            }
        } catch (Throwable t) {
            logError("Could not process CONNECTED event", t);
        }
    }

    private static boolean removeMatchingNetworks(WifiManager wifiManager, String ssid, int netId) {
        boolean removed = false;
        List<WifiConfiguration> configured;
        try {
            configured = wifiManager.getConfiguredNetworks();
        } catch (Throwable t) {
            logError("getConfiguredNetworks failed while removing", t);
            return false;
        }
        if (configured == null) {
            return false;
        }
        for (WifiConfiguration config : configured) {
            if (!ssidEquals(config.SSID, ssid)) {
                continue;
            }
            int reason = getDisableReason(config);
            if (config.networkId == netId
                    || reason == DISABLED_BY_WRONG_PASSWORD
                    || (REMOVE_ON_GENERIC_AUTH_FAILURE
                    && reason == DISABLED_AUTHENTICATION_FAILURE)) {
                if (removeNetwork(wifiManager, config, "fallback")) {
                    removed = true;
                }
            }
        }
        return removed;
    }

    private static boolean removeNetwork(WifiManager wifiManager,
                                         WifiConfiguration config,
                                         String source) {
        if (config == null || config.networkId < 0) {
            return false;
        }
        try {
            boolean removed = wifiManager.removeNetwork(config.networkId);
            logInfo("removeNetwork(" + config.networkId + ") via " + source + " -> " + removed);
            return removed;
        } catch (Throwable t) {
            logError("removeNetwork(" + config.networkId + ") failed", t);
            return false;
        }
    }

    private static int getDisableReason(WifiConfiguration config) {
        if (sDisableReasonUnavailable) {
            return -1;
        }
        try {
            Method getStatus = sGetNetworkSelectionStatus;
            if (getStatus == null) {
                getStatus = WifiConfiguration.class.getDeclaredMethod("getNetworkSelectionStatus");
                getStatus.setAccessible(true);
                sGetNetworkSelectionStatus = getStatus;
            }
            Object status = getStatus.invoke(config);
            if (status == null) {
                return -1;
            }

            Method getReason = sGetNetworkSelectionDisableReason;
            if (getReason == null) {
                getReason = status.getClass().getDeclaredMethod(
                        "getNetworkSelectionDisableReason");
                getReason.setAccessible(true);
                sGetNetworkSelectionDisableReason = getReason;
            }
            Object reason = getReason.invoke(status);
            return reason instanceof Integer ? (Integer) reason : -1;
        } catch (NoSuchMethodException | IllegalAccessException | SecurityException e) {
            // Hidden API access is unavailable in this process.  Keep the MIUI broadcast path
            // working and stop calling into reflection on every poll.
            sDisableReasonUnavailable = true;
            logError("NetworkSelectionStatus is not accessible", e);
            return -1;
        } catch (Throwable t) {
            logError("Unable to read NetworkSelectionStatus", t);
            return -1;
        }
    }

    private static void showRemovedToast(String ssid) {
        logInfo("Removed network \"" + ssid + "\" because authentication failed");
        Context context = sContext;
        if (context == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(context, TOAST_TEXT, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                logError("Toast failed", t);
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

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
                filter.addAction(ACTION_MIUI_WIFI_CONNECTION_FAILURE);
                filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
                filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // The MIUI/HyperOS WIFI_CONNECTION_FAILURE broadcast may be sent by another
                    // process (Settings itself registers an exported receiver for it), so the
                    // receiver has to be exported.  The handler validates the SSID against an
                    // active Settings connection attempt before removing anything.
                    context.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(RECEIVER, filter);
                }
                sReceiverRegistered = true;
                logInfo("Broadcast receiver registered");
            } catch (Throwable t) {
                logError("registerReceiver failed", t);
            }
        }
    }

    private static void schedulePoll(long delayMs) {
        Handler worker = ensureWorker();
        if (worker == null) {
            return;
        }
        worker.removeCallbacks(POLL_RUNNABLE);
        worker.postDelayed(POLL_RUNNABLE, Math.max(0L, delayMs));
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
                sWorker = new Handler(thread.getLooper());
            }
            return sWorker;
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
            // No context available; polling still works, only the toast is skipped.
        }
        return null;
    }

    private static boolean hasCredential(WifiConfiguration config) {
        if (!TextUtils.isEmpty(config.preSharedKey)) {
            return true;
        }
        if (config.wepKeys != null) {
            for (String key : config.wepKeys) {
                if (!TextUtils.isEmpty(key)) {
                    return true;
                }
            }
        }
        return config.enterpriseConfig != null;
    }

    private static String normalizeSsid(String ssid) {
        if (ssid == null) {
            return null;
        }
        if (ssid.length() >= 2 && ssid.charAt(0) == '"'
                && ssid.charAt(ssid.length() - 1) == '"') {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private static boolean ssidEquals(String left, String right) {
        String normalizedLeft = normalizeSsid(left);
        String normalizedRight = normalizeSsid(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private static void logInfo(String message) {
        WifiAuthCleanupModule module = sModule;
        if (module != null) {
            module.log(Log.INFO, TAG, message);
        } else {
            Log.i(TAG, message);
        }
    }

    private static void logError(String message, Throwable throwable) {
        WifiAuthCleanupModule module = sModule;
        if (module != null) {
            module.log(Log.ERROR, TAG, message, throwable);
        } else {
            Log.e(TAG, message, throwable);
        }
    }

    private static final class Attempt {
        final String ssid;
        final long startedAt;
        volatile boolean authFailureSeen;

        Attempt(String ssid) {
            this.ssid = ssid;
            this.startedAt = SystemClock.elapsedRealtime();
        }
    }
}
