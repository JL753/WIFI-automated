package com.wifitput.android.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 测速期间挂一个前台通知 + 抓住 CPU 和 WiFi 不放，避免打流打到一半被系统回收或灭屏降速。
 * 灭屏测试必须靠它：没有 WakeLock 时 CPU 会睡、iperf3 子进程被挂起；
 * 没有 WifiLock 时 WiFi 进省电模式，吞吐会掉一大截。
 */
public class KeepAlive extends Service {

    public static final String CHANNEL = "wifitput_test";
    public static final int NOTIF_ID = 8801;
    private static final String EXTRA_TEXT = "text";
    private static final String WAKE_TAG = "wifitput:test";

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    public static void start(Context c, String text) {
        Intent i = new Intent(c, KeepAlive.class);
        i.putExtra(EXTRA_TEXT, text);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, KeepAlive.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        grabCpu();
        grabWifi();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? "正在测速" : intent.getStringExtra(EXTRA_TEXT);
        if (text == null || text.isEmpty()) {
            text = "正在测速";
        }
        startForeground(NOTIF_ID, build(text));
        // 上一次若因系统回收释放过，这里补回来
        grabCpu();
        grabWifi();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void grabCpu() {
        if (wake != null && wake.isHeld()) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG);
            wake.setReferenceCounted(false);
            wake.acquire();
        } catch (RuntimeException ignored) {
        }
    }

    private void grabWifi() {
        if (wifi != null && wifi.isHeld()) {
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 12
                    ? WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    : WifiManager.WIFI_MODE_FULL;
            wifi = wm.createWifiLock(mode, WAKE_TAG);
            wifi.setReferenceCounted(false);
            wifi.acquire();
        } catch (RuntimeException ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wake != null && wake.isHeld()) {
                wake.release();
            }
        } catch (RuntimeException ignored) {
        }
        wake = null;
        try {
            if (wifi != null && wifi.isHeld()) {
                wifi.release();
            }
        } catch (RuntimeException ignored) {
        }
        wifi = null;
    }

    private Notification build(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "测速运行中", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, com.wifitput.android.ui.MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, flags);
        b.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("WiFiTput")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }
}
