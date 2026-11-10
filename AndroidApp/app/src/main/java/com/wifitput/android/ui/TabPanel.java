package com.wifitput.android.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Bundle;
import com.wifitput.android.core.Compare;
import com.wifitput.android.core.Discovery;
import com.wifitput.android.core.History;
import com.wifitput.android.core.Prefs;
import com.wifitput.android.core.Qr;
import com.wifitput.android.core.Record;
import com.wifitput.android.core.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个模式页（WiFi / 热点 / P2P）。三个页结构一样，只是参数和可用的配对方式不同：
 * 只有 P2P 页给二维码（扫码配对是为了走 Wi-Fi Direct 投屏那类场景），
 * WiFi 页和热点页靠「扫描对端」+ 手工填地址。
 */
class TabPanel extends android.widget.ScrollView implements Session.Listener, AddrSink {

    /** 主界面顶栏的全局状态：哪个页签在跑、是打流还是当服务端。 */
    interface StatusSink {
        void onStatus(TabPanel p, boolean active, boolean clientRun);
    }

    final String mode;
    private final Session session;
    private final LogBox log;
    private final RateChart chart;
    private final ResultCard resultCard;
    private final EditText labelInput;
    private final TextView nextHint;
    private StatusSink sink;
    /** 连测：总次数、当前第几次、已经完成的各次统计。 */
    private int repeatTotal = 1;
    private int repeatIndex = 1;
    private final List<Stats> repeatStats = new ArrayList<Stats>();
    private long runStartMs;
    private String lastShareText = "";
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
        chart = new RateChart(c);
        resultCard = new ResultCard(c);
        labelInput = Ui.input(c, Prefs.getStr(c, "run_label", ""), 0);
        labelInput.setHint("例如：客厅 / 换信道后");

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
        // 「下一步该点哪」随状态变化，两台手机来回换角色时不至于迷路
        nextHint = Ui.text(c, "", 12f, Ui.BLUE, true);
        nextHint.setPadding(0, Ui.dp(c, 6), 0, 0);
        c1.addView(nextHint, Ui.fill());
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

            c4.addView(Ui.cardTitle(c, "投屏直连"));
            c4.addView(Ui.hint(c, "出码这台上点「我的投屏码」，另一台用「扫码直连」扫它，"
                    + "两台就自动组好 Wi-Fi Direct 群组，地址也会自动填好。"));

            LinearLayout castRow = Ui.row(c);
            Button mineBtn = Ui.primary(c, "我的投屏码", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    log.add("-- 正在准备投屏码…");
                    if (p2p == null) {
                        log.add("!! 这台设备不支持 Wi-Fi Direct");
                        return;
                    }
                    // 出码这台必须先当上群主，码里的 MAC 才对得上，扫码那台才连得进来
                    p2p.ensureGroup(new Runnable() {
                        @Override
                        public void run() {
                            String ip = p2p.ownerIp();
                            if (ip == null || ip.length() == 0) {
                                ip = firstIp();
                            }
                            if (ip.length() == 0) {
                                log.add("!! 还没拿到本机地址，先连上 WiFi 或等一下再试");
                                return;
                            }
                            String mac = p2p.selfMac();
                            String text = Qr.encodeCast(ip, session.port, mac, p2p.selfName());
                            QrDialog.showCast(getContext(), text,
                                    "对端扫码后会自动组网。群主地址 " + ip + "，端口 " + session.port);
                            log.add("OK 投屏码已生成，群主地址 " + ip
                                    + "，设备名 " + p2p.selfName());
                        }
                    });
                }
            });
            Button scanQrBtn = Ui.ghost(c, "扫码直连", new View.OnClickListener() {
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
            castRow.addView(mineBtn, Ui.w(1.35f));
            Ui.margin(c, mineBtn, 0, 0, 8, 0);
            castRow.addView(scanQrBtn, Ui.w(1f));
            Ui.margin(c, scanQrBtn, 8, 0, 0, 0);
            c4.addView(castRow);

            // 手动那套还在，只是收起来：投屏码这条路不通时它仍是唯一退路
            Fold manual = new Fold(c, "手动组网（备用）", "p2p_manual", false);
            manual.body().addView(p2p);
            c4.addView(manual);

            root.addView(c4);
        }

        // 卡片 5：开始 + 实时数据（合并成一张，页面更紧凑）
        // 结果回传的 PC 地址不在这里配了，统一走顶栏「⋯ → 设置结果回传 PC」
        LinearLayout c6 = Ui.card(c, "运行");

        // 标签在开始前填：位置、场景，随结果一起进历史，方便事后对比
        LinearLayout labelCol = Ui.column(c);
        TextView labelTitle = Ui.text(c, "本次标签（可选，例如：客厅 / 换信道后）", 11.5f, Ui.GREY, false);
        labelTitle.setPadding(Ui.dp(c, 2), 0, 0, Ui.dp(c, 3));
        labelCol.addView(labelTitle, Ui.fill());
        labelCol.addView(labelInput, Ui.fill());
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.bottomMargin = Ui.dp(c, 10);
        c6.addView(labelCol, labelLp);

        LinearLayout runRow = Ui.row(c);
        runBtn = Ui.primary(c, "开始打流", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (session.running() || busy) {
                    stopRun();
                    return;
                }
                startRun(1);
            }
        });
        uploadBtn = Ui.ghost(c, "重传上次结果", new View.OnClickListener() {
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

        // 实时速率曲线压在日志上方：先看走势，再看逐行明细
        Ui.margin(c, chart, 0, 12, 0, 0);
        c6.addView(chart);

        // 跑完的结果卡：主数字、结论、与上次同参数对比、复测/分享
        Ui.margin(c, resultCard, 0, 10, 0, 0);
        c6.addView(resultCard);
        resultCard.setActions(new ResultCard.Actions() {
            @Override
            public void onAgain() {
                if (!session.running() && !busy) {
                    startRun(1);
                }
            }

            @Override
            public void onRepeat() {
                if (!session.running() && !busy) {
                    startRun(3);
                }
            }

            @Override
            public void onShare() {
                shareLast();
            }

            @Override
            public void onLabel() {
                editLabel();
            }
        });

        Ui.margin(c, log, 0, 8, 0, 0);
        c6.addView(log);
        root.addView(c6);

        addView(root);
        log.add("-- " + modeTitle() + " 就绪。" + session.myHint());
        updateNextHint();
    }

    // ---------------------------------------------------------------- 运行

    /** 按当前参数开始一轮；repeatCount > 1 时连续跑。 */
    private void startRun(int repeatCount) {
        if (session.serving()) {
            log.add("!! 先停掉服务端再开始打流");
            return;
        }
        if (!pullParams()) {
            return;
        }
        if (session.host == null || session.host.trim().isEmpty()) {
            log.add("!! 先填对端地址 —— 点「扫描对端」，让对方先点「当服务端」");
            updateNextHint();
            return;
        }
        session.savePrefs();
        String label = labelInput.getText().toString().trim();
        Prefs.putStr(c(), "run_label", label);
        session.label = label;
        repeatTotal = Math.max(1, repeatCount);
        repeatIndex = 1;
        repeatStats.clear();
        runStartMs = SystemClock.elapsedRealtime();
        setBusy(true, repeatTotal > 1 ? "打流中 1/" + repeatTotal + "…" : "打流中…");
        chart.reset(session.duration);
        resultCard.hide();
        updateNextHint();
        notifyStatus(true, true);
        session.runOnce();
    }

    private void stopRun() {
        removeCallbacks(nextRepeat);
        boolean wasServing = session.serving();
        repeatTotal = 1;
        session.stopServing();
        if (wasServing) {
            serveBtn.setText("当服务端");
            setBusy(false, "服务端已停止");
            setServeUi(false);
            chart.finish();
            showServerResult();
        } else {
            setBusy(false, "已停止");
        }
        updateNextHint();
        notifyStatus(false, false);
    }

    private final Runnable nextRepeat = new Runnable() {
        @Override
        public void run() {
            if (!busy) {
                return;
            }
            runStartMs = SystemClock.elapsedRealtime();
            chart.reset(session.duration);
            session.runOnce();
        }
    };

    private void shareLast() {
        if (lastShareText == null || lastShareText.isEmpty()) {
            log.add("!! 还没有可分享的结果");
            return;
        }
        Share.send(c(), lastShareText);
    }

    /** 结果卡上的「加标签」：补一个标签并写回刚存下的那条历史。 */
    private void editLabel() {
        final EditText input = Ui.input(c(), labelInput.getText().toString(), 0);
        // Ui.input 默认是带 weight 的 LinearLayout 参数，直接塞进对话框会宽度为 0
        LinearLayout box = Ui.column(c());
        box.setPadding(Ui.dp(c(), 20), Ui.dp(c(), 4), Ui.dp(c(), 20), 0);
        box.addView(input, Ui.fill());
        new AlertDialog.Builder(c())
                .setTitle("给这次结果加标签")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String s = input.getText().toString().trim();
                        labelInput.setText(s);
                        Prefs.putStr(c(), "run_label", s);
                        session.label = s;
                        if (!session.lastHistoryId().isEmpty()) {
                            History.updateLabel(c(), session.lastHistoryId(), s);
                        }
                        resultCard.setLabel(s);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 服务端停服后：把这一轮收到的记录也出一张结果卡。 */
    private void showServerResult() {
        List<Record> recs = session.serveRecords();
        Stats st = Stats.ofRecords(recs);
        if (st != null && st.hasData()) {
            String subtitle = "服务端 · 收到 " + recs.size() + " 行逐秒数据";
            resultCard.show(st, session.label, subtitle, "", "", false);
            lastShareText = Share.text(session.label, subtitle, st, "");
        } else {
            resultCard.hide();
            lastShareText = "";
        }
    }

    /** 连测时的汇总：中位数 + 区间。 */
    private String aggregateText() {
        List<Double> vals = new ArrayList<Double>();
        for (Stats s : repeatStats) {
            if (s != null && s.hasData() && s.mainMb() >= 0) {
                vals.add(s.mainMb());
            }
        }
        if (vals.size() < 2) {
            return "";
        }
        java.util.Collections.sort(vals);
        int n = vals.size();
        double median = n % 2 == 1 ? vals.get(n / 2)
                : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2.0;
        return n + " 次中位数 " + Stats.fmtMb(median) + " Mb/s（最低 "
                + Stats.fmtMb(vals.get(0)) + " · 最高 " + Stats.fmtMb(vals.get(n - 1)) + "）";
    }

    /** 结果卡的内容：标签、参数、与同参数上一次的对比、连测汇总。 */
    private void showResult(Bundle bundle, Stats stats, boolean interim, int doneIndex) {
        if (stats == null || !stats.hasData()) {
            resultCard.hide();
            lastShareText = "";
            return;
        }
        String label = session.label == null ? "" : session.label.trim();
        String time = bundle != null && bundle.startedAt != null && bundle.startedAt.length() >= 19
                ? bundle.startedAt.substring(11) : "";
        String subtitle = (time.isEmpty() ? "" : time + " · ")
                + session.kind + (session.reverse ? " 反向" : " 正向")
                + " · " + session.streams + " 流 · " + session.duration + " 秒"
                + (interim ? " · 第 " + doneIndex + "/" + repeatTotal + " 次" : "");
        String compareText = "";
        if (!interim && repeatTotal == 1 && bundle != null) {
            Compare.Hit hit = Compare.previous(c(), mode, stats,
                    session.lastHistoryId(), bundle.startedAt);
            compareText = Compare.line(hit, stats);
        }
        String agg = !interim ? aggregateText() : "";
        resultCard.show(stats, label, subtitle, compareText, agg, !interim);
        lastShareText = Share.text(label, subtitle, stats,
                compareText + (agg.isEmpty() ? "" : "\n" + agg));
    }

    /** 「下一步点哪」随状态更新。 */
    private void updateNextHint() {
        String s;
        if (session.serving()) {
            s = "服务端就绪：另一台点「扫描对端」，或手工填地址后开始打流";
        } else if (busy) {
            // 服务端用的是同一个 worker，不能用 session.running() 判断「在打流」
            s = "打流中，曲线和日志实时更新";
        } else if (host.getText().toString().trim().isEmpty()) {
            s = "下一步：另一台点「当服务端」，这台点「扫描对端」";
        } else {
            s = "下一步：点下面的「开始打流」（对端 " + host.getText().toString().trim() + "）";
        }
        nextHint.setText("→ " + s);
    }

    void setStatusSink(StatusSink s) {
        sink = s;
    }

    private void notifyStatus(boolean active, boolean clientRun) {
        if (sink != null) {
            sink.onStatus(this, active, clientRun);
        }
    }

    long runStartMs() {
        return runStartMs;
    }

    String modeTitle() {
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
        if (session.running() || session.serving() || busy) {
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
            setServeUi(false);
            // 停服时把没满一个采样的最后一段也落下来，顺便把空状态文案复位
            chart.finish();
            showServerResult();
            updateNextHint();
            notifyStatus(false, false);
            return;
        }
        // 连测的间隔里 session.running() 是 false，但整批还没结束，不能再切去当服务端
        if (busy || session.running()) {
            log.add("!! 先停掉正在跑的测试");
            return;
        }
        if (!pullParams()) {
            return;
        }
        String label = labelInput.getText().toString().trim();
        Prefs.putStr(c(), "run_label", label);
        session.label = label;
        session.savePrefs();
        serveBtn.setText("停止服务端");
        setBusy(true, "服务端就绪");
        setServeUi(true);
        // 当服务端时不知道对方打多久，横轴先按 0 秒起步、随数据往后长
        chart.reset(0);
        resultCard.hide();
        // serving 标志是在后台线程里置上的，这里先直接把提示写成服务端状态
        nextHint.setText("→ 服务端就绪：另一台点「扫描对端」，或手工填地址后开始打流");
        notifyStatus(true, false);
        session.startServing();
    }

    boolean isBusy() {
        return busy;
    }

    /** 当前是不是在打流（而不是当服务端）；连测的间隔里也算。 */
    boolean isClientRun() {
        return busy && !session.serving();
    }

    /** 当服务端时主按钮让位：暂停「开始打流」，避免和「停止服务端」两个入口。 */
    private void setServeUi(boolean serving) {
        runBtn.setEnabled(!serving);
        runBtn.setAlpha(serving ? 0.5f : 1f);
        runBtn.setText(serving ? "服务端运行中" : (busy ? "停止" : "开始打流"));
    }

    void setBusy(boolean busy, String text) {
        this.busy = busy;
        runBtn.setText(busy ? "停止" : "开始打流");
        scanBtn.setEnabled(!busy);
        scanBtn.setAlpha(busy ? 0.45f : 1f);
        // 跑的过程中改标签不会影响已经开始的这一轮，锁住避免标签和结果对不上
        labelInput.setEnabled(!busy);
        labelInput.setAlpha(busy ? 0.55f : 1f);
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
    void onQrPeer(final Qr.Peer peer) {
        if (peer == null) {
            log.add("!! 没扫到可用的投屏码");
            return;
        }
        // Android 12+ 码里的 MAC 被打码，按地址连不上；改用对端设备名搜索直连
        if (p2p != null && peer.name != null && peer.name.length() > 0) {
            log.add("-- 投屏码指向 " + peer.name + "，正在搜对端准备组网…");
            // 先把 Wi-Fi Direct 群组连起来再填地址：地址在组网前是不通的
            p2p.connectByName(peer.name, new Runnable() {
                @Override
                public void run() {
                    fillByQr(peer.ip, peer.port);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    fillByQr(peer.ip, peer.port);
                    log.add("!! 没连上对端，地址已填好但网络可能不通");
                }
            });
            return;
        }
        fillByQr(peer.ip, peer.port);
    }

    /** 按投屏码填地址，比手工/扫描多留一行日志，方便事后从日志复盘。 */
    private void fillByQr(String ip, int port) {
        fill(ip, port);
        log.add("OK 已按投屏码填好对端地址：" + ip + ":" + port);
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
        updateNextHint();
        // 填完地址下一步就是开始，直接把「开始打流」滚进视野，省一次上滑
        post(new Runnable() {
            @Override
            public void run() {
                runBtn.requestRectangleOnScreen(new android.graphics.Rect(
                        0, 0, runBtn.getWidth(), runBtn.getHeight()), true);
            }
        });
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

    /** 逐秒数据也来自工作线程，统一 post 回主线程再喂给曲线。 */
    @Override
    public void onSample(final Record rec) {
        post(new Runnable() {
            @Override
            public void run() {
                chart.add(rec);
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
                // 当服务端时，这一行说明对方开了新的一轮打流，曲线和结果卡都换新的
                if (msg != null && msg.startsWith("Accepted connection")) {
                    chart.restartOnNextSample();
                    resultCard.hide();
                    nextHint.setText("→ 对端已连上，正在接收数据…");
                }
                // 服务端起失败时后台线程会自己收尾，界面要跟着复位。
                // 只有按钮还停在「停止服务端」时才动界面，避免把刚开始的下一轮误停。
                if ("服务端已停止".equals(msg)
                        && "停止服务端".equals(serveBtn.getText().toString())) {
                    serveBtn.setText("当服务端");
                    if (busy) {
                        setBusy(false, "服务端已停止");
                    }
                    setServeUi(false);
                    updateNextHint();
                    notifyStatus(false, false);
                }
                if ("XX".equals(tag)) {
                    status.setText("出错，看下面的日志");
                    status.setTextColor(Ui.RED);
                    status.setBackground(Ui.round(c(), 0xFFFBEAEA, 0, 9f));
                    // 服务端起失败时后台线程不会走 onDone，这里要自己把按钮和顶栏复位
                    if ("停止服务端".equals(serveBtn.getText().toString())) {
                        serveBtn.setText("当服务端");
                        setBusy(false, "服务端启动失败");
                        setServeUi(false);
                        updateNextHint();
                        notifyStatus(false, false);
                    }
                }
            }
        });
    }

    @Override
    public void onDone(final Bundle bundle, final Stats stats, final String note) {
        last = bundle;
        final int doneIndex = repeatIndex;
        post(new Runnable() {
            @Override
            public void run() {
                chart.finish();
                if (stats != null && stats.hasData()) {
                    repeatStats.add(stats);
                }
                boolean cancelled = note != null && note.contains("停止");
                if (!cancelled && stats != null && stats.hasData()
                        && doneIndex < repeatTotal) {
                    repeatIndex = doneIndex + 1;
                    setBusy(true, "打流中 " + repeatIndex + "/" + repeatTotal + "…");
                    showResult(bundle, stats, true, doneIndex);
                    log.add("-- 第 " + doneIndex + "/" + repeatTotal
                            + " 次完成，1 秒后开始下一次");
                    notifyStatus(true, true);
                    postDelayed(nextRepeat, 1200);
                    return;
                }
                showResult(bundle, stats, false, doneIndex);
                serveBtn.setText("当服务端");
                setBusy(false, bundle == null ? "没有结果" : "完成");
                updateNextHint();
                notifyStatus(false, false);
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
