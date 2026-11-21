package com.vrcdevicecheck.osc.media;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 连接手环并订阅标准 BLE 心率服务。
 *
 * 只走蓝牙 SIG 的标准 HRS（0x180D / 0x2A37），不需要厂家 SDK：
 * 但手环必须开放这个标准服务，而且通常不能同时被官方运动健康 App 占着连接。
 *
 * 连接生命周期用 acquire / release 计数：上报服务和心率设置页都可以持有，
 * 两边都释放后才断开，避免离开设置页时把正在上报的连接断掉。
 */
public final class HeartRateManager {

    public static final int STATE_IDLE = 0;
    public static final int STATE_NO_DEVICE = 1;
    public static final int STATE_PERMISSION = 2;
    public static final int STATE_BLUETOOTH_OFF = 3;
    public static final int STATE_CONNECTING = 4;
    public static final int STATE_WAITING = 5;
    public static final int STATE_CONNECTED = 6;
    public static final int STATE_NO_SERVICE = 7;
    public static final int STATE_ERROR = 8;

    private static final UUID HRS_SERVICE =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HRS_MEASUREMENT =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long CONNECT_TIMEOUT_MS = 15000L;
    private static final long FRESH_MS = 60000L;

    /** 上游 compileSdk 30 没有这个常量，用字符串；API 31+ 运行时才检查 */
    private static final String PERMISSION_BLUETOOTH_CONNECT =
            "android.permission.BLUETOOTH_CONNECT";

    private static final String PREFS = "vrcdi_heart_rate";
    private static final String KEY_NAME = "device_name";
    private static final String KEY_ADDRESS = "device_address";

    public interface Listener {
        void onHeartRateUpdate();
    }

    private static HeartRateManager instance;
    private static final Object LOCK = new Object();

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences prefs;

    private int acquireCount;
    private volatile boolean desired;
    private volatile int state = STATE_IDLE;
    private volatile int gattStatus;
    private volatile int bpm;
    private volatile long bpmAt;
    private String deviceName;
    private String deviceAddress;

    private BluetoothGatt gatt;
    private boolean closing;

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (state == STATE_CONNECTING) {
                fail(STATE_ERROR, gattStatus);
                closeGatt();
            }
        }
    };

    private HeartRateManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.deviceName = prefs.getString(KEY_NAME, "");
        this.deviceAddress = prefs.getString(KEY_ADDRESS, "");
    }

    private static HeartRateManager get(Context context) {
        synchronized (LOCK) {
            if (instance == null) instance = new HeartRateManager(context);
            return instance;
        }
    }

    public static void acquire(Context context) {
        HeartRateManager manager = get(context);
        synchronized (LOCK) {
            manager.acquireCount++;
            manager.desired = true;
        }
        manager.main.post(new Runnable() {
            @Override
            public void run() {
                manager.connectIfNeeded();
            }
        });
    }

    public static void release() {
        HeartRateManager manager = instance;
        if (manager == null) return;
        synchronized (LOCK) {
            if (manager.acquireCount > 0) manager.acquireCount--;
            if (manager.acquireCount > 0) return;
            manager.desired = false;
        }
        manager.main.post(new Runnable() {
            @Override
            public void run() {
                manager.disconnectInternal(STATE_IDLE);
            }
        });
    }

    public static void selectDevice(Context context, String name, String address) {
        HeartRateManager manager = get(context);
        manager.deviceName = name == null ? "" : name.trim();
        manager.deviceAddress = address == null ? "" : address.trim();
        manager.prefs.edit()
                .putString(KEY_NAME, manager.deviceName)
                .putString(KEY_ADDRESS, manager.deviceAddress)
                .apply();
        manager.main.post(new Runnable() {
            @Override
            public void run() {
                manager.disconnectInternal(STATE_IDLE);
                if (manager.desired) manager.connectIfNeeded();
                manager.notifyListeners();
            }
        });
    }

    public static void reconnect() {
        HeartRateManager manager = instance;
        if (manager == null) return;
        manager.main.post(new Runnable() {
            @Override
            public void run() {
                manager.disconnectInternal(STATE_IDLE);
                if (manager.desired) manager.connectIfNeeded();
            }
        });
    }

    /** 最近 60 秒内收到的心率；没有或已过期返回 0 */
    public static int getFreshBpm() {
        HeartRateManager manager = instance;
        if (manager == null) return 0;
        if (manager.bpm <= 0) return 0;
        if (SystemClock.elapsedRealtime() - manager.bpmAt > FRESH_MS) return 0;
        return manager.bpm;
    }

    public static boolean hasSelectedDevice(Context context) {
        HeartRateManager manager = get(context);
        return manager.deviceAddress != null && !manager.deviceAddress.trim().isEmpty();
    }

    public static String getSelectedName(Context context) {
        return get(context).deviceName == null ? "" : get(context).deviceName;
    }

    public static int getState() {
        HeartRateManager manager = instance;
        return manager == null ? STATE_IDLE : manager.state;
    }

    public static int getGattStatus() {
        HeartRateManager manager = instance;
        return manager == null ? 0 : manager.gattStatus;
    }

    public static void addListener(Listener listener) {
        HeartRateManager manager = instance;
        if (manager != null && listener != null && !manager.listeners.contains(listener)) {
            manager.listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        HeartRateManager manager = instance;
        if (manager != null) manager.listeners.remove(listener);
    }

    private void connectIfNeeded() {
        if (!desired) return;
        if (state == STATE_CONNECTING || state == STATE_WAITING || state == STATE_CONNECTED) return;
        if (deviceAddress == null || deviceAddress.trim().isEmpty()) {
            fail(STATE_NO_DEVICE, 0);
            return;
        }
        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            fail(STATE_BLUETOOTH_OFF, 0);
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(
                PERMISSION_BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            fail(STATE_PERMISSION, 0);
            return;
        }

        closeGatt();
        closing = false;
        setState(STATE_CONNECTING, 0);
        try {
            BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
            if (deviceName == null || deviceName.isEmpty()) {
                deviceName = device.getName() == null ? "" : device.getName();
            }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                fail(STATE_ERROR, 0);
                return;
            }
            main.removeCallbacks(connectTimeout);
            main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        } catch (Throwable t) {
            fail(STATE_ERROR, 0);
            closeGatt();
        }
    }

    private void disconnectInternal(int endState) {
        main.removeCallbacks(connectTimeout);
        bpm = 0;
        bpmAt = 0;
        closeGatt();
        setState(endState, 0);
    }

    private void closeGatt() {
        BluetoothGatt current = gatt;
        gatt = null;
        if (current == null) return;
        closing = true;
        try {
            current.disconnect();
        } catch (Throwable ignored) {
            // 权限被撤销等
        }
        try {
            current.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void fail(int newState, int status) {
        main.removeCallbacks(connectTimeout);
        setState(newState, status);
    }

    private void setState(int newState, int status) {
        state = newState;
        gattStatus = status;
        notifyListeners();
    }

    private void notifyListeners() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    try {
                        listener.onHeartRateUpdate();
                    } catch (Throwable ignored) {
                        // 单个界面刷新失败不影响连接
                    }
                }
            }
        });
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status,
                                            int newState) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        setState(STATE_CONNECTING, 0);
                        try {
                            gatt.discoverServices();
                        } catch (Throwable t) {
                            fail(STATE_ERROR, 0);
                            closeGatt();
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (closing) {
                            closing = false;
                            return;
                        }
                        bpm = 0;
                        bpmAt = 0;
                        closeGatt();
                        if (desired) {
                            fail(STATE_ERROR, status);
                        } else {
                            setState(STATE_IDLE, 0);
                        }
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail(STATE_NO_SERVICE, status);
                        closeGatt();
                        return;
                    }
                    BluetoothGattService service = gatt.getService(HRS_SERVICE);
                    BluetoothGattCharacteristic measurement = service == null
                            ? null : service.getCharacteristic(HRS_MEASUREMENT);
                    if (measurement == null) {
                        fail(STATE_NO_SERVICE, 0);
                        closeGatt();
                        return;
                    }
                    try {
                        if (!gatt.setCharacteristicNotification(measurement, true)) {
                            fail(STATE_ERROR, 0);
                            closeGatt();
                            return;
                        }
                        BluetoothGattDescriptor descriptor = measurement.getDescriptor(CCCD);
                        if (descriptor == null) {
                            setState(STATE_WAITING, 0);
                            return;
                        }
                        // 只使用 compileSdk 30 就有的旧接口；
                        // targetSdk 30 在 Android 13+ 仍会走旧版回调
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    } catch (Throwable t) {
                        fail(STATE_ERROR, 0);
                        closeGatt();
                    }
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    main.removeCallbacks(connectTimeout);
                    if (state == STATE_ERROR) return;
                    setState(STATE_WAITING, 0);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            handleMeasurement(characteristic.getValue());
        }

    };

    private void handleMeasurement(byte[] value) {
        final int parsed = HeartRateParser.parse(value);
        if (parsed <= 0) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                main.removeCallbacks(connectTimeout);
                bpm = parsed;
                bpmAt = SystemClock.elapsedRealtime();
                setState(STATE_CONNECTED, 0);
            }
        });
    }
}