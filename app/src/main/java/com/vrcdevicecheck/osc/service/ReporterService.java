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
import com.vrcdevicecheck.osc.data.ReportField;
import com.vrcdevicecheck.osc.device.DeviceInfoRepository;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;
import com.vrcdevicecheck.osc.media.HeartRateManager;
import com.vrcdevicecheck.osc.media.MusicInfo;
import com.vrcdevicecheck.osc.media.NowPlayingRepository;
import com.vrcdevicecheck.osc.osc.OscEncoder;
import com.vrcdevicecheck.osc.osc.OscSender;
import com.vrcdevicecheck.osc.osc.ReportBuilder;
import com.vrcdevicecheck.osc.priv.PrivilegeController;
import com.vrcdevicecheck.osc.priv.PrivilegedShell;
import com.vrcdevicecheck.osc.util.LocaleHelper;
import com.vrcdevicecheck.osc.weather.WeatherInfo;
import com.vrcdevicecheck.osc.weather.WeatherRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    /** 勾选音乐字段后，歌词轮询间隔（只用于检测换行，不发消息就不占带宽） */
    private static final long MUSIC_TICK_MS = 2000L;
    /** 两次 Chatbox 发送的最小间隔，避免歌词换行太快触发 VRChat 限频 */
    private static final long MUSIC_SEND_GAP_MS = 2000L;
    /** 只有心率变化时，5 秒更新一次就够了 */
    private static final long HEART_RATE_SEND_GAP_MS = 5000L;

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
    private static volatile ReporterService instance;
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
    /** 上一轮发送完成的时间；用于改间隔后按新间隔重新计算下一次发送时刻 */
    private volatile long lastSendAt;
    /** 下一次发送的目标时刻（0 表示下一轮立刻发送） */
    private long nextSendAt;
    /** 「立即发送一次」请求 */
    private boolean forceSend;
    /** 最近一次成功发出的文本，用于歌词变化时判断是否值得再发一条 */
    private volatile String lastSentText = "";
    /** 最近一次尝试发送的时刻（含失败），用于节流歌词/心率触发的发送 */
    private volatile long lastAnySendAt;
    /** 服务是否持有心率手环连接（只在勾选心率字段时持有） */
    private boolean heartRateAcquired;

    @Override
    protected void attachBaseContext(Context newBase) {
        // 通知栏和日志里的文案要跟界面语言一致，不能只看系统语言
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
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
                forceSend = true;
                nextSendAt = 0;
                lock.notifyAll();
            }
            // 服务本来就在跑：只唤醒工作线程，不重复走启动流程
            if (running) {
                notifyListeners();
                return START_STICKY;
            }
            // 服务没在跑：继续往下走正常启动，启动后立刻发送一次
        }

        startAsForeground();
        running = true;
        if (worker == null || !worker.isAlive()) {
            loop = true;
            synchronized (lock) {
                // 启动即发（默认）：下一轮直接发送；关闭后先等一个间隔
                if (!forceSend) {
                    nextSendAt = settings.isSendOnStart()
                            ? 0
                            : System.currentTimeMillis() + intervalMs();
                }
            }
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
        if (heartRateAcquired) {
            HeartRateManager.release();
            heartRateAcquired = false;
        }
        if (instance == this) instance = null;
        notifyListeners();
        super.onDestroy();
    }

    private long intervalMs() {
        return Math.max(AppSettings.MIN_INTERVAL, settings.getIntervalSec()) * 1000L;
    }

    /**
     * 按「上次发送完成时刻 + 新间隔」重新计算下一次发送。
     * 如果新间隔已经过去，就立即补发一次；否则等剩余时间。
     * 这样在设置里把 60 秒改成 5 秒，不用等满 60 秒才生效。
     */
    private void reschedule() {
        synchronized (lock) {
            long base = lastSendAt > 0 ? lastSendAt : System.currentTimeMillis();
            nextSendAt = Math.max(System.currentTimeMillis(), base + intervalMs());
            lock.notifyAll();
        }
        updateNotification();
    }

    /** 设置页保存间隔后调用：服务在跑就立即按新间隔重排，没跑则只存配置 */
    public static void onIntervalChanged() {
        ReporterService service = instance;
        if (service != null) service.reschedule();
    }

    /** 字段或权限变化后唤醒工作线程，立即重新评估音乐轮询 */
    public static void wake() {
        ReporterService service = instance;
        if (service != null) {
            synchronized (service.lock) {
                service.lock.notifyAll();
            }
        }
    }

    private void reportLoop() {
        while (loop) {
            boolean due;
            synchronized (lock) {
                if (!loop) break;
                long now = System.currentTimeMillis();
                due = forceSend || nextSendAt == 0 || now >= nextSendAt;
                if (due) forceSend = false;
            }

            Set<ReportField> enabledFields = settings.getEnabledFields();
            boolean musicEnabled = ReportBuilder.hasMusicFields(enabledFields);
            boolean heartRateEnabled = enabledFields.contains(ReportField.HEART_RATE);
            syncHeartRate(heartRateEnabled);
            boolean fastTick = musicEnabled || heartRateEnabled;

            if (due) {
                try {
                    sampleAndSend();
                } catch (Throwable t) {
                    addLog(false, text(R.string.log_collect_error), t.getClass().getSimpleName());
                }
                lastSendAt = System.currentTimeMillis();
                synchronized (lock) {
                    // 采集期间又点了「立即发送」：保持待发送状态，下一轮马上再发
                    nextSendAt = forceSend ? 0 : lastSendAt + intervalMs();
                }
            } else if (fastTick && latestSnapshot != null) {
                // 常规上报没到点，但歌词/心率可能变了：复用上一轮快照快速更新
                try {
                    quickTick();
                } catch (Throwable ignored) {
                    // 音乐/心率读取失败不影响正常上报
                }
            }

            synchronized (lock) {
                if (!loop) break;
                long waitMs = Math.max(0L, nextSendAt - System.currentTimeMillis());
                if (fastTick) waitMs = Math.min(waitMs, MUSIC_TICK_MS);
                try {
                    // 到点、改间隔、改字段或「立即发送」都会唤醒
                    lock.wait(waitMs);
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
            addLog(false, text(R.string.log_collect_failed),
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            return;
        }
        latestSnapshot = result.snapshot;
        warning = result.warning;

        Set<ReportField> enabledFields = settings.getEnabledFields();
        WeatherInfo weather = WeatherRepository.isWeatherEnabled(enabledFields)
                ? WeatherRepository.getForReport(this)
                : null;
        MusicInfo music = ReportBuilder.hasMusicFields(enabledFields)
                ? NowPlayingRepository.snapshot(this)
                : null;
        Integer heartRate = heartRateBpm(enabledFields);

        ReportBuilder.Report report = ReportBuilder.build(
                result.snapshot,
                enabledFields,
                settings.isAsciiOnly(),
                settings.getModelDisplayMode() == ModelDisplayMode.PRODUCT_NAME,
                weather,
                settings.isHideUnavailable(),
                LocaleHelper.getLanguage(this),
                music,
                heartRate);
        latestReport = report;

        if (report.text.trim().isEmpty()) {
            addLog(false, enabledFields.isEmpty()
                    ? text(R.string.log_no_fields)
                    : text(R.string.log_all_unavailable), null);
            notifyListeners();
            return;
        }
        if (settings.getTargetIp().trim().isEmpty()) {
            addLog(false, report.text, text(R.string.log_no_target));
            notifyListeners();
            return;
        }

        byte[] packet = OscEncoder.chatboxInput(
                report.text, settings.isSendImmediately(), settings.isNotificationSound());
        OscSender.Result sendResult = sender.send(this,
                settings.getTargetIp(), settings.getTargetPort(), packet);
        lastAnySendAt = System.currentTimeMillis();
        if (sendResult.ok) {
            lastSentText = report.text;
            addLog(true, report.text,
                    text(R.string.log_sent, sendResult.bytes,
                            settings.getTargetIp(), settings.getTargetPort()));
        } else {
            addLog(false, report.text, sendResult.message);
        }
        notifyListeners();
    }

    /**
     * 歌词换行 / 切歌 / 心率变化时的快速更新：
     * 复用最近一次设备快照，不重新读硬件，也不刷新天气缓存，
     * 只在文本真的变了时才发一条，避免 Chatbox 被刷屏。
     */
    private void quickTick() {
        Set<ReportField> enabledFields = settings.getEnabledFields();
        boolean musicEnabled = ReportBuilder.hasMusicFields(enabledFields);
        boolean heartRateEnabled = enabledFields.contains(ReportField.HEART_RATE);
        if (!musicEnabled && !heartRateEnabled) return;

        long now = System.currentTimeMillis();
        long minGap = musicEnabled ? MUSIC_SEND_GAP_MS : HEART_RATE_SEND_GAP_MS;
        if (now - lastAnySendAt < minGap) return;

        MusicInfo music = musicEnabled ? NowPlayingRepository.snapshot(this) : null;
        Integer heartRate = heartRateBpm(enabledFields);
        ReportBuilder.Report report = ReportBuilder.build(
                latestSnapshot,
                enabledFields,
                settings.isAsciiOnly(),
                settings.getModelDisplayMode() == ModelDisplayMode.PRODUCT_NAME,
                WeatherRepository.getCached(this),
                settings.isHideUnavailable(),
                LocaleHelper.getLanguage(this),
                music,
                heartRate);
        if (report.text.trim().isEmpty()) return;
        if (report.text.equals(lastSentText)) return;
        if (settings.getTargetIp().trim().isEmpty()) return;

        byte[] packet = OscEncoder.chatboxInput(
                report.text, settings.isSendImmediately(), settings.isNotificationSound());
        OscSender.Result sendResult = sender.send(this,
                settings.getTargetIp(), settings.getTargetPort(), packet);
        lastAnySendAt = now;
        if (sendResult.ok) {
            lastSentText = report.text;
            latestReport = report;
            notifyListeners();
        }
    }

    /** 勾选心率字段时，让服务持有一条手环连接；取消勾选立即释放 */
    private void syncHeartRate(boolean enabled) {
        if (enabled && !heartRateAcquired) {
            HeartRateManager.acquire(this);
            heartRateAcquired = true;
        } else if (!enabled && heartRateAcquired) {
            HeartRateManager.release();
            heartRateAcquired = false;
        }
    }

    /** 近 60 秒内没有新数据就按「不可用」处理，避免把旧心率一直发出去 */
    private Integer heartRateBpm(Set<ReportField> fields) {
        if (fields == null || !fields.contains(ReportField.HEART_RATE)) return null;
        int bpm = HeartRateManager.getFreshBpm();
        return bpm > 0 ? bpm : null;
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
        // 已存在时也重建一次：语言换了以后通知渠道名字要跟着更新
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                text(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(text(R.string.notif_channel_desc));
        manager.createNotificationChannel(channel);
    }

    /** 取一条本地化文案：不依赖 attachBaseContext 是否生效 */
    private String text(int resId, Object... args) {
        return LocaleHelper.str(this, resId, args);
    }

    private void startAsForeground() {
        // minSdk 29，三参数版本自 API 29 起可用
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    /** 通知栏文案跟着间隔走，改了间隔不必重启服务 */
    private void updateNotification() {
        if (!running) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ReporterService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String ip = settings.getTargetIp();
        String target = (ip == null || ip.trim().isEmpty())
                ? text(R.string.notif_target_unset) : ip.trim();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text(R.string.notif_title))
                .setContentText(text(R.string.notif_text, target,
                        settings.getTargetPort(), settings.getIntervalSec()))
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                        text(R.string.notif_stop),
                        stop).build())
                .build();
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
