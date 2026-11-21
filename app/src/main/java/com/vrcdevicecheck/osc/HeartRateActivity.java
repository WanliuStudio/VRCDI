package com.vrcdevicecheck.osc;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vrcdevicecheck.osc.media.HeartRateManager;
import com.vrcdevicecheck.osc.theme.ThemeManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 心率手环设置：扫描附近 BLE 设备，选择手环并显示实时心率。
 *
 * 这里只使用蓝牙标准心率服务；手环必须开放 0x180D，且通常需要
 * 先断开与官方运动健康 App 的连接（一个手环一般只能连一个主机）。
 */
public class HeartRateActivity extends BaseActivity {

    private static final int REQUEST_PERMISSIONS = 41;
    private static final long SCAN_MS = 10000L;
    private static final int MAX_DEVICES = 20;
    /** compileSdk 30 里没有这两个常量，用字符串声明，运行时由系统判断 */
    private static final String PERMISSION_BLUETOOTH_SCAN =
            "android.permission.BLUETOOTH_SCAN";
    private static final String PERMISSION_BLUETOOTH_CONNECT =
            "android.permission.BLUETOOTH_CONNECT";

    private TextView statusView;
    private TextView selectedView;
    private TextView hintView;
    private TextView listTitle;
    private LinearLayout deviceList;
    private Button scanButton;
    private Button reconnectButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String> found = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private boolean scanning;

    private final HeartRateManager.Listener listener = new HeartRateManager.Listener() {
        @Override
        public void onHeartRateUpdate() {
            refreshUi();
        }
    };

    private final Runnable stopScanRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null) return;
            String address = null;
            String name = null;
            try {
                ScanRecord record = result.getScanRecord();
                if (record != null) name = record.getDeviceName();
                if ((name == null || name.trim().isEmpty()) && result.getDevice() != null) {
                    name = result.getDevice().getName();
                }
                if (result.getDevice() != null) address = result.getDevice().getAddress();
            } catch (Throwable ignored) {
                // 权限异常等，跳过这个结果
            }
            if (name == null || name.trim().isEmpty() || address == null) return;
            String cleanName = name.trim();
            if (found.containsKey(address) || found.size() >= MAX_DEVICES) return;
            found.put(address, cleanName);
            rebuildDeviceList();
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            scanButton.setEnabled(true);
            statusView.setText(R.string.heart_rate_scan_failed);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.heart_rate_title);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        statusView = new TextView(this);
        statusView.setTextSize(16f);
        statusView.setTextColor(getColor(R.color.text_primary));
        root.addView(statusView);

        selectedView = new TextView(this);
        selectedView.setTextSize(12f);
        selectedView.setTextColor(getColor(R.color.text_secondary));
        selectedView.setPadding(0, dp(6), 0, dp(10));
        root.addView(selectedView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        scanButton = new Button(this);
        scanButton.setText(R.string.heart_rate_scan);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ensurePermissions()) startScan();
            }
        });
        buttons.addView(scanButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        reconnectButton = new Button(this);
        reconnectButton.setText(R.string.heart_rate_reconnect);
        reconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HeartRateManager.reconnect();
                refreshUi();
            }
        });
        LinearLayout.LayoutParams reconnectParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        reconnectParams.setMarginStart(dp(8));
        buttons.addView(reconnectButton, reconnectParams);
        root.addView(buttons);

        hintView = new TextView(this);
        hintView.setText(R.string.heart_rate_hint);
        hintView.setTextSize(12f);
        hintView.setTextColor(getColor(R.color.text_secondary));
        hintView.setPadding(0, dp(12), 0, dp(6));
        root.addView(hintView);

        listTitle = new TextView(this);
        listTitle.setText(R.string.heart_rate_devices_found);
        listTitle.setTextSize(12f);
        listTitle.setTextColor(getColor(R.color.text_secondary));
        listTitle.setVisibility(View.GONE);
        root.addView(listTitle);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList);

        ThemeManager.apply(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HeartRateManager.acquire(this);
        HeartRateManager.addListener(listener);
        refreshUi();
    }

    @Override
    protected void onPause() {
        stopScan();
        HeartRateManager.removeListener(listener);
        HeartRateManager.release();
        super.onPause();
    }

    private boolean ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(missing, PERMISSION_BLUETOOTH_SCAN);
            addIfMissing(missing, PERMISSION_BLUETOOTH_CONNECT);
        }
        addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        if (missing.isEmpty()) return true;
        requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        return false;
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (granted) {
            startScan();
        } else {
            statusView.setText(R.string.heart_rate_permission_denied);
        }
    }

    private void startScan() {
        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            statusView.setText(R.string.heart_rate_status_bluetooth_off);
            requestEnableBluetooth();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(
                PERMISSION_BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            statusView.setText(R.string.heart_rate_status_permission);
            return;
        }

        stopScan();
        found.clear();
        rebuildDeviceList();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            statusView.setText(R.string.heart_rate_scan_failed);
            return;
        }
        scanning = true;
        scanButton.setEnabled(false);
        statusView.setText(R.string.heart_rate_scanning);
        try {
            scanner.startScan(scanCallback);
            handler.postDelayed(stopScanRunnable, SCAN_MS);
        } catch (Throwable t) {
            scanning = false;
            scanButton.setEnabled(true);
            statusView.setText(R.string.heart_rate_status_permission);
        }
    }

    private void stopScan() {
        handler.removeCallbacks(stopScanRunnable);
        if (scanning) {
            try {
                if (scanner != null) scanner.stopScan(scanCallback);
            } catch (Throwable ignored) {
                // 权限被撤销等
            }
        }
        scanning = false;
        if (scanButton != null) scanButton.setEnabled(true);
        if (found.isEmpty()) {
            statusView.setText(R.string.heart_rate_scan_empty);
        } else {
            refreshUi();
        }
    }

    private void rebuildDeviceList() {
        if (deviceList == null) return;
        deviceList.removeAllViews();
        listTitle.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
        for (final Map.Entry<String, String> entry : found.entrySet()) {
            Button button = new Button(this);
            button.setText(entry.getValue());
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HeartRateManager.selectDevice(HeartRateActivity.this,
                            entry.getValue(), entry.getKey());
                    stopScan();
                    refreshUi();
                }
            });
            deviceList.addView(button);
        }
    }

    private void refreshUi() {
        if (statusView == null) return;
        String name = HeartRateManager.getSelectedName(this);
        int state = HeartRateManager.getState();
        int bpm = HeartRateManager.getFreshBpm();

        selectedView.setText(name.isEmpty()
                ? getString(R.string.heart_rate_status_no_device)
                : getString(R.string.heart_rate_selected, name));
        reconnectButton.setEnabled(!name.isEmpty());

        switch (state) {
            case HeartRateManager.STATE_NO_DEVICE:
                statusView.setText(R.string.heart_rate_status_no_device);
                break;
            case HeartRateManager.STATE_PERMISSION:
                statusView.setText(R.string.heart_rate_status_permission);
                break;
            case HeartRateManager.STATE_BLUETOOTH_OFF:
                statusView.setText(R.string.heart_rate_status_bluetooth_off);
                break;
            case HeartRateManager.STATE_CONNECTING:
                statusView.setText(getString(R.string.heart_rate_status_connecting, name));
                break;
            case HeartRateManager.STATE_WAITING:
                statusView.setText(getString(R.string.heart_rate_status_waiting, name));
                break;
            case HeartRateManager.STATE_CONNECTED:
                statusView.setText(bpm > 0
                        ? getString(R.string.heart_rate_status_connected, name, bpm)
                        : getString(R.string.heart_rate_status_waiting, name));
                break;
            case HeartRateManager.STATE_NO_SERVICE:
                statusView.setText(getString(R.string.heart_rate_status_no_service, name));
                break;
            case HeartRateManager.STATE_ERROR:
                statusView.setText(getString(R.string.heart_rate_status_error,
                        name, HeartRateManager.getGattStatus()));
                break;
            default:
                statusView.setText(name.isEmpty()
                        ? getString(R.string.heart_rate_status_no_device)
                        : getString(R.string.heart_rate_status_idle, name));
                break;
        }
    }

    private void requestEnableBluetooth() {
        try {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Throwable ignored) {
            toast(getString(R.string.heart_rate_status_bluetooth_off));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}