package com.vrcdevicecheck.osc.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.vrcdevicecheck.osc.MainActivity;
import com.vrcdevicecheck.osc.R;
import com.vrcdevicecheck.osc.data.AppSettings;
import com.vrcdevicecheck.osc.data.ModelDisplayMode;
import com.vrcdevicecheck.osc.device.DeviceInfoRepository;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;
import com.vrcdevicecheck.osc.osc.OscEncoder;
import com.vrcdevicecheck.osc.osc.OscSender;
import com.vrcdevicecheck.osc.osc.ReportBuilder;
import com.vrcdevicecheck.osc.priv.PrivilegeController;
import com.vrcdevicecheck.osc.priv.PrivilegedShell;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 前台上报服务。
 *
 * 低占用策略：
 * - 单个前台服务 + 一条工作线程，没有额外定时框架、没有 WakeLock、没有线程池；
 * - 每个周期只起一次子进程读完全部节点，间隔默认 10 秒（可调）；
 * - 不做持久化写盘，日志只在内存里保留最近 {@link #MAX_LOGS} 条。
 *
 * 「后台不被杀」的正确做法是前台服务 + 用户把本应用加入电池优化白名单，
 * 界面里给了跳转入口；不采用任何绕过系统限制的手段。
 */
public class ReporterService extends Service {

    public static final String ACTION_START = "com.vrcdevicecheck.osc.action.START";
    public static final String ACTION_STOP = "com.vrcdevicecheck.osc.action.STOP";
    public static final String ACTION_SEND_NOW = "com.vrcdevicecheck.osc.action.SEND_NOW";

    private static final String CHANNEL_ID = "vrcdi_report";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MAX_LOGS = 50;

    /** 一条上报记录 */
    public static class LogEntry {
        public final long timeMs;
        public final boolean ok;
        public final String text;
        public final String detail;

        LogEntry(long timeMs, boolean ok, String text, String detail) {
            this.timeMs = timeMs;
            this.ok = ok;
            this.text = text;
            this.detail = detail;
        }

        public String timeText() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(timeMs));
        }
    }

    /** 界面刷新回调，统一切回主线程 */
    public interface Listener {
        void onReporterUpdate();
    }

    // ---------------- 对外可见的运行状态（静态，便于界面读取） ----------------
    private static volatile boolean running;
    private static volatile DeviceSnapshot latestSnapshot;
    private static volatile ReportBuilder.Report latestReport;
    private static volatile String warning;
    private static final LinkedList<LogEntry> LOGS = new LinkedList<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static boolean isRunning() {
        return running;
    }

    public static DeviceSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public static ReportBuilder.Report getLatestReport() {
        return latestReport;
    }

    public static String getWarning() {
        return warning;
    }

    public static List<LogEntry> getLogs() {
        synchronized (LOGS) {
            return Collections.unmodifiableList(new ArrayList<>(LOGS));
        }
    }

    public static void clearLogs() {
        synchronized (LOGS) {
            LOGS.clear();
        }
        notifyListeners();
    }

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : LISTENERS) {
                    try {
                        listener.onReporterUpdate();
                    } catch (Throwable ignored) {
                        // 单个界面刷新失败不影响服务
                    }
                }
            }
        });
    }

    // ---------------- 服务实现 ----------------

    private AppSettings settings;
    private DeviceInfoRepository repository;
    private final OscSender sender = new OscSender();
    private final Object lock = new Object();
    private volatile boolean loop;
    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        repository = new DeviceInfoRepository(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SEND_NOW.equals(action)) {
            synchronized (lock) {
                lock.notifyAll();
            }
            return START_STICKY;
        }

        startAsForeground();
        running = true;
        if (worker == null || !worker.isAlive()) {
            loop = true;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    reportLoop();
                }
            }, "vrcdi-reporter");
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
        notifyListeners();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        loop = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        sender.close();
        notifyListeners();
        super.onDestroy();
    }

    private void reportLoop() {
        while (loop) {
            try {
                sampleAndSend();
            } catch (Throwable t) {
                addLog(false, "采集或发送异常", t.getClass().getSimpleName());
            }
            long intervalMs = Math.max(AppSettings.MIN_INTERVAL, settings.getIntervalSec()) * 1000L;
            synchronized (lock) {
                if (!loop) break;
                try {
                    // 到点或被「立即发送」唤醒后进入下一轮
                    lock.wait(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void sampleAndSend() {
        PrivilegedShell shell = PrivilegeController.shellFor(settings.getPrivilegeMode());

        DeviceInfoRepository.CollectResult result;
        try {
            result = repository.collect(shell);
        } catch (Throwable t) {
            addLog(false, "采集失败", t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            return;
        }
        latestSnapshot = result.snapshot;
        warning = result.warning;

        ReportBuilder.Report report = ReportBuilder.build(
                result.snapshot,
                settings.getEnabledFields(),
                settings.isAsciiOnly(),
                settings.getModelDisplayMode() == ModelDisplayMode.PRODUCT_NAME);
        latestReport = report;

        if (report.text.trim().isEmpty()) {
            addLog(false, "未勾选任何字段", null);
            notifyListeners();
            return;
        }
        if (settings.getTargetIp().trim().isEmpty()) {
            addLog(false, report.text, "未填写目标 IP，本次未发送");
            notifyListeners();
            return;
        }

        byte[] packet = OscEncoder.chatboxInput(
                report.text, settings.isSendImmediately(), settings.isNotificationSound());
        OscSender.Result sendResult = sender.send(settings.getTargetIp(), settings.getTargetPort(), packet);
        if (sendResult.ok) {
            addLog(true, report.text,
                    "已发送 " + sendResult.bytes + " 字节到 "
                            + settings.getTargetIp() + ":" + settings.getTargetPort());
        } else {
            addLog(false, report.text, sendResult.message);
        }
        notifyListeners();
    }

    private void addLog(boolean ok, String text, String detail) {
        synchronized (LOGS) {
            LOGS.addFirst(new LogEntry(System.currentTimeMillis(), ok, text, detail));
            while (LOGS.size() > MAX_LOGS) LOGS.removeLast();
        }
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notif_channel_desc));
            manager.createNotificationChannel(channel);
        }
    }

    private void startAsForeground() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ReporterService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String ip = settings.getTargetIp();
        String target = (ip == null || ip.trim().isEmpty()) ? "未设置" : ip.trim();

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText("目标 " + target + ":" + settings.getTargetPort()
                        + " · 每 " + settings.getIntervalSec() + "s")
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                        getString(R.string.notif_stop),
                        stop).build());

        // minSdk 29，三参数版本自 API 29 起可用
        startForeground(NOTIFICATION_ID, builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    // ---------------- 启动辅助 ----------------

    public static void start(Context context) {
        context.startForegroundService(
                new Intent(context, ReporterService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, ReporterService.class).setAction(ACTION_STOP));
    }

    public static void sendNow(Context context) {
        context.startForegroundService(
                new Intent(context, ReporterService.class).setAction(ACTION_SEND_NOW));
    }
}
