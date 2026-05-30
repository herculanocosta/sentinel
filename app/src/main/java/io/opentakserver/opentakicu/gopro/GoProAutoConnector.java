package io.opentakserver.opentakicu.gopro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * GoPro connect helper, two phases:
 *   1. {@link #startScan(ScanListCallback)} — BLE-scan for ~5 s and report the LIST of GoPro
 *      cameras found (the user picks one; we don't auto-connect to the first).
 *   2. {@link #connectToDevice(BluetoothDevice, Callback)} — for the chosen camera: GATT connect →
 *      read Wi-Fi credentials → enable the Wi-Fi AP → join it → confirm the HTTP server answers.
 *
 * Every step has a short (~5 s) timeout and fails fast; the UI ({@link GoProDialogs}) surfaces a
 * Retry button rather than this class looping internally.
 */
public class GoProAutoConnector {
    private static final String TAG = "GoProAutoConnector";

    // Open GoPro BLE GATT UUIDs (https://gopro.github.io/OpenGoPro/ble/)
    private static final UUID CHAR_CMD_REQ          = UUID.fromString("b5f90072-aa8d-11e3-9046-0002a5d5c51b");
    private static final UUID CHAR_WIFI_AP_SSID     = UUID.fromString("b5f90002-aa8d-11e3-9046-0002a5d5c51b");
    private static final UUID CHAR_WIFI_AP_PASSWORD = UUID.fromString("b5f90003-aa8d-11e3-9046-0002a5d5c51b");

    /** GoPro default AP IPv4 — the camera is also the gateway/server. */
    public static final String GOPRO_HOST = "10.5.5.9";
    public static final int GOPRO_PORT = 8080;

    public enum State {
        IDLE, SCANNING_BLE, CONNECTING_GATT, READING, ENABLING_WIFI, CONNECTING_WIFI, CONNECTED, FAILED
    }

    /** Callback for the connect phase (one chosen device). */
    public interface Callback {
        void onState(@NonNull State state, @NonNull String detail);
        void onConnected(@NonNull Network goproNetwork, @NonNull String goproHost);
        void onFailed(@NonNull String reason);
    }

    /** Callback for the scan phase (returns the list of cameras to choose from). */
    public interface ScanListCallback {
        void onScanComplete(@NonNull List<Found> devices);
        void onScanFailed(@NonNull String reason);
    }

    /** A GoPro discovered during the BLE scan. */
    public static final class Found {
        public final BluetoothDevice device;
        public final String name;
        public final int rssi;
        Found(BluetoothDevice device, String name, int rssi) {
            this.device = device; this.name = name; this.rssi = rssi;
        }
    }

    private static final String PREF_GOPRO_MAC  = "gopro_ble_mac";
    private static final String PREF_GOPRO_SSID = "gopro_wifi_ssid";
    private static final String PREF_GOPRO_PASS = "gopro_wifi_password";

    /* Per-step budgets — short, so a hung step surfaces a Retry button within ~4 s. */
    private static final long BLE_SCAN_MS    = 5000;
    private static final long GATT_TIMEOUT_MS = 5000;   // BLE handshake needs a little headroom
    /** Budget for the join step. The GoPro AP is only just powering on after the BLE command, so
     *  the phone needs several seconds to associate on the FIRST attempt — too short and the user
     *  is forced to hit Retry every time. We poll for the network the whole time; the persistent
     *  Retry button stays available so nobody has to wait out the full budget if it's truly stuck. */
    private static final long WIFI_STEP_BUDGET_MS = 12000;
    /** Pause after the BLE "enable Wi-Fi" command BEFORE we attempt to join. This matters more than
     *  it looks: on some phones, issuing the Wi-Fi request while the GoPro AP is still powering up
     *  wedges the association in a state that never recovers (the reported "first run stuck on
     *  joining, Retry works" behaviour — by Retry time the AP is up). So we wait for the AP to
     *  actually be broadcasting first. GoPro HERO APs take ~3-4s to come up after the command. */
    private static final long AP_WARMUP_MS = 4000;
    private boolean wifiResolved = false;
    private boolean usedSystemNetwork = false;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BluetoothAdapter adapter;
    private final BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private ConnectivityManager.NetworkCallback netCallback;

    /** GATT operations are sequential — queue them so we don't step on a pending op. */
    private final Queue<Runnable> gattOps = new LinkedList<>();
    private boolean gattOpInFlight = false;

    private Callback callback;
    private ScanListCallback scanListCallback;
    private final Map<String, Found> found = new LinkedHashMap<>();

    private String ssid;
    private String password;
    private String macAddr;
    private boolean cancelled = false;
    private String savedBtName;

    public GoProAutoConnector(@NonNull Context ctx) {
        this.ctx = ctx.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm == null ? null : bm.getAdapter();
        this.scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
    }

    /** Cancel any in-flight scan / connect. Safe to call from the main thread. */
    public void cancel() {
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        unregisterNet();
        restoreBluetoothName();
    }

    /* ===================== Phase 1: SCAN → list ===================== */

    public void startScan(@NonNull ScanListCallback cb) {
        this.scanListCallback = cb;
        cancelled = false;
        if (!hasBluetoothPermissions()) { cb.onScanFailed("Bluetooth permission not granted"); return; }
        if (adapter == null) { cb.onScanFailed("Bluetooth not supported on this device"); return; }
        if (!adapter.isEnabled()) { cb.onScanFailed("Bluetooth is OFF — turn it on"); return; }
        if (scanner == null) { cb.onScanFailed("BLE scanner unavailable"); return; }

        // Show "SENTINEL" as the connecting app on the GoPro's status screen during the handshake.
        setBluetoothNameTemporary("SENTINEL");
        found.clear();
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCollectCallback);
        } catch (SecurityException e) {
            cb.onScanFailed("BLE scan denied: " + e.getMessage());
            return;
        }
        handler.postDelayed(() -> {
            if (cancelled) return;
            stopScan();
            scanListCallback.onScanComplete(new ArrayList<>(found.values()));
        }, BLE_SCAN_MS);
    }

    private final ScanCallback scanCollectCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
            if (name == null) name = safeGetName(device);
            if (name == null) return;
            if (!name.startsWith("GoPro ") && !name.startsWith("GP")) return;
            // Collect unique devices (by MAC); keep the strongest RSSI seen.
            Found prev = found.get(device.getAddress());
            if (prev == null || result.getRssi() > prev.rssi) {
                found.put(device.getAddress(), new Found(device, name, result.getRssi()));
            }
        }

        @Override public void onScanFailed(int errorCode) {
            if (scanListCallback != null && !cancelled) {
                handler.post(() -> scanListCallback.onScanFailed("BLE scan failed (code " + errorCode + ")"));
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void stopScan() {
        try { if (scanner != null) scanner.stopScan(scanCollectCallback); }
        catch (Exception ignored) {}
    }

    /* ===================== Phase 2: CONNECT to chosen device ===================== */

    @SuppressLint("MissingPermission")
    public void connectToDevice(@NonNull BluetoothDevice device, @NonNull Callback cb) {
        this.callback = cb;
        cancelled = false;
        wifiResolved = false;
        usedSystemNetwork = false;
        ssid = null; password = null;
        gattOps.clear(); gattOpInFlight = false;
        macAddr = device.getAddress();
        // Show "SENTINEL" on the GoPro screen (no-op if already set; covers a fresh connector on Retry).
        setBluetoothNameTemporary("SENTINEL");
        connectGatt(device);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected, discovering services");
                handler.postDelayed(() -> {
                    try { g.discoverServices(); } catch (Exception e) { fail("discoverServices: " + e); }
                }, 250);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected (status=" + status + ")");
                if (ssid != null && password != null) return;  // intentional, proceeding to Wi-Fi
                if (!cancelled) fail("Bluetooth dropped before reading Wi-Fi credentials (status " + status + ")");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Service discovery failed (" + status + ")"); return; }
            emit(State.READING, "Reading Wi-Fi credentials");
            enqueueRead(CHAR_WIFI_AP_SSID);
            enqueueRead(CHAR_WIFI_AP_PASSWORD);
            enqueueEnableWifi();
            kickGatt();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "char read " + c.getUuid() + " status " + status); return; }
                byte[] value = c.getValue();
                String text = value == null ? null : new String(value, StandardCharsets.UTF_8).trim();
                if (CHAR_WIFI_AP_SSID.equals(c.getUuid())) { ssid = text; Log.d(TAG, "GoPro SSID: " + ssid); }
                else if (CHAR_WIFI_AP_PASSWORD.equals(c.getUuid())) { password = text; Log.d(TAG, "GoPro pw len " + (password != null ? password.length() : 0)); }
            } finally {
                gattOpInFlight = false;
                kickGatt();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Log.d(TAG, "char write " + c.getUuid() + " status " + status);
            gattOpInFlight = false;
            kickGatt();
        }
    };

    @SuppressLint("MissingPermission")
    private void connectGatt(BluetoothDevice device) {
        emit(State.CONNECTING_GATT, "Connecting to GoPro…");
        gatt = device.connectGatt(ctx, false, gattCallback);
        handler.postDelayed(() -> {
            if (cancelled || ssid != null) return;
            if (gatt != null) fail("Bluetooth connect timed out");
        }, GATT_TIMEOUT_MS);
    }

    private void enqueueRead(UUID uuid) {
        gattOps.add(() -> {
            BluetoothGattCharacteristic c = findChar(uuid);
            if (c != null) { gattOpInFlight = true; gatt.readCharacteristic(c); }
        });
    }

    private void enqueueEnableWifi() {
        gattOps.add(() -> {
            BluetoothGattCharacteristic c = findChar(CHAR_CMD_REQ);
            if (c == null) { Log.w(TAG, "command characteristic not found"); return; }
            byte[] payload = new byte[]{0x03, 0x17, 0x01, 0x01};  // Open GoPro v2: Wi-Fi AP on
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            c.setValue(payload);
            gattOpInFlight = true;
            emit(State.ENABLING_WIFI, "Enabling camera Wi-Fi");
            gatt.writeCharacteristic(c);
            handler.postDelayed(this::onCredsReadyDisconnect, 600);
        });
    }

    @SuppressLint("MissingPermission")
    private BluetoothGattCharacteristic findChar(UUID uuid) {
        if (gatt == null) return null;
        for (BluetoothGattService svc : gatt.getServices()) {
            BluetoothGattCharacteristic c = svc.getCharacteristic(uuid);
            if (c != null) return c;
        }
        return null;
    }

    private void kickGatt() {
        if (gattOpInFlight) return;
        Runnable next = gattOps.poll();
        if (next != null) next.run();
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {}
        gatt = null;
    }

    private void onCredsReadyDisconnect() {
        if (ssid == null || password == null) { fail("Could not read the camera's Wi-Fi credentials"); return; }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(PREF_GOPRO_MAC, macAddr)
                .putString(PREF_GOPRO_SSID, ssid)
                .putString(PREF_GOPRO_PASS, password)
                .apply();
        closeGatt();
        // Wait for the AP to actually start broadcasting BEFORE attempting to join (see AP_WARMUP_MS).
        emit(State.ENABLING_WIFI, "Waiting for camera Wi-Fi to start…");
        handler.postDelayed(this::joinWifi, AP_WARMUP_MS);
    }

    /* ===================== Wi-Fi join ===================== */

    @SuppressLint("MissingPermission")
    private void joinWifi() {
        if (cancelled) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { fail("Wi-Fi join needs Android 10+"); return; }
        if (ssid == null || password == null) { fail("Missing Wi-Fi credentials"); return; }

        wifiResolved = false;
        emit(State.CONNECTING_WIFI, "Joining " + ssid + "… (this can take a few seconds)");

        // Path A — programmatic join (for networks the phone won't auto-connect to). We do NOT rely
        // on the specifier's onAvailable: on many devices the phone associates with the AP but the
        // callback never fires (especially when the SSID is a saved network the system auto-joins),
        // leaving the UI stuck on "Joining…". That's the reported bug.
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) { fail("ConnectivityManager unavailable"); return; }
        unregisterNet();
        try {
            WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid).setWpa2Passphrase(password).build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(@NonNull Network network) {
                    Log.d(TAG, "specifier onAvailable: " + network);
                    handler.post(() -> resolveWifi(network));
                }
                @Override public void onLost(@NonNull Network network) { Log.w(TAG, "Wi-Fi lost"); }
            };
            cm.requestNetwork(req, netCallback);
        } catch (Exception e) { Log.w(TAG, "requestNetwork", e); }

        // Path B — POLL for any Wi-Fi network on the GoPro's 10.5.5.x subnet. This catches the
        // connection regardless of HOW the phone joined (our specifier OR the system's saved
        // network), which is what the ATAK plugin does and what fixes the stuck-on-joining loop.
        pollGoProNetwork();

        // Fail-fast fallback if the phone never attaches to the AP.
        handler.postDelayed(() -> {
            if (!cancelled && !wifiResolved) {
                unregisterNet();
                fail("Couldn't reach the camera Wi-Fi. Make sure it's on and close, then Retry.");
            }
        }, WIFI_STEP_BUDGET_MS);
    }

    /** Poll every ~0.7s for a Wi-Fi network carrying a 10.5.5.x address (the GoPro AP). */
    private void pollGoProNetwork() {
        if (cancelled || wifiResolved) return;
        Network n = findGoProNetwork();
        if (n != null) { resolveWifi(n); return; }
        handler.postDelayed(this::pollGoProNetwork, 700);
    }

    private Network findGoProNetwork() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network systemFallback = null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                boolean isGoPro = false;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    byte[] a = la.getAddress().getAddress();
                    if (a != null && a.length == 4
                            && (a[0] & 0xFF) == 10 && (a[1] & 0xFF) == 5 && (a[2] & 0xFF) == 5) {
                        isGoPro = true; break;
                    }
                }
                if (!isGoPro) continue;
                // PREFER the app-bound (no-INTERNET) network — it never becomes the system default,
                // so it won't disable mobile data (we still need cellular for the broadcast). A
                // GoPro network that DOES advertise INTERNET is the system's *saved* copy of the
                // SSID, which Android can promote to default and kill mobile data — only fall back
                // to it if there's nothing better.
                boolean appBound = !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                Log.d(TAG, "GoPro net " + n + " appBound=" + appBound);
                if (appBound) { usedSystemNetwork = false; return n; }
                systemFallback = n;
            }
        } catch (Exception e) { Log.w(TAG, "findGoProNetwork", e); }
        if (systemFallback != null) {
            usedSystemNetwork = true;   // the UI will offer a "forget Wi-Fi" tip after connecting
            Log.w(TAG, "Only a SYSTEM (internet-capable) GoPro network found — mobile data may "
                    + "switch off. Forget the saved '" + ssid + "' Wi-Fi so only SENTINEL's app-bound "
                    + "connection is used.");
        }
        return systemFallback;
    }

    /** True if the connection landed on a SYSTEM (saved/internet-capable) Wi-Fi rather than the
     *  app-bound one — i.e. the saved-network situation that can disable mobile data. */
    public boolean usedSystemNetwork() { return usedSystemNetwork; }

    /**
     * The phone is on the GoPro subnet — hand the network off immediately. We do NOT probe the
     * camera's HTTP server here (that step used to hang on "Checking camera…"); {@link GoProSource}
     * / {@link GoProClient} already retry the HTTP control with their own watchdog.
     */
    private void resolveWifi(@NonNull Network network) {
        if (cancelled || wifiResolved) return;
        wifiResolved = true;   // stops the poll + the fallback timeout
        restoreBluetoothName();
        emit(State.CONNECTED, "Connected to " + (ssid != null ? ssid : "GoPro"));
        handler.post(() -> { if (callback != null) callback.onConnected(network, GOPRO_HOST); });
    }

    private void unregisterNet() {
        if (netCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            try { if (cm != null) cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            netCallback = null;
        }
    }

    /* ===================== helpers ===================== */

    @SuppressLint("MissingPermission")
    private void setBluetoothNameTemporary(String newName) {
        if (adapter == null) return;
        try {
            String current = adapter.getName();
            if (current != null && !current.equals(newName)) { savedBtName = current; adapter.setName(newName); }
        } catch (Exception e) { Log.w(TAG, "set BT name", e); }
    }

    @SuppressLint("MissingPermission")
    private void restoreBluetoothName() {
        if (adapter == null || savedBtName == null) return;
        try { adapter.setName(savedBtName); } catch (Exception e) { Log.w(TAG, "restore BT name", e); }
        savedBtName = null;
    }

    private void emit(State state, String detail) {
        handler.post(() -> { if (callback != null) callback.onState(state, detail); });
    }

    private void fail(String why) {
        Log.w(TAG, "fail: " + why);
        final Callback cb = callback;
        handler.post(() -> {
            if (cb != null) { cb.onState(State.FAILED, why); cb.onFailed(why); }
        });
        cancel();
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private String safeGetName(BluetoothDevice device) {
        try { return device.getName(); } catch (SecurityException e) { return null; }
    }
}
