package com.wifitput.android.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Bundle;
import com.wifitput.android.core.Discovery;
import com.wifitput.android.core.Prefs;

import java.util.List;

/**
 * 一个模式页（WiFi / 热点 / P2P）。三个页结构一样，只是参数和可用的配对方式不同：
 * 只有 P2P 页给二维码（扫码配对是为了走 Wi-Fi Direct 投屏那类场景），
 * WiFi 页和热点页靠「扫描对端」+ 手工填地址。
 */
class TabPanel extends android.widget.ScrollView implements Session.Listener, AddrSink {

    final String mode;
    private final Session session;
    private final LogBox log;
    private final EditText host;
    private final EditText port;
    private final EditText duration;
    private final EditText streams;
    private final EditText interval;
    private final EditText udpBw;
    private final Button kindBtn;
    private final Button dirBtn;
    private final Button serveBtn;
    private final Button runBtn;
    private final Button scanBtn;
    private final Button uploadBtn;
    private final TextView status;
    private final TextView serveHint;
    private P2pBox p2p;
    private HotspotBox hs;
    private boolean busy;
    private Bundle last;

    TabPanel(Context c, String mode) {
        super(c);
        this.mode = mode;
        session = new Session(c, mode, this);
        session.kind = Prefs.getStr(c, "kind", "TCP");
        session.udpBw = Prefs.getStr(c, "udp_bw", "50M");
        session.duration = Prefs.getInt(c, "duration", 10);
        session.streams = Prefs.getInt(c, "streams", 4);
        session.interval = Prefs.getInt(c, "interval", 1);
        session.port = Prefs.getInt(c, "port", 5201);
        session.host = Prefs.getStr(c, "host", "");

        setBackgroundColor(Ui.PAGE_BG);
        setFillViewport(true);

        LinearLayout root = Ui.column(c);
        root.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 20));

        // 顶部：模式标题 + 状态胶囊同一行，下面一句话说明
        LinearLayout top = Ui.column(c);
        LinearLayout titleRow = Ui.row(c);
        TextView title = Ui.text(c, modeTitle(), 18f, Ui.INK, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);
        status = Ui.text(c, "空闲", 12.5f, Ui.BLUE, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(c, 10), Ui.dp(c, 5), Ui.dp(c, 10), Ui.dp(c, 5));
        status.setBackground(Ui.round(c, 0xFFE8F0FB, 0, 9f));
        titleRow.addView(status);
        top.addView(titleRow);
        top.addView(Ui.hint(c, modeHint()));
        root.addView(top, Ui.cardLp(c));

        log = new LogBox(c);

        // 卡片 1：这一台干什么
        LinearLayout c1 = Ui.card(c, "这一台干什么");
        LinearLayout roleRow = Ui.row(c);
        serveBtn = Ui.ghost(c, "当服务端", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleServe();
            }
        });
        scanBtn = Ui.ghost(c, "扫描对端", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        roleRow.addView(serveBtn, Ui.w(1f));
        Ui.margin(c, serveBtn, 0, 0, 5, 0);
        roleRow.addView(scanBtn, Ui.w(1f));
        Ui.margin(c, scanBtn, 5, 0, 0, 0);
        c1.addView(roleRow);
        serveHint = Ui.hint(c, serveHintText());
        c1.addView(serveHint);
        if ("p2p".equals(mode)) {
            LinearLayout qrRow = Ui.row(c);
            Button mineBtn = Ui.ghost(c, "我的二维码", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    QrDialog.showMine(getContext(), firstIp(), session.port);
                }
            });
            Button scanQrBtn = Ui.ghost(c, "扫一扫", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    android.content.Context cc = getContext();
                    if (cc instanceof android.app.Activity) {
                        QrDialog.scan((android.app.Activity) cc);
                    } else {
                        log.add("!! 当前界面不支持扫码");
                    }
                }
            });
            qrRow.addView(mineBtn, Ui.w(1f));
            Ui.margin(c, mineBtn, 0, 0, 5, 0);
            qrRow.addView(scanQrBtn, Ui.w(1f));
            Ui.margin(c, scanQrBtn, 5, 0, 0, 0);
            c1.addView(qrRow);
        }
        root.addView(c1);

        // 卡片 2：对端地址（可收起）
        Fold c2 = new Fold(c, "对端地址", "peer", true);
        LinearLayout hostRow = Ui.row(c);
        host = Ui.input(c, session.host, 0);
        port = Ui.number(c, String.valueOf(session.port), 78);
        LinearLayout hostField = Ui.field(c, "对方手机地址", host, 1f);
        Ui.margin(c, hostField, 0, 0, 10, 0);
        hostRow.addView(hostField);
        hostRow.addView(Ui.narrowField(c, "端口", port, 78));
        c2.body().addView(hostRow);
        c2.body().addView(Ui.hint(c, "扫描对端后弹窗挑一个；也可以照对方顶栏的地址手工填"));
        root.addView(c2);

        // 卡片 3：测试参数（可收起）
        Fold c3 = new Fold(c, "测试参数", "param", true);
        LinearLayout row1 = Ui.row(c);
        duration = Ui.number(c, String.valueOf(session.duration), 0);
        streams = Ui.number(c, String.valueOf(session.streams), 0);
        interval = Ui.number(c, String.valueOf(session.interval), 0);
        LinearLayout f1 = Ui.field(c, "时长(秒)", duration, 1f);
        LinearLayout f2 = Ui.field(c, "并发流", streams, 1f);
        LinearLayout f3 = Ui.field(c, "采样(秒)", interval, 1f);
        Ui.margin(c, f1, 0, 0, 8, 0);
        Ui.margin(c, f2, 0, 0, 8, 0);
        row1.addView(f1);
        row1.addView(f2);
        row1.addView(f3);
        c3.body().addView(row1);

        LinearLayout row2 = Ui.row(c);
        Ui.margin(c, row2, 0, 12, 0, 0);
        kindBtn = Ui.chip(c, kindLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                session.kind = "UDP".equals(session.kind) ? "TCP" : "UDP";
                kindBtn.setText(kindLabel());
                udpBw.setEnabled("UDP".equals(session.kind));
            }
        });
        dirBtn = Ui.chip(c, dirLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                session.reverse = !session.reverse;
                dirBtn.setText(dirLabel());
            }
        });
        udpBw = Ui.input(c, session.udpBw, 0);
        udpBw.setEnabled("UDP".equals(session.kind));
        row2.addView(kindBtn);
        Ui.margin(c, kindBtn, 0, 0, 8, 0);
        row2.addView(dirBtn);
        Ui.margin(c, dirBtn, 0, 0, 12, 0);
        row2.addView(Ui.field(c, "UDP 限速", udpBw, 1f));
        c3.body().addView(row2);
        root.addView(c3);

        // 卡片 4：系统热点引导 / P2P 组网（只有对应模式才有）
        if ("hotspot".equals(mode)) {
            LinearLayout c4 = Ui.card(c, null);
            hs = new HotspotBox(c);
            c4.addView(hs);
            root.addView(c4);
            hs.refresh();
        } else if ("p2p".equals(mode)) {
            LinearLayout c4 = Ui.card(c, null);
            p2p = new P2pBox(c);
            c4.addView(p2p);
            root.addView(c4);
        }

        // 卡片 5：开始 + 实时数据（合并成一张，页面更紧凑）
        // 结果回传的 PC 地址不在这里配了，统一走顶栏「⋯ → 设置结果回传 PC」
        LinearLayout c6 = Ui.card(c, "运行");
        LinearLayout runRow = Ui.row(c);
        runBtn = Ui.primary(c, "开始打流", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (session.running()) {
                    session.stopServing();
                    setBusy(false, "已停止");
                    return;
                }
                if (!pullParams()) {
                    return;
                }
                session.savePrefs();
                setBusy(true, "打流中…");
                session.runOnce();
            }
        });
        uploadBtn = Ui.ghost(c, "重发上一次", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (last == null) {
                    log.add("!! 还没有可重发的结果");
                    return;
                }
                if (!pullParams()) {
                    return;
                }
                session.savePrefs();
                log.add("-- 重新上传上一轮结果");
                session.upload(last, "");
            }
        });
        runRow.addView(runBtn, Ui.w(1.35f));
        Ui.margin(c, runBtn, 0, 0, 10, 0);
        runRow.addView(uploadBtn, Ui.w(1f));
        // 日志框太小看不清逐秒曲线，给一个全屏入口看完整实时数据
        Button fullBtn = Ui.ghost(c, "全屏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogDialog.show(c(), modeTitle() + " · 实时数据", log.dump());
            }
        });
        runRow.addView(fullBtn, Ui.w(0.62f));
        Ui.margin(c, fullBtn, 8, 0, 0, 0);
        c6.addView(runRow);
        Ui.margin(c, log, 0, 12, 0, 0);
        c6.addView(log);
        root.addView(c6);

        addView(root);
        log.add("-- " + modeTitle() + " 就绪。" + session.myHint());
    }

    private String modeTitle() {
        if ("hotspot".equals(mode)) {
            return "热点吞吐";
        }
        if ("p2p".equals(mode)) {
            return "P2P 直连吞吐";
        }
        return "WiFi 吞吐";
    }

    private String modeHint() {
        if ("hotspot".equals(mode)) {
            return "热点要开在系统设置里（不是 App 里）。先在下面点「去系统设置开热点」打开便携式热点，"
                    + "另一台连上它，两个人对着打流。开热点的那台建议当服务端。";
        }
        if ("p2p".equals(mode)) {
            return "两台都不接路由器，用 Wi-Fi Direct 直接互联后再打流，适合排除路由器干扰。";
        }
        return "两台手机连同一个 WiFi（路由器或手机热点都行），一台当服务端，另一台当客户端。";
    }

    private String serveHintText() {
        if ("hotspot".equals(mode)) {
            return "开热点的这台点「当服务端」，另一台在系统 WLAN 里连上热点后点「扫描对端」。";
        }
        if ("p2p".equals(mode)) {
            return "先在下面完成 Wi-Fi Direct 组网，再让群主这台点「当服务端」，另一台点「扫描对端」或扫二维码。";
        }
        return "建议路由器下那台点「当服务端」，另一台点「扫描对端」自动找到它。";
    }

    private String kindLabel() {
        return "UDP".equals(session.kind) ? "协议 UDP" : "协议 TCP";
    }

    private String dirLabel() {
        return session.reverse ? "方向 反向 -R" : "方向 正向";
    }

    private void startScan() {
        if (session.running() || session.serving()) {
            log.add("!! 先停掉正在跑的测试再扫描");
            return;
        }
        log.add("-- 开始扫描，扫到的设备会列在弹窗里");
        status.setText("正在扫描…");
        status.setTextColor(Ui.BLUE);
        session.scan(3000);
    }

    private boolean pullParams() {
        int d = Ui.num(duration, session.duration);
        if (d < 1 || d > 3600) {
            log.add("!! 时长要在 1 到 3600 秒之间");
            return false;
        }
        int s = Ui.num(streams, session.streams);
        if (s < 1 || s > 32) {
            log.add("!! 并发流要在 1 到 32 之间");
            return false;
        }
        int iv = Ui.num(interval, session.interval);
        if (iv < 1 || iv > 60) {
            log.add("!! 采样间隔要在 1 到 60 秒之间");
            return false;
        }
        int p = Ui.num(port, session.port);
        if (p < 1 || p > 65535) {
            log.add("!! iperf3 端口要在 1 到 65535 之间");
            return false;
        }
        session.duration = d;
        session.streams = s;
        session.interval = iv;
        session.port = p;
        session.host = host.getText().toString().trim();
        session.udpBw = udpBw.getText().toString().trim();
        if (session.udpBw.isEmpty()) {
            session.udpBw = "50M";
        }
        return true;
    }

    private void toggleServe() {
        if (session.serving()) {
            session.stopServing();
            serveBtn.setText("当服务端");
            setBusy(false, "服务端已停止");
            return;
        }
        if (session.running()) {
            log.add("!! 先停掉正在跑的测试");
            return;
        }
        if (!pullParams()) {
            return;
        }
        session.savePrefs();
        serveBtn.setText("停止服务端");
        setBusy(true, "服务端就绪");
        session.startServing();
    }

    boolean isBusy() {
        return busy;
    }

    void setBusy(boolean busy, String text) {
        this.busy = busy;
        runBtn.setText(busy ? "停止" : "开始打流");
        scanBtn.setEnabled(!busy);
        scanBtn.setAlpha(busy ? 0.45f : 1f);
        status.setText(text);
        status.setTextColor(busy ? Ui.GREEN : Ui.BLUE);
        status.setBackground(Ui.round(c(), busy ? 0xFFE7F4E9 : 0xFFE8F0FB, 0, 9f));
    }

    private Context c() {
        return getContext();
    }

    void stopAll() {
        session.stopServing();
        session.savePrefs();
        if (p2p != null) {
            p2p.shutdown();
        }
    }

    /** 从系统设置开完热点回来时重新检测一次。 */
    void refreshHotspot() {
        if (hs != null) {
            hs.refresh();
        }
    }

    /** 扫码结果回来时由宿主 Activity 调用。 */
    void onQrResult(String[] addr) {
        if (addr == null) {
            log.add("!! 没扫到可用的对端地址");
            return;
        }
        try {
            fill(addr[0], Integer.parseInt(addr[1]));
        } catch (NumberFormatException e) {
            log.add("!! 二维码里的端口不对：" + addr[1]);
        }
    }

    @Override
    public void fill(String ip, int port) {
        host.setText(ip);
        this.port.setText(String.valueOf(port));
        session.host = ip;
        session.port = port;
        session.savePrefs();
        log.add("OK 对端地址已填入：" + ip + ":" + port);
        status.setText("可以开始打流");
        status.setTextColor(Ui.BLUE);
    }

    /** 本机第一个可用的 IPv4，用于生成二维码。 */
    private String firstIp() {
        try {
            List<String> ips = com.wifitput.android.core.Upload.localIps();
            return ips.isEmpty() ? "" : ips.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onPeers(final List<Discovery.Peer> peers) {
        post(new Runnable() {
            @Override
            public void run() {
                status.setText("空闲");
                status.setTextColor(Ui.BLUE);
                PeerDialog.show(getContext(), peers, TabPanel.this, new Runnable() {
                    @Override
                    public void run() {
                        startScan();
                    }
                });
            }
        });
    }

    /** Session 的回调都在工作线程，任何控件操作都必须 post 回主线程。 */
    @Override
    public void onLine(final String tag, final String msg) {
        post(new Runnable() {
            @Override
            public void run() {
                log.add(tag == null ? msg : tag + " " + msg);
                if ("XX".equals(tag)) {
                    status.setText("出错，看下面的日志");
                    status.setTextColor(Ui.RED);
                    status.setBackground(Ui.round(c(), 0xFFFBEAEA, 0, 9f));
                }
            }
        });
    }

    @Override
    public void onDone(final Bundle bundle, final String note) {
        last = bundle;
        post(new Runnable() {
            @Override
            public void run() {
                serveBtn.setText("当服务端");
                setBusy(false, bundle == null ? "没有结果" : "完成");
                if (bundle != null) {
                    log.add("-- 结果包已生成：" + bundle.records.size() + " 条逐秒记录");
                }
                if (note != null && !note.isEmpty()) {
                    log.add("-- " + note);
                }
            }
        });
    }
}
