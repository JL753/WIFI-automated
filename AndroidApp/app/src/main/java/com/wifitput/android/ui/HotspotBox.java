package com.wifitput.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Dev;

import org.json.JSONObject;

/**
 * 系统热点面板（仅热点页）。
 *
 * App 里开不出「系统设置里那个热点」：LocalOnlyHotspot 的射频是真的（实测另机能连、能打流），
 * 但它是 ROLE_SOFTAP_LOCAL_ONLY —— 系统热点开关不会变已开启、不共享外网，
 * 另一台手机在 WLAN 列表里看到的也不是用户平时那套热点配置。
 * 所以这里不做开关，只引导去系统设置打开便携式热点，回来检测并显示本机地址。
 */
class HotspotBox extends LinearLayout {

    interface Listener {
        void onHotspot(boolean on, String ip);
    }

    private static final String IDLE =
            "还没检测到本机热点。点「去系统设置开热点」，在系统设置里打开「便携式热点」，"
                    + "回来后点「重新检测」。";

    private final TextView status;
    private boolean checking;
    private Listener listener;
    private boolean on;

    HotspotBox(Context c) {
        this(c, true);
    }

    /** withButtons=false 时只显示状态，开关/重新检测由外面的主按钮负责。 */
    HotspotBox(Context c, boolean withButtons) {
        super(c);
        setOrientation(VERTICAL);
        addView(Ui.cardTitle(c, "系统热点"));

        if (withButtons) {
            Button open = Ui.primary(c, "去系统设置开热点", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSettings();
                }
            });
            Button recheck = Ui.ghost(c, "重新检测", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    refresh();
                }
            });
            LinearLayout bar = Ui.row(c);
            bar.addView(open, Ui.w(1.35f));
            Ui.margin(c, open, 0, 0, 8, 0);
            bar.addView(recheck, Ui.w(1f));
            addView(bar, Ui.fill());
        }

        status = Ui.text(c, IDLE, 12.5f, Ui.GREY, false);
        status.setPadding(0, Ui.dp(c, 6), 0, 0);
        status.setLineSpacing(Ui.dp(c, 1), 1f);
        addView(status, Ui.fill());
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isOn() {
        return on;
    }

    /** 打不开主流设置页时退到无线设置，都不行就把说法打在状态行上。 */
    void openSettings() {
        Context c = getContext();
        try {
            // Settings 里没有这个常量的公开字段，只能写字符串（实测可跳转到个人热点页）。
            start(c, "android.settings.TETHER_SETTINGS");
            return;
        } catch (Exception ignored) {
        }
        try {
            start(c, Settings.ACTION_WIRELESS_SETTINGS);
            return;
        } catch (Exception ignored) {
        }
        setStatus("打不开系统设置。手动进「设置 → 网络 → 便携式热点」打开开关，再回来点「重新检测」。", Ui.RED);
    }

    private static void start(Context c, String action) {
        Intent i = new Intent(action);
        if (!(c instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        c.startActivity(i);
    }

    /** 检测本机热点状态。Dev.collect 那套反射 + 网段推断有点开销，放工作线程。 */
    void refresh() {
        if (checking) {
            return;
        }
        checking = true;
        setStatus("正在检测本机热点…", Ui.GREY);
        final Context app = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject h = null;
                String err = "";
                try {
                    h = Dev.hotspot(app);
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                final JSONObject r = h;
                final String e = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (r == null) {
                            setStatus("检测失败：" + e, Ui.RED);
                        } else {
                            render(r);
                        }
                    }
                });
            }
        }, "hotspot-check").start();
    }

    private void render(JSONObject h) {
        on = h.optBoolean("on", false);
        if (!on) {
            setStatus(IDLE, Ui.GREY);
            if (listener != null) {
                listener.onHotspot(false, "");
            }
            return;
        }
        String ip = h.optString("ip", "");
        String iface = h.optString("iface", "");
        String addr;
        if (ip.isEmpty()) {
            addr = iface.isEmpty() ? "地址未知" : iface;
        } else {
            addr = iface.isEmpty() ? ip : ip + "（" + iface + "）";
        }
        setStatus("已检测到本机热点：本机地址 " + addr
                + "\n另一台连上后在「WiFi」页点「扫描对端」，也可以手工填这个地址。", Ui.GREEN);
        if (listener != null) {
            listener.onHotspot(true, ip);
        }
    }

    private void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
    }
}
