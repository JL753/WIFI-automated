package com.wifitput.android.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.WifiDirect;

import java.util.List;

/**
 * Wi-Fi Direct 组网面板。
 *
 * 这是可选路径：不走路由器，让两台手机直接互联。
 * 一台点「组建群组」当群主，另一台点「搜索设备」找到它并加入。
 */
class P2pBox extends LinearLayout implements WifiDirect.Cb {

    interface Listener {
        void onGroup(boolean grouped, boolean isGo, String ip, String ssid, String pass);

        /** 组网过程中的一行状态，用于控制台在抽屉关着时也能看到进度。 */
        void onStatus(String s);
    }

    private static final String IDLE =
            "未组网。点「组建群组」让这台当群主，另一台点「搜索设备」找到它并加入。";
    private static final String NO_P2P = "这台设备不支持 Wi-Fi Direct";

    private final TextView status;
    private final WifiDirect wd;
    private final boolean usable;

    private String goIp = "";
    private boolean grouped;
    private Listener listener;

    /** 等组网成功后要执行的动作（出码 / 按码连接），没有等的时候为 null。 */
    private Runnable pending;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 等待组网的兜底计时器：P2P 协商偶尔会卡住，不能一直挂着。 */
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (pending != null) {
                pending = null;
                setStatus("等组网超时，可以再点一次，或用下面的手动组网", Ui.RED);
            }
        }
    };

    P2pBox(Context c) {
        super(c);
        setOrientation(VERTICAL);
        addView(Ui.cardTitle(c, "Wi-Fi Direct 组网"));

        WifiDirect w = null;
        boolean ok = false;
        try {
            w = new WifiDirect(c, this);
            ok = w.available();
        } catch (Throwable t) {
            w = null;
            ok = false;
        }
        wd = w;
        usable = ok;

        Button make = Ui.ghost(c, "组建群组", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(0, "正在组建群组…");
            }
        });
        Button scan = Ui.ghost(c, "搜索设备", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(1, "正在搜索附近设备…");
            }
        });
        Button quit = Ui.ghost(c, "退出群组", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(2, "正在退出…");
            }
        });

        LinearLayout bar = Ui.row(c);
        bar.addView(make, weight());
        bar.addView(scan, weight());
        bar.addView(quit, weight());
        addView(bar, Ui.fill());

        status = Ui.text(c, IDLE, 14f, Ui.GREY, false);
        status.setPadding(0, Ui.dp(c, 6), 0, 0);
        addView(status, Ui.fill());
    }

    /** 三个按钮共用的入口：不支持就直接说，其余一律 try/catch 兜住。 */
    private void act(int what, String progress) {
        if (!usable || wd == null) {
            setStatus(NO_P2P, Ui.GREY);
            return;
        }
        setStatus(progress, Ui.GREY);
        try {
            if (what == 0) {
                wd.createGroup();
            } else if (what == 1) {
                wd.discoverForPick();
            } else {
                wd.removeGroup();
            }
        } catch (Throwable t) {
            setStatus("操作失败：" + t, Ui.RED);
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    private void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
        if (listener != null && s != null && !s.trim().isEmpty()) {
            listener.onStatus(s);
        }
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

    /** 只留最后一行，避免日志把状态栏撑爆。 */
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
    public void onPeers(final List<String[]> peers) {
        post(new Runnable() {
            @Override
            public void run() {
                if (peers == null || peers.isEmpty()) {
                    setStatus("没搜到设备。确认另一台已点「组建群组」，并且两台都开着 WiFi。", Ui.GREY);
                    return;
                }
                final CharSequence[] items = new CharSequence[peers.size()];
                final String[] addrs = new String[peers.size()];
                final String[] names = new String[peers.size()];
                for (int i = 0; i < peers.size(); i++) {
                    String[] p = peers.get(i);
                    String addr = p != null && p.length > 0 ? nz(p[0]) : "";
                    String name = p != null && p.length > 1 ? nz(p[1]) : "";
                    String st = p != null && p.length > 2 ? nz(p[2]) : "";
                    if (name.length() == 0) {
                        name = addr;
                    }
                    addrs[i] = addr;
                    names[i] = name;
                    items[i] = name + "\n" + addr + " · " + st;
                }
                try {
                    AlertDialog d = new AlertDialog.Builder(getContext())
                            .setTitle("P2P 设备")
                            .setItems(items, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dlg, int which) {
                                    join(addrs[which], names[which]);
                                }
                            })
                            .create();
                    d.show();
                } catch (Throwable t) {
                    setStatus("弹出设备列表失败：" + t, Ui.RED);
                }
            }
        });
    }

    private void join(String address, String name) {
        setStatus("正在连接 " + name + "…", Ui.GREY);
        try {
            if (wd != null) {
                wd.connect(address);
            }
        } catch (Throwable t) {
            setStatus("连接失败：" + t, Ui.RED);
        }
    }

    @Override
    public void onGroup(final boolean grp, final boolean isGo, final String ip,
                        final String ssid, final String pass) {
        post(new Runnable() {
            @Override
            public void run() {
                grouped = grp;
                if (!grp) {
                    goIp = "";
                    setStatus("未组网", Ui.GREY);
                    if (listener != null) {
                        listener.onGroup(false, false, "", "", "");
                    }
                    return;
                }
                goIp = nz(ip);
                StringBuilder sb = new StringBuilder();
                sb.append("已组网（").append(isGo ? "本机是群主" : "对端是群主")
                        .append("）群主地址 ").append(goIp);
                if (nz(ssid).length() > 0) {
                    sb.append("，网络 ").append(ssid);
                }
                if (nz(pass).length() > 0) {
                    sb.append("，密码 ").append(pass);
                }
                setStatus(sb.toString(), Ui.GREEN);
                if (listener != null) {
                    listener.onGroup(true, isGo, goIp, nz(ssid), nz(pass));
                }
                // 组网一成功就得把等着的动作放出去，否则点完按钮毫无反应
                if (pending != null) {
                    Runnable next = pending;
                    pending = null;
                    ui.removeCallbacks(timeout);
                    ui.post(next);
                }
            }
        });
    }

    /** 本机 P2P 设备地址，未拿到 ""。 */
    String selfMac() {
        return wd == null ? "" : nz(wd.selfAddress());
    }

    /** 本机 P2P 设备名，未拿到 ""（空时回落到机型名，至少比空白好认）。 */
    String selfName() {
        String n = wd == null ? "" : nz(wd.selfName()).trim();
        if (n.length() == 0) {
            n = nz(Build.MODEL).trim();
        }
        return n;
    }

    /** 群主 IP，未组网 ""。 */
    String ownerIp() {
        return wd == null ? "" : nz(wd.groupOwnerIp());
    }

    /** 已组网就立刻回调；否则先清残留群组再组建群组，等组网成功回调。最多等 25 秒，回调在主线程。 */
    void ensureGroup(final Runnable onReady) {
        if (!usable || wd == null) {
            setStatus(NO_P2P, Ui.GREY);
            return;
        }
        if (grouped && ownerIp().length() > 0) {
            ui.post(onReady);
            return;
        }
        arm(onReady);
        // Android 12+ 若系统里还留着废弃群组，createGroup 的 onSuccess 不回调，界面会静默卡满 25 秒
        setStatus("正在清理残留群组…", Ui.GREY);
        try {
            wd.removeGroup(new Runnable() {
                @Override
                public void run() {
                    setStatus("正在组建群组…", Ui.GREY);
                    try {
                        wd.createGroup();
                    } catch (Throwable t) {
                        abort("组建群组失败：" + t);
                    }
                }
            });
        } catch (Throwable t) {
            abort("组建群组失败：" + t);
        }
    }

    /** 按投屏码里的对端设备名搜索并直连。onReady 组网成功后跑，onMiss 搜不到时跑。最多等 25 秒，回调在主线程。 */
    void connectByName(final String name, final Runnable onReady, final Runnable onMiss) {
        String n = nz(name).trim();
        if (n.length() == 0) {
            setStatus("这个码里没有对端设备名，只能手工填 IP", Ui.RED);
            if (onMiss != null) {
                ui.post(onMiss);
            }
            return;
        }
        if (!usable || wd == null) {
            setStatus(NO_P2P, Ui.GREY);
            return;
        }
        arm(onReady);
        setStatus("正在搜索对端 " + n + "…", Ui.GREY);
        wd.connectByName(n, new Runnable() {
            @Override
            public void run() {
                abort("没搜到「" + n + "」。已按投屏码填好地址，连不上就展开「手动组网（备用）」点「搜索设备」挑一台。");
                if (onMiss != null) {
                    ui.post(onMiss);
                }
            }
        });
    }

    /** 记下待执行动作并起表，重复点按钮时以最后一次为准。 */
    private void arm(Runnable onReady) {
        pending = onReady;
        ui.removeCallbacks(timeout);
        ui.postDelayed(timeout, 25000);
    }

    /** 发起阶段就失败：撤掉等待，别让界面停在「正在…」。 */
    private void abort(String why) {
        pending = null;
        ui.removeCallbacks(timeout);
        setStatus(why, Ui.RED);
    }

    /** 宿主 onPause / 切 Tab 时调：退出群组并释放。 */
    void shutdown() {
        pending = null;
        ui.removeCallbacks(timeout);
        try {
            if (wd != null) {
                wd.removeGroup();
                wd.close();
            }
        } catch (Throwable ignored) {
            // 已释放或系统不支持时忽略
        }
    }

    String goIp() {
        return nz(goIp);
    }

    boolean grouped() {
        return grouped;
    }
}