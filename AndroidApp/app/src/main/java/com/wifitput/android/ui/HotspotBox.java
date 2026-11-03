package com.wifitput.android.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Hotspot;

/** 本机热点面板：这台开热点，另一台当客户端连上来。 */
class HotspotBox extends LinearLayout implements Hotspot.Cb {

    private static final String IDLE =
            "热点未开启。开启后另一台手机在系统 WiFi 列表里连上它，再在「对端地址」里填这台显示的地址。";
    private static final String TOO_OLD =
            "系统版本过低，不支持在 App 内开热点（需要 Android 8.0 以上）";

    private final TextView status;
    private final Hotspot hs;
    private final boolean usable;

    HotspotBox(Context c) {
        super(c);
        setOrientation(VERTICAL);
        addView(Ui.cardTitle(c, "本机热点"));

        boolean sup = false;
        Hotspot h = null;
        boolean ok = false;
        try {
            sup = Hotspot.supported();
            h = new Hotspot(c, this);
            ok = sup;
        } catch (Throwable t) {
            h = null;
            ok = false;
        }
        hs = h;
        usable = ok;

        Button on = Ui.ghost(c, "开启热点", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(true);
            }
        });
        Button off = Ui.ghost(c, "关闭热点", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(false);
            }
        });

        LinearLayout bar = Ui.row(c);
        bar.addView(on, weight());
        bar.addView(off, weight());
        addView(bar, Ui.fill());

        status = Ui.text(c, IDLE, 14f, Ui.GREY, false);
        status.setPadding(0, Ui.dp(c, 6), 0, 0);
        addView(status, Ui.fill());

        if (!usable) {
            on.setEnabled(false);
            off.setEnabled(false);
            setStatus(TOO_OLD, Ui.GREY);
        }
    }

    private void act(boolean on) {
        if (!usable || hs == null) {
            setStatus(TOO_OLD, Ui.GREY);
            return;
        }
        setStatus(on ? "正在开启热点…" : "正在关闭热点…", Ui.GREY);
        try {
            if (on) {
                hs.start();
            } else {
                hs.stop();
            }
        } catch (Throwable t) {
            setStatus("操作失败：" + t, Ui.RED);
        }
    }

    private void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String lastLine(String msg) {
        String s = nz(msg).trim();
        int i = s.lastIndexOf('\n');
        return i >= 0 ? s.substring(i + 1).trim() : s;
    }

    @Override
    public void onLine(final String tag, final String msg) {
        post(new Runnable() {
            @Override
            public void run() {
                String line = lastLine(msg);
                setStatus(("" + nz(tag)).trim() + " " + line,
                        "!!".equals(tag) ? Ui.RED : Ui.GREY);
            }
        });
    }

    @Override
    public void onState(final boolean on, final String ssid, final String pass, final String ip) {
        post(new Runnable() {
            @Override
            public void run() {
                if (!on) {
                    setStatus("热点未开启", Ui.GREY);
                    return;
                }
                setStatus("热点已开启：" + nz(ssid) + "  密码 " + nz(pass)
                        + "\n本机热点地址 " + nz(ip), Ui.GREEN);
            }
        });
    }

    /** 宿主 onPause / 切 Tab 时调：关掉热点驱动句柄。 */
    void shutdown() {
        try {
            if (hs != null) {
                hs.close();
            }
        } catch (Throwable ignored) {
            // 已释放时忽略
        }
    }

    String ssid() {
        try {
            return hs == null ? "" : nz(hs.ssid());
        } catch (Throwable t) {
            return "";
        }
    }
}