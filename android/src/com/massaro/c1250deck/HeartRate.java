package com.massaro.c1250deck;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bluetooth LE heart rate (standard Heart Rate service 0x180D): an Apple Watch running a
 * broadcast app (BlueHeart, HeartCast, ECHO) or any chest strap. Remembers the last device
 * and reconnects to it on launch.
 */
@SuppressLint("MissingPermission")
public class HeartRate {
    public interface Sink { void send(JSONObject msg); }

    static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID HR_MEASURE = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int PERM_REQUEST = 4211;

    private final Activity act;
    private final Sink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Map<String, JSONObject> found = new LinkedHashMap<>();

    private BluetoothGatt gatt;
    private String wantAddress;
    private String wantName = "";
    private boolean scanning;
    private Runnable afterPermission;

    public HeartRate(Activity a, Sink s) {
        act = a;
        sink = s;
        prefs = a.getSharedPreferences("hr", Context.MODE_PRIVATE);
    }

    public String savedName() { return prefs.getString("name", ""); }

    /** Reconnect to the remembered device, if there is one. */
    public void resume() {
        String addr = prefs.getString("address", null);
        if (addr != null) withPermission(() -> connect(addr, prefs.getString("name", "Heart rate")));
    }

    public void scan() { withPermission(this::doScan); }

    public void connectTo(String address) {
        JSONObject d = found.get(address);
        String name = d != null ? d.optString("name", "Heart rate") : "Heart rate";
        stopScan();
        withPermission(() -> connect(address, name));
    }

    public void forget() {
        prefs.edit().clear().apply();
        wantAddress = null;
        closeGatt();
        status("idle", "", "");
    }

    public void shutdown() { stopScan(); wantAddress = null; closeGatt(); }

    // ---------- permissions ----------

    private String[] needed() {
        if (Build.VERSION.SDK_INT >= 31) return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void withPermission(Runnable r) {
        List<String> missing = new ArrayList<>();
        for (String p : needed()) if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) { r.run(); return; }
        afterPermission = r;
        act.requestPermissions(missing.toArray(new String[0]), PERM_REQUEST);
    }

    public void onPermissionResult(int code, int[] results) {
        if (code != PERM_REQUEST) return;
        boolean ok = results.length > 0;
        for (int x : results) ok &= x == PackageManager.PERMISSION_GRANTED;
        Runnable r = afterPermission;
        afterPermission = null;
        if (ok && r != null) r.run();
        else status("error", "", "Bluetooth permission was declined. Run the install script again to grant it.");
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    // ---------- scanning ----------

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String name = d.getName();
            if (name == null && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
            try {
                JSONObject o = new JSONObject();
                o.put("id", d.getAddress());
                o.put("name", name != null ? name : "Heart rate monitor");
                o.put("rssi", r.getRssi());
                found.put(d.getAddress(), o);
            } catch (Exception ignored) {}
            sendDevices();
        }
        @Override public void onScanFailed(int code) {
            scanning = false;
            status("error", "", "Bluetooth scan failed (code " + code + "). Try again in a moment.");
        }
    };

    private void doScan() {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { status("error", "", "Bluetooth is off on the treadmill. Turn it on in Settings."); return; }
        BluetoothLeScanner s = a.getBluetoothLeScanner();
        if (s == null) { status("error", "", "Bluetooth scanning isn't available."); return; }
        stopScan();
        found.clear();
        sendDevices();
        List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HR_SERVICE)).build());
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        s.startScan(filters, settings, scanCb);
        scanning = true;
        status("scanning", "", "");
        ui.postDelayed(() -> { if (scanning) { stopScan(); status(gatt != null ? "connected" : "idle", wantName, ""); } }, 12000);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        BluetoothAdapter a = adapter();
        if (a != null && a.getBluetoothLeScanner() != null) {
            try { a.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) {}
        }
    }

    private void sendDevices() {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "devices");
            m.put("list", new JSONArray(found.values()));
            sink.send(m);
        } catch (Exception ignored) {}
    }

    // ---------- connection ----------

    private void connect(String address, String name) {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { status("error", name, "Bluetooth is off on the treadmill."); return; }
        closeGatt();
        wantAddress = address;
        wantName = name;
        prefs.edit().putString("address", address).putString("name", name).apply();
        status("connecting", name, "");
        BluetoothDevice dev = a.getRemoteDevice(address);
        gatt = dev.connectGatt(act, true, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    private void closeGatt() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (wantAddress != null) {
                    status("connecting", wantName, "Lost the signal. Reconnecting…");
                    // autoConnect=true keeps trying in the background; reconnect explicitly if the stack gave up
                    if (st != BluetoothGatt.GATT_SUCCESS) {
                        final String addr = wantAddress, name = wantName;
                        ui.postDelayed(() -> { if (addr.equals(wantAddress)) connect(addr, name); }, 4000);
                    }
                }
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService svc = g.getService(HR_SERVICE);
            BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(HR_MEASURE);
            if (c == null) { status("error", wantName, "That device doesn't share heart rate."); return; }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d != null) {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
            }
            status("connected", wantName, "");
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            if (HR_MEASURE.equals(c.getUuid())) bpm(v);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33 && HR_MEASURE.equals(c.getUuid())) bpm(c.getValue());
        }
    };

    /** Heart Rate Measurement: flags byte, then uint8 or uint16 bpm (flag bit 0). */
    static int parse(byte[] v) {
        if (v == null || v.length < 2) return -1;
        boolean u16 = (v[0] & 1) != 0;
        if (u16) return v.length >= 3 ? ((v[1] & 0xff) | ((v[2] & 0xff) << 8)) : -1;
        return v[1] & 0xff;
    }

    private void bpm(byte[] v) {
        int b = parse(v);
        if (b <= 0) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "bpm");
            m.put("bpm", b);
            m.put("name", wantName);
            sink.send(m);
        } catch (Exception ignored) {}
    }

    private void status(String state, String name, String message) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "status");
            m.put("state", state);
            m.put("name", name);
            m.put("message", message);
            m.put("needsLocation", Build.VERSION.SDK_INT < 31);
            sink.send(m);
        } catch (Exception ignored) {}
    }
}
