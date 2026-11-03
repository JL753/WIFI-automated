package com.wifitput.android.core;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * 用 LocalOnlyHotspot 开热点（API 26 起可用）。
 *
 * LocalOnlyHotspot 没有互联网共享，但同一台设备上的 WiFi 仍可用，适合本工具做吞吐测试。
 */
public class Hotspot {

    public interface Cb {
        void onLine(String tag, String msg);

        void onState(boolean on, String ssid, String pass, String ip);
    }

    private final Context ctx;
    private final Cb cb;
    private final WifiManager wm;
    private final Handler ui;

    private WifiManager.LocalOnlyHotspotReservation res;
    private String ssid = "";
    private String pass = "";
    private String ip = "";
    private boolean starting;

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    public Hotspot(Context ctx, Cb cb) {
        Context app = ctx;
        if (ctx != null) {
            Context ac = ctx.getApplicationContext();
            app = ac != null ? ac : ctx;
        }
        this.ctx = app;
        this.cb = cb;
        this.ui = new Handler(Looper.getMainLooper());
        WifiManager m = null;
        if (this.ctx != null) {
            try {
                m = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
            } catch (Exception ignored) {
            }
        }
        this.wm = m;
    }

    public void start() {
        if (!supported()) {
            line("!!", "系统版本过低，不支持开热点（需要 Android 8.0 以上）");
            state(false, "", "", "");
            return;
        }
        if (wm == null) {
            line("!!", "取不到 WifiManager，无法开热点");
            state(false, "", "", "");
            return;
        }
        if (res != null) {
            line("--", "热点已在运行：" + ssid);
            state(true, ssid, pass, ip);
            return;
        }
        if (starting) {
            line("--", "热点正在启动，请稍候");
            return;
        }
        starting = true;
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    starting = false;
                    res = r;
                    WifiConfiguration c = null;
                    try {
                        c = r == null ? null : r.getWifiConfiguration();
                    } catch (Exception ignored) {
                    }
                    ssid = c == null || c.SSID == null ? "" : strip(c.SSID);
                    pass = c == null || c.preSharedKey == null ? "" : strip(c.preSharedKey);
                    ip = localIp();
                    state(true, ssid, pass, ip);
                    line("OK", "热点已开启：" + ssid
                            + (pass.length() > 0 ? "  密码：" + pass : "  （开放网络，无密码）"));
                }

                @Override
                public void onStopped() {
                    starting = false;
                    res = null;
                    ssid = "";
                    pass = "";
                    ip = "";
                    line("--", "热点已停止");
                    state(false, "", "", "");
                }

                @Override
                public void onFailed(int reason) {
                    starting = false;
                    res = null;
                    line("!!", "开热点失败：" + reasonCn(reason));
                    state(false, "", "", "");
                }
            }, ui);
        } catch (Exception e) {
            starting = false;
            line("!!", "开热点失败：" + e);
            state(false, "", "", "");
        }
    }

    public void stop() {
        WifiManager.LocalOnlyHotspotReservation r = res;
        res = null;
        starting = false;
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        ssid = "";
        pass = "";
        ip = "";
        state(false, "", "", "");
    }

    public boolean on() {
        return res != null;
    }

    public String ssid() {
        return ssid;
    }

    public String pass() {
        return pass;
    }

    public void close() {
        stop();
    }

    private static String reasonCn(int reason) {
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL) {
            return "没有可用信道";
        }
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE) {
            return "当前 WiFi 状态不允许开热点（先断开已连接的 WiFi 再试）";
        }
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED) {
            return "系统策略不允许开热点";
        }
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC) {
            return "未知原因";
        }
        return "未知原因";
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        if (out.length() >= 2 && out.charAt(0) == '"' && out.charAt(out.length() - 1) == '"') {
            out = out.substring(1, out.length() - 1);
        }
        return out;
    }

    private String localIp() {
        try {
            List<String> all = Dev.addrs();
            for (String a : all) {
                if (a == null) {
                    continue;
                }
                int i = a.indexOf('/');
                String v = i >= 0 ? a.substring(0, i) : a;
                if (v.length() == 0 || v.startsWith("127.") || v.indexOf(':') >= 0) {
                    continue;
                }
                return v;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void line(final String tag, final String msg) {
        final Cb c = cb;
        if (c == null) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                c.onLine(tag, msg);
            }
        });
    }

    private void state(final boolean on, final String s, final String p, final String a) {
        final Cb c = cb;
        if (c == null) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                c.onState(on, s, p, a);
            }
        });
    }
}
