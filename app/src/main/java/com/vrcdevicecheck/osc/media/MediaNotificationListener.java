package com.vrcdevicecheck.osc.media;

import android.service.notification.NotificationListenerService;

/**
 * 本服务不读取通知内容，只作为「通知使用权」的挂载点：
 * Android 只允许已启用通知监听的应用调用
 * {@link android.media.session.MediaSessionManager#getActiveSessions}。
 */
public class MediaNotificationListener extends NotificationListenerService {
}