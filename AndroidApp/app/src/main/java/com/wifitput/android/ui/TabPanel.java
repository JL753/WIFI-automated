package com.wifitput.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wifitput.android.R;
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
 * 一个模式页（WiFi / 热点 / P2P）。
 *
 * 页面按「控制台 + 设置抽屉」组织：主页只放当前状态、数据和一个主动作；
 * 角色、地址、参数、标签、热点/P2P 的细节都收进底部抽屉。三种模式共用同一套数据区，
 * 区别只在「先建哪种链路」这一张卡。
 */
class TabPanel extends ScrollView implements Session.Listener, AddrSink {

    /** 主界面顶栏的全局状态：哪个页签在跑、是打流还是当服务端。 */
    interface StatusSink {
        void onStatus(TabPanel p, boolean active, boolean clientRun);
    }

    final String mode;
    private final Session session;
    private final LogStore log = new LogStore();
    private final RateChart chart;
    private final ResultCard resultCard;

    // 控制台（在 buildXxx 里创建，不能声明成 final）
    private TextView status;
    private TextView roleLeft;
    private TextView roleRight;
    private LinearLayout roleSeg;
    private TextView peerLine;
    private TextView stepLine;
    private TextView chipsLine;
    private TextView nextHint;
    private LinearLayout linkBox;
    private LinearLayout dataBox;
    private TextView liveRole;
    private TextView liveValue;
    private TextView liveUnit;
    private TextView liveRight;
    private LinearLayout actionRow;
    private Button primaryBtn;
    private Button secondaryBtn;
    private LinearLayout setupBarView;
    private TextView setupSummary;
    private TextView lastLog;

    // 设置抽屉里的控件
    private final EditText host;
    private final EditText port;
    private final EditText duration;
    private final EditText streams;
    private final EditText interval;
    private final EditText udpBw;
    private final EditText labelInput;
    private final Button kindBtn;
    private final Button dirBtn;
    private Dialog setupDialog;
    private LinearLayout setupSheetView;
    private TextView roleChip;
    private TextView uploadBtn;
    private HotspotBox hs;
    private P2pBox p2p;

    private StatusSink sink;
    private boolean busy;
    private String busyText = "";
    private boolean testServer;
    private boolean hotspotHost = true;
    private boolean p2pOwner = true;
    private boolean hotspotOn;
    private String hotspotIp = "";
    private boolean grouped;
    private String groupIp = "";

    private Bundle last;
    private Stats lastStats;
    /** 服务端结果没有 Bundle，导出 CSV 时用它这一轮收到的记录。 */
    private List<Record> lastServerRecords = new ArrayList<Record>();
    private String lastServerAt = "";
    private boolean lastWasServer;
    private int repeatTotal = 1;
    private int repeatIndex = 1;
    private final List<Stats> repeatStats = new ArrayList<Stats>();
    private long runStartMs;
    private String lastShareText = "";

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
        hotspotHost = Prefs.getBool(c, "hotspot_host", true);
        p2pOwner = Prefs.getBool(c, "p2p_owner", true);
        testServer = Prefs.getBool(c, "test_server",
                "hotspot".equals(mode) || "p2p".equals(mode));

        setBackgroundColor(Ui.PAGE_BG);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);

        // 先把所有控件建好：抽屉和主页共用同一批输入
        host = Ui.input(c, session.host, 0);
        host.setHint("对方手机地址");
        port = Ui.number(c, String.valueOf(session.port), 78);
        duration = Ui.number(c, String.valueOf(session.duration), 0);
        streams = Ui.number(c, String.valueOf(session.streams), 0);
        interval = Ui.number(c, String.valueOf(session.interval), 0);
        udpBw = Ui.input(c, session.udpBw, 0);
        udpBw.setEnabled("UDP".equals(session.kind));
        labelInput = Ui.input(c, Prefs.getStr(c, "run_label", ""), 0);
        labelInput.setHint("例如：客厅 / 换信道后");

        kindBtn = Ui.chip(c, kindLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                session.kind = "UDP".equals(session.kind) ? "TCP" : "UDP";
                kindBtn.setText(kindLabel());
                udpBw.setEnabled("UDP".equals(session.kind));
                updateSummary();
                renderLink();
            }
        });
        dirBtn = Ui.chip(c, dirLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                session.reverse = !session.reverse;
                dirBtn.setText(dirLabel());
                updateSummary();
                renderLink();
            }
        });

        chart = new RateChart(c);
        resultCard = new ResultCard(c);
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
            public void onCsv() {
                exportCsv();
            }

            @Override
            public void onLabel() {
                editLabel();
            }
        });

        if ("hotspot".equals(mode)) {
            hs = new HotspotBox(c, false);
            hs.setListener(new HotspotBox.Listener() {
                @Override
                public void onHotspot(boolean on, String ip) {
                    hotspotOn = on;
                    hotspotIp = ip == null ? "" : ip;
                    renderLink();
                }
            });
            hs.refresh();
        } else if ("p2p".equals(mode)) {
            p2p = new P2pBox(c);
            p2p.setListener(new P2pBox.Listener() {
                @Override
                public void onGroup(boolean grp, boolean isGo, String ip, String ssid, String pass) {
                    grouped = grp;
                    groupIp = ip == null ? "" : ip;
                    renderLink();
                }

                @Override
                public void onStatus(String s) {
                    if (busy || lastStats != null || nextHint == null) {
                        return;
                    }
                    nextHint.setText("→ " + s);
                    if (s.contains("超时") || s.contains("失败") || s.contains("没搜到")) {
                        pill("组网失败", 2);
                    } else if (s.contains("正在")) {
                        pill("组网中…", 0);
                    } else if (s.contains("已组网")) {
                        idlePill();
                    }
                }
            });
        }

        // 控制台
        LinearLayout root = Ui.column(c);
        root.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 20));

        LinearLayout console = Ui.card(c, null);
        LinearLayout head = Ui.row(c);
        TextView title = Ui.text(c, modeTitle(), 18f, Ui.INK, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        status = Ui.text(c, "空闲", 12f, Ui.BLUE, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(c, 10), Ui.dp(c, 5), Ui.dp(c, 10), Ui.dp(c, 5));
        status.setBackground(Ui.round(c, Ui.PILL_BLUE_BG, 0, 9f));
        head.addView(status);
        console.addView(head, Ui.fill());

        linkBox = Ui.column(c);
        buildLinkBox(c);
        console.addView(linkBox, Ui.fill());

        dataBox = Ui.column(c);
        dataBox.setVisibility(GONE);
        buildDataBox(c);
        console.addView(dataBox, Ui.fill());

        actionRow = Ui.row(c);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(c, 10);
        primaryBtn = Ui.primary(c, "开始打流", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPrimary();
            }
        });
        secondaryBtn = Ui.ghost(c, "设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSecondary();
            }
        });
        actionRow.addView(primaryBtn, Ui.w(1.35f));
        Ui.margin(c, primaryBtn, 0, 0, 8, 0);
        actionRow.addView(secondaryBtn, Ui.w(1f));
        console.addView(actionRow, alp);

        nextHint = Ui.text(c, "", 12f, Ui.BLUE, true);
        nextHint.setPadding(0, Ui.dp(c, 8), 0, 0);
        nextHint.setLineSpacing(Ui.dp(c, 1), 1f);
        console.addView(nextHint, Ui.fill());
        root.addView(console);

        // 设置摘要条
        final TextView[] setupText = new TextView[1];
        setupBarView = clickableBar(c, setupText, "⚙ 连接与参数", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    return;
                }
                showSetup();
            }
        });
        setupSummary = setupText[0];
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) setupBarView.getLayoutParams();
        slp.bottomMargin = Ui.dp(c, 8);
        root.addView(setupBarView, slp);

        // 日志条：只显示最新一行，点开全屏
        final TextView[] logText = new TextView[1];
        LinearLayout logBar = clickableBar(c, logText, "日志 · 等待开始", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogDialog.show(c(), modeTitle() + " · 日志", log.dump());
            }
        });
        lastLog = logText[0];
        root.addView(logBar);

        addView(root);
        log.add("-- " + modeTitle() + " 就绪。" + session.myHint());
        refreshLog();
        updateSummary();
        renderState();
    }

    // ---------------------------------------------------------------- 界面

    private void buildLinkBox(Context c) {
        roleSeg = Ui.row(c);
        roleSeg.setBackground(Ui.round(c, Ui.CHIP_BG, 0, 10f));
        roleSeg.setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2));
        roleLeft = segText(c, leftRoleLabel());
        roleRight = segText(c, rightRoleLabel());
        roleLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRole(true);
            }
        });
        roleRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRole(false);
            }
        });
        roleSeg.addView(roleLeft, Ui.w(1f));
        roleSeg.addView(roleRight, Ui.w(1f));
        linkBox.addView(roleSeg, Ui.fill());

        peerLine = Ui.text(c, "", 12f, Ui.INK, false);
        peerLine.setPadding(0, Ui.dp(c, 8), 0, 0);
        linkBox.addView(peerLine, Ui.fill());

        stepLine = Ui.text(c, "", 11f, Ui.MUTED, false);
        stepLine.setPadding(0, Ui.dp(c, 3), 0, 0);
        stepLine.setVisibility("wifi".equals(mode) ? GONE : VISIBLE);
        linkBox.addView(stepLine, Ui.fill());

        if (hs != null) {
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = Ui.dp(c, 6);
            linkBox.addView(hs, hlp);
        }

        chipsLine = Ui.text(c, "", 11.5f, Ui.MUTED, false);
        chipsLine.setPadding(0, Ui.dp(c, 6), 0, 0);
        chipsLine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSetup();
            }
        });
        linkBox.addView(chipsLine, Ui.fill());
    }

    private void buildDataBox(Context c) {
        LinearLayout live = Ui.row(c);
        LinearLayout left = Ui.column(c);
        liveRole = Ui.text(c, "当前速率", 11f, Ui.MUTED, false);
        left.addView(liveRole, Ui.fill());
        LinearLayout valueRow = Ui.row(c);
        liveValue = Ui.text(c, "--", 32f, Ui.INK, true);
        valueRow.addView(liveValue);
        liveUnit = Ui.text(c, "Mb/s", 11f, Ui.MUTED, false);
        liveUnit.setPadding(Ui.dp(c, 5), 0, 0, Ui.dp(c, 2));
        liveUnit.setGravity(Gravity.BOTTOM);
        valueRow.addView(liveUnit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(valueRow, Ui.fill());
        live.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        liveRight = Ui.text(c, "", 11.5f, Ui.MUTED, false);
        liveRight.setGravity(Gravity.END);
        live.addView(liveRight, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dataBox.addView(live, Ui.fill());

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 142));
        clp.topMargin = Ui.dp(c, 8);
        dataBox.addView(chart, clp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(c, 10);
        dataBox.addView(resultCard, rlp);
    }

    private TextView segText(Context c, String s) {
        TextView t = Ui.text(c, s, 12.5f, Ui.GREY, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        return t;
    }

    private LinearLayout clickableBar(Context c, TextView[] outText, String text,
                                      View.OnClickListener l) {
        LinearLayout bar = Ui.row(c);
        bar.setBackground(Ui.pressed(c, Ui.RESULT_BG, Ui.PILL_BLUE_BG, Ui.CARD_LINE, 12f));
        bar.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        TextView t = Ui.text(c, text, 12f, Ui.GREY, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(t);
        TextView go = Ui.text(c, "›", 16f, Ui.BLUE, true);
        go.setPadding(Ui.dp(c, 8), 0, 0, 0);
        bar.addView(go);
        bar.setOnClickListener(l);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outText[0] = t;
        return bar;
    }

    private void setBarText(TextView t, String s) {
        if (t != null) {
            t.setText(s);
        }
    }

    // ---------------------------------------------------------------- 状态

    private void renderState() {
        boolean idle = !busy && lastStats == null;
        linkBox.setVisibility(idle ? VISIBLE : GONE);
        dataBox.setVisibility(idle ? GONE : VISIBLE);
        actionRow.setVisibility(idle || busy ? VISIBLE : GONE);
        setupBarView.setAlpha(busy ? 0.55f : 1f);
        setupBarView.setEnabled(!busy);

        if (idle) {
            idlePill();
            renderLink();
        } else if (busy) {
            pill(busyText == null || busyText.isEmpty() ? "打流中" : busyText, 1);
            liveRole.setText("当前速率");
            if (testServer) {
                liveRight.setText("服务端接收中");
            } else {
                liveRight.setText(repeatTotal > 1
                        ? "第 " + repeatIndex + "/" + repeatTotal + " 次"
                        : "打流中");
            }
            primaryBtn.setText(testServer ? "停止服务端" : "停止");
            secondaryBtn.setText("日志");
            primaryBtn.setVisibility(VISIBLE);
            secondaryBtn.setVisibility(VISIBLE);
            updateLive();
            updateNextHint();
        } else {
            pill("完成", 1);
            liveRole.setText(lastStats != null && lastStats.receiverMb >= 0 ? "接收端" : "平均速率");
            liveValue.setText(lastStats == null ? "--" : Stats.fmtMb(lastStats.mainMb()));
            liveRight.setText(lastStats == null ? "" : ratioText(lastStats));
            primaryBtn.setVisibility(GONE);
            secondaryBtn.setVisibility(GONE);
            updateNextHint();
        }
        notifyStatus(busy, busy && !testServer);
    }

    private void idlePill() {
        if ("hotspot".equals(mode)) {
            if (hotspotHost && !hotspotOn) {
                pill("热点未开", 2);
            } else {
                pill(testServer ? "待命" : "空闲", 0);
            }
            return;
        }
        if ("p2p".equals(mode)) {
            if (!grouped) {
                pill("未组网", 2);
            } else {
                pill(testServer ? "待命" : "空闲", 0);
            }
            return;
        }
        pill(testServer ? "待命" : "空闲", 0);
    }

    private void pill(String text, int level) {
        status.setText(text);
        if (level == 2) {
            status.setTextColor(Ui.AMBER);
            status.setBackground(Ui.round(c(), Ui.WARN_BG, 0, 9f));
        } else if (level == 1) {
            status.setTextColor(Ui.GREEN);
            status.setBackground(Ui.round(c(), Ui.SUCCESS_BG, 0, 9f));
        } else {
            status.setTextColor(Ui.BLUE);
            status.setBackground(Ui.round(c(), Ui.PILL_BLUE_BG, 0, 9f));
        }
    }

    private void renderLink() {
        if (busy || lastStats != null || linkBox == null || status == null) {
            return;
        }
        paintSeg();
        String hostText = host.getText().toString().trim();
        String portText = String.valueOf(Ui.num(port, session.port));
        boolean client = !testServer;

        if ("wifi".equals(mode)) {
            peerLine.setText(testServer
                    ? "本机 " + localIp() + " · 端口 " + portText
                    : (hostText.isEmpty() ? "还没选对端" : "对端 " + hostText + ":" + portText));
            peerLine.setTextColor(Ui.INK);
            chipsLine.setText(paramsText() + "   ·   设置 ›");
            if (testServer) {
                primaryBtn.setText("当服务端");
                secondaryBtn.setVisibility(GONE);
                nextHint.setText("→ 下一步：点「当服务端」，等另一台扫描并发起打流");
            } else if (hostText.isEmpty()) {
                primaryBtn.setText("扫描对端");
                secondaryBtn.setText("手工填写");
                secondaryBtn.setVisibility(VISIBLE);
                nextHint.setText("→ 下一步：另一台点「当服务端」，这台点「扫描对端」");
            } else {
                primaryBtn.setText("开始打流");
                secondaryBtn.setText("重新扫描");
                secondaryBtn.setVisibility(VISIBLE);
                nextHint.setText("→ 下一步：点「开始打流」（对端 " + hostText + "）");
            }
        } else if ("hotspot".equals(mode)) {
            if (hs != null) {
                hs.setVisibility(hotspotHost ? VISIBLE : GONE);
            }
            if (testServer) {
                peerLine.setText(hotspotHost
                        ? (hotspotOn ? "热点已开启 · 本机 " + safeIp(hotspotIp) : "本机热点未开启")
                        : (hostText.isEmpty() ? "已连对方热点 · 还没选对端" : "对端 " + hostText + ":" + portText));
                if (hotspotHost && !hotspotOn) {
                    primaryBtn.setText("去系统设置开热点");
                    secondaryBtn.setText("重新检测");
                    secondaryBtn.setVisibility(VISIBLE);
                } else if (hotspotHost) {
                    primaryBtn.setText("当服务端");
                    secondaryBtn.setText("重新检测");
                    secondaryBtn.setVisibility(VISIBLE);
                } else {
                    primaryBtn.setText("当服务端");
                    secondaryBtn.setText("设置");
                    secondaryBtn.setVisibility(VISIBLE);
                }
                nextHint.setText(hotspotHost && !hotspotOn
                        ? "→ 下一步：在系统设置打开便携式热点，回来点「重新检测」"
                        : "→ 下一步：点「当服务端」，另一台连上热点后扫描你");
            } else {
                peerLine.setText(hostText.isEmpty()
                        ? (hotspotHost ? "本机热点已开 · 还没选对端" : "先在系统 WLAN 连上对方热点")
                        : "对端 " + hostText + ":" + portText);
                primaryBtn.setText(hostText.isEmpty() ? "扫描对端" : "开始打流");
                secondaryBtn.setText(hostText.isEmpty()
                        ? (hotspotHost ? "手工填写" : "去 WLAN 设置") : "重新扫描");
                secondaryBtn.setVisibility(VISIBLE);
                nextHint.setText(hostText.isEmpty()
                        ? (hotspotHost ? "→ 下一步：扫描对端，或手工填对方的地址"
                        : "→ 下一步：WLAN 连上对方热点 → 扫描对端 → 开始打流")
                        : "→ 下一步：点「开始打流」（对端 " + hostText + "）");
            }
            stepLine.setText(stepText(hotspotHost ? hotspotOn : true, hostText));
            chipsLine.setText(paramsText() + "   ·   设置 ›");
        } else {
            if (testServer) {
                peerLine.setText(p2pOwner
                        ? (grouped ? "已组网 · 群主 " + safeIp(groupIp) : "还没组网")
                        : (hostText.isEmpty() ? "已加入对方群组 · 还没选对端" : "对端 " + hostText + ":" + portText));
                if (!grouped) {
                    primaryBtn.setText(p2pOwner ? "我的投屏码" : "扫码直连");
                    secondaryBtn.setText("手动组网");
                } else {
                    primaryBtn.setText("当服务端");
                    secondaryBtn.setText("手动组网");
                }
                secondaryBtn.setVisibility(VISIBLE);
                nextHint.setText(!grouped
                        ? (p2pOwner ? "→ 下一步：点「我的投屏码」，另一台扫码加入"
                        : "→ 下一步：点「扫码直连」加入对方的群组")
                        : "→ 下一步：点「当服务端」，等另一台开始打流");
            } else {
                peerLine.setText(hostText.isEmpty()
                        ? (grouped ? "已组网 · 群主 " + safeIp(groupIp) : "还没组网")
                        : "对端 " + hostText + ":" + portText);
                if (!grouped) {
                    primaryBtn.setText(p2pOwner ? "我的投屏码" : "扫码直连");
                    secondaryBtn.setText("手动组网");
                } else if (hostText.isEmpty()) {
                    primaryBtn.setText("扫描对端");
                    secondaryBtn.setText("手动组网");
                } else {
                    primaryBtn.setText("开始打流");
                    secondaryBtn.setText("重新扫描");
                }
                secondaryBtn.setVisibility(VISIBLE);
                nextHint.setText(!grouped
                        ? (p2pOwner ? "→ 下一步：点「我的投屏码」，另一台扫码加入" : "→ 下一步：点「扫码直连」加入对方的群组")
                        : (hostText.isEmpty() ? "→ 下一步：扫描对端，或从设置里手动组网" : "→ 下一步：点「开始打流」（对端 " + hostText + "）"));
            }
            stepLine.setText(stepText(grouped, hostText));
            chipsLine.setText(paramsText() + "   ·   设置 ›");
        }
    }

    private String stepText(boolean linkReady, String hostText) {
        int step;
        if (!linkReady) {
            step = 1;
        } else if (testServer) {
            step = session.serving() ? 3 : 2;
        } else {
            step = hostText.isEmpty() ? 2 : 3;
        }
        String link = "hotspot".equals(mode)
                ? (hotspotHost ? "开热点" : "连热点") : "组网";
        String second = testServer ? "当服务端" : "扫描对端";
        String third = testServer ? "对方打流" : "开始打流";
        return mark(step, 1) + "① " + link + "   "
                + mark(step, 2) + "② " + second + "   "
                + mark(step, 3) + "③ " + third;
    }

    private String mark(int current, int step) {
        return current == step ? "▶" : "";
    }

    private void paintSeg() {
        boolean leftOn;
        if ("wifi".equals(mode)) {
            leftOn = !testServer;
        } else if ("hotspot".equals(mode)) {
            leftOn = hotspotHost;
        } else {
            leftOn = p2pOwner;
        }
        paint(roleLeft, leftOn);
        paint(roleRight, !leftOn);
    }

    private void paint(TextView t, boolean on) {
        t.setBackground(on ? Ui.round(c(), Ui.SEG_ON_BG, Ui.CARD_LINE, 8f) : null);
        t.setTextColor(on ? Ui.BLUE : Ui.GREY);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private String leftRoleLabel() {
        if ("hotspot".equals(mode)) {
            return "我开热点";
        }
        if ("p2p".equals(mode)) {
            return "我出码";
        }
        return "我来打流";
    }

    private String rightRoleLabel() {
        if ("hotspot".equals(mode)) {
            return "我连热点";
        }
        if ("p2p".equals(mode)) {
            return "我扫码";
        }
        return "我来当服务端";
    }

    private void onRole(boolean left) {
        if (busy) {
            return;
        }
        if ("wifi".equals(mode)) {
            testServer = !left;
        } else if ("hotspot".equals(mode)) {
            hotspotHost = left;
            testServer = left;
            if (hs != null) {
                hs.refresh();
            }
        } else {
            p2pOwner = left;
            testServer = left;
        }
        saveRoles();
        updateSummary();
        renderState();
    }

    private void updateLive() {
        if (!busy) {
            return;
        }
        float v = chart.lastValue();
        liveValue.setText(v >= 0 ? Stats.fmtMb(v) : "--");
    }

    private void updateNextHint() {
        if (busy) {
            nextHint.setText(testServer
                    ? "→ 服务端接收中，等对端打完；点「停止服务端」结束"
                    : "→ 打流中，曲线和日志实时更新");
        } else if (lastStats != null) {
            nextHint.setText("→ 完成，可以再测一次、连测 3 次或分享结果");
        }
    }

    private String ratioText(Stats st) {
        if (st == null || st.linkMbps <= 0 || st.mainMb() <= 0) {
            return "";
        }
        return "约协商 " + Math.round(st.mainMb() * 100.0 / st.linkMbps) + "%";
    }

    private void refreshLog() {
        String s = log.last();
        setBarText(lastLog, "日志 · " + (s == null || s.isEmpty() ? "等待开始" : s));
    }

    private void updateSummary() {
        String label = labelInput.getText().toString().trim();
        String role = testServer ? "服务端" : "客户端";
        String s = paramsText() + " · " + role + (label.isEmpty() ? "" : " · " + label);
        setBarText(setupSummary, "⚙ 设置 · " + s);
        if (chipsLine != null && !busy && lastStats == null) {
            chipsLine.setText(paramsText() + "   ·   设置 ›");
        }
        if (roleChip != null) {
            roleChip.setText(roleChipText());
        }
    }

    private String roleChipText() {
        return "测试角色：" + (testServer ? "服务端" : "客户端") + "（点此交换）";
    }

    private String paramsText() {
        int d = Ui.num(duration, session.duration);
        int s = Ui.num(streams, session.streams);
        int iv = Ui.num(interval, session.interval);
        StringBuilder sb = new StringBuilder();
        sb.append(session.kind).append(session.reverse ? " 反向" : " 正向");
        sb.append(" · ").append(s).append(" 流 · ").append(d).append(" 秒");
        if (iv != 1) {
            sb.append(" · 采样 ").append(iv).append(" 秒");
        }
        if ("UDP".equals(session.kind)) {
            String bw = udpBw.getText().toString().trim();
            sb.append(" · 限速 ").append(bw.isEmpty() ? "50M" : bw);
        }
        return sb.toString();
    }

    private String safeIp(String ip) {
        return ip == null || ip.isEmpty() ? "地址未知" : ip;
    }

    private String localIp() {
        try {
            List<String> ips = com.wifitput.android.core.Upload.localIps();
            return ips.isEmpty() ? "本机地址未知" : ips.get(0);
        } catch (Exception e) {
            return "本机地址未知";
        }
    }

    // ---------------------------------------------------------------- 动作

    private void onPrimary() {
        if (busy) {
            stopRun();
            return;
        }
        if (lastStats != null) {
            return;
        }
        String h = host.getText().toString().trim();
        if (testServer) {
            if ("hotspot".equals(mode) && hotspotHost && !hotspotOn) {
                if (hs != null) {
                    hs.openSettings();
                }
                return;
            }
            if ("p2p".equals(mode) && !grouped) {
                if (p2pOwner) {
                    makeQr();
                } else {
                    scanQr();
                }
                return;
            }
            toggleServeStart();
            return;
        }
        if ("p2p".equals(mode) && !grouped) {
            if (p2pOwner) {
                makeQr();
            } else {
                scanQr();
            }
            return;
        }
        if (h.isEmpty()) {
            startScan();
        } else {
            startRun(1);
        }
    }

    private void onSecondary() {
        if (busy) {
            LogDialog.show(c(), modeTitle() + " · 日志", log.dump());
            return;
        }
        String h = host.getText().toString().trim();
        if (testServer) {
            if ("hotspot".equals(mode)) {
                if (hotspotHost && hs != null) {
                    hs.refresh();
                } else {
                    showSetup();
                }
            } else if ("p2p".equals(mode)) {
                // 手动组网永远在设置抽屉里；主按钮负责出码/扫码
                showSetup();
            } else {
                showSetup();
            }
            return;
        }
        if ("hotspot".equals(mode) && h.isEmpty()) {
            if (hotspotHost) {
                showSetup();
            } else {
                openWlanSettings();
            }
            return;
        }
        if ("wifi".equals(mode) && h.isEmpty()) {
            showSetup();
            return;
        }
        if ("p2p".equals(mode) && h.isEmpty()) {
            showSetup();
            return;
        }
        startScan();
    }

    private void toggleServeStart() {
        if (!pullParams()) {
            return;
        }
        session.label = labelInput.getText().toString().trim();
        Prefs.putStr(c(), "run_label", session.label);
        session.savePrefs();
        setBusy(true, "服务端就绪");
        chart.reset(0);
        resultCard.hide();
        lastStats = null;
        renderState();
        session.startServing();
    }

    private void startRun(int repeatCount) {
        if (!pullParams()) {
            return;
        }
        if (session.host == null || session.host.trim().isEmpty()) {
            log.add("!! 先填对端地址 —— 点「扫描对端」，让对方先点「当服务端」");
            refreshLog();
            updateSummary();
            return;
        }
        session.label = labelInput.getText().toString().trim();
        Prefs.putStr(c(), "run_label", session.label);
        session.savePrefs();
        repeatTotal = Math.max(1, repeatCount);
        repeatIndex = 1;
        repeatStats.clear();
        lastStats = null;
        runStartMs = SystemClock.elapsedRealtime();
        setBusy(true, repeatTotal > 1 ? "打流中 1/" + repeatTotal + "…" : "打流中…");
        chart.reset(session.duration);
        resultCard.hide();
        renderState();
        session.runOnce();
    }

    private void stopRun() {
        removeCallbacks(nextRepeat);
        boolean wasServing = session.serving();
        repeatTotal = 1;
        session.stopServing();
        if (wasServing) {
            chart.finish();
            showServerResult();
            setBusy(false, "服务端已停止");
        } else {
            setBusy(false, "已停止");
        }
        renderState();
    }

    private final Runnable nextRepeat = new Runnable() {
        @Override
        public void run() {
            if (!busy) {
                return;
            }
            runStartMs = SystemClock.elapsedRealtime();
            chart.reset(session.duration);
            resultCard.hide();
            lastStats = null;
            liveValue.setText("--");
            renderState();
            session.runOnce();
        }
    };

    private void showServerResult() {
        List<Record> recs = session.serveRecords();
        Stats st = Stats.ofRecords(recs);
        lastWasServer = true;
        lastServerRecords = recs;
        lastServerAt = Bundle.stamp();
        if (st != null && st.hasData()) {
            lastStats = st;
            String subtitle = "服务端 · 收到 " + recs.size() + " 行逐秒数据";
            resultCard.show(st, session.label, subtitle, "", "", false, !recs.isEmpty());
            lastShareText = Share.text(session.label, subtitle, st, "");
        } else {
            lastStats = null;
            resultCard.hide();
            lastShareText = "";
        }
    }

    private void showResult(Bundle bundle, Stats stats, boolean interim, int doneIndex) {
        if (stats == null || !stats.hasData()) {
            resultCard.hide();
            lastShareText = "";
            return;
        }
        lastStats = stats;
        lastWasServer = false;
        lastServerRecords = new ArrayList<Record>();
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
        resultCard.show(stats, label, subtitle, compareText, agg,
                !interim, !interim && bundle != null);
        liveRole.setText(stats.receiverMb >= 0 ? "接收端" : "平均速率");
        liveValue.setText(Stats.fmtMb(stats.mainMb()));
        liveRight.setText(ratioText(stats));
        lastShareText = Share.text(label, subtitle, stats,
                compareText + (agg.isEmpty() ? "" : "\n" + agg));
    }

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

    private void exportCsv() {
        if (lastWasServer) {
            CsvExport.shareRecords(c(), session.label, lastServerAt, lastServerRecords);
            return;
        }
        if (last == null) {
            log.add("!! 还没有可导出的结果");
            refreshLog();
            return;
        }
        CsvExport.shareBundle(c(), session.label, last.startedAt, last);
    }

    private void shareLast() {
        if (lastShareText == null || lastShareText.isEmpty()) {
            log.add("!! 还没有可分享的结果");
            refreshLog();
            return;
        }
        Share.send(c(), lastShareText);
    }

    private void editLabel() {
        final EditText input = Ui.input(c(), labelInput.getText().toString(), 0);
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
                        updateSummary();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- P2P / 扫描

    private void makeQr() {
        log.add("-- 正在准备投屏码…");
        refreshLog();
        if (p2p == null) {
            log.add("!! 这台设备不支持 Wi-Fi Direct");
            refreshLog();
            return;
        }
        p2pOwner = true;
        testServer = true;
        saveRoles();
        p2p.ensureGroup(new Runnable() {
            @Override
            public void run() {
                String ip = p2p.ownerIp();
                if (ip == null || ip.length() == 0) {
                    ip = firstIp();
                }
                if (ip.length() == 0) {
                    log.add("!! 还没拿到本机地址，先连上 WiFi 或等一下再试");
                    refreshLog();
                    return;
                }
                String mac = p2p.selfMac();
                String text = Qr.encodeCast(ip, session.port, mac, p2p.selfName());
                QrDialog.showCast(c(), text,
                        "对端扫码后会自动组网。群主地址 " + ip + "，端口 " + session.port);
                log.add("OK 投屏码已生成，群主地址 " + ip + "，设备名 " + p2p.selfName());
                refreshLog();
                renderLink();
            }
        });
    }

    private void scanQr() {
        p2pOwner = false;
        testServer = false;
        saveRoles();
        Context cc = c();
        if (cc instanceof Activity) {
            QrDialog.scan((Activity) cc);
        } else {
            log.add("!! 当前界面不支持扫码");
            refreshLog();
        }
        renderLink();
    }

    private void openWlanSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
            if (!(c() instanceof Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            c().startActivity(i);
        } catch (Exception e) {
            log.add("!! 打不开 WLAN 设置，请手动进系统设置连接热点");
            refreshLog();
        }
    }

    private void startScan() {
        if (session.running() || session.serving() || busy) {
            log.add("!! 先停掉正在跑的测试再扫描");
            refreshLog();
            return;
        }
        log.add("-- 开始扫描，扫到的设备会列在弹窗里");
        refreshLog();
        pill("正在扫描…", 0);
        session.scan(3000);
    }

    private void fillByQr(String ip, int port) {
        fill(ip, port);
        log.add("OK 已按投屏码填好对端地址：" + ip + ":" + port);
        refreshLog();
    }

    @Override
    public void fill(String ip, int port) {
        host.setText(ip);
        this.port.setText(String.valueOf(port));
        session.host = ip;
        session.port = port;
        session.savePrefs();
        log.add("OK 对端地址已填入：" + ip + ":" + port);
        refreshLog();
        updateSummary();
        renderLink();
    }

    private String firstIp() {
        try {
            List<String> ips = com.wifitput.android.core.Upload.localIps();
            return ips.isEmpty() ? "" : ips.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 设置抽屉

    private void showSetup() {
        if (setupDialog == null) {
            buildSetupSheet();
        }
        if (setupDialog == null) {
            return;
        }
        if (setupSheetView != null) {
            setupSheetView.setTranslationY(0);
        }
        updateSummary();
        if (roleChip != null) {
            roleChip.setText(roleChipText());
        }
        try {
            setupDialog.show();
        } catch (Exception ignored) {
            // 窗口已销毁时忽略
        }
    }

    private void buildSetupSheet() {
        Context c = c();
        final LinearLayout sheet = Ui.column(c);
        sheet.setBackground(Ui.round(c, Ui.PAGE_BG, 0, 18f));
        setupSheetView = sheet;

        // 顶部拖拽条：按住往下拖超过 80dp 就关掉，面板有「底部抽屉」的手感
        LinearLayout handleBox = Ui.column(c);
        handleBox.setGravity(Gravity.CENTER_HORIZONTAL);
        handleBox.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 6));
        View handle = new View(c);
        handle.setBackground(Ui.round(c, Ui.CHIP_LINE, 0, 2f));
        handleBox.addView(handle, new LinearLayout.LayoutParams(
                Ui.dp(c, 36), Ui.dp(c, 4)));
        sheet.addView(handleBox, Ui.fill());

        final float[] startY = new float[1];
        View.OnTouchListener drag = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int action = e.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    startY[0] = e.getRawY();
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_MOVE) {
                    sheet.setTranslationY(Math.max(0f, e.getRawY() - startY[0]));
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    float dy = sheet.getTranslationY();
                    if (dy > Ui.dp(c, 80)) {
                        sheet.setTranslationY(0);
                        if (setupDialog != null) {
                            setupDialog.dismiss();
                        }
                    } else {
                        sheet.animate().translationY(0).setDuration(160).start();
                    }
                    return true;
                }
                return false;
            }
        };
        handleBox.setOnTouchListener(drag);

        LinearLayout head = Ui.row(c);
        head.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 8));
        TextView t = Ui.text(c, "设置与连接", 17f, Ui.INK, true);
        // 标题区也能拖，比只拖那根 4dp 的小条好按
        t.setOnTouchListener(drag);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = Ui.text(c, "完成", 14f, Ui.BLUE, true);
        done.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), 0, Ui.dp(c, 4));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (setupDialog != null) {
                    setupDialog.dismiss();
                }
            }
        });
        head.addView(done);
        sheet.addView(head, Ui.fill());

        LinearLayout body = Ui.column(c);
        body.setPadding(Ui.dp(c, 14), Ui.dp(c, 2), Ui.dp(c, 14), Ui.dp(c, 16));

        // 角色与连接
        LinearLayout roleCard = Ui.card(c, "角色与连接");
        roleCard.addView(Ui.hint(c, modeHint()), Ui.fill());
        roleChip = Ui.text(c, roleChipText(), 12f, Ui.BLUE, true);
        roleChip.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        roleChip.setBackground(Ui.pressed(c, Ui.PILL_BLUE_BG, Ui.CHIP_BG, Ui.CHIP_LINE, 9f));
        roleChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    return;
                }
                testServer = !testServer;
                saveRoles();
                roleChip.setText(roleChipText());
                updateSummary();
                renderState();
            }
        });
        roleCard.addView(roleChip, Ui.fill());
        roleCard.addView(Ui.hint(c, "推荐：开热点 / 出码那台当服务端；需要反过来就在这里交换。"), Ui.fill());
        if (p2p != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = Ui.dp(c, 10);
            roleCard.addView(p2p, plp);
        }
        body.addView(roleCard);

        // 对端地址
        LinearLayout addrCard = Ui.card(c, "对端地址");
        LinearLayout hostRow = Ui.row(c);
        LinearLayout hostField = Ui.field(c, "对方手机地址", host, 1f);
        Ui.margin(c, hostField, 0, 0, 8, 0);
        hostRow.addView(hostField);
        hostRow.addView(Ui.narrowField(c, "端口", port, 78));
        addrCard.addView(hostRow, Ui.fill());
        Button scan = Ui.ghost(c, "扫描对端", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanLp.topMargin = Ui.dp(c, 8);
        addrCard.addView(scan, scanLp);
        addrCard.addView(Ui.hint(c, "也可以照对方顶栏的地址手工填。"), Ui.fill());
        body.addView(addrCard);

        // 参数
        LinearLayout paramCard = Ui.card(c, "测试参数");
        LinearLayout row1 = Ui.row(c);
        LinearLayout f1 = Ui.field(c, "时长(秒)", duration, 1f);
        LinearLayout f2 = Ui.field(c, "并发流", streams, 1f);
        LinearLayout f3 = Ui.field(c, "采样(秒)", interval, 1f);
        Ui.margin(c, f1, 0, 0, 8, 0);
        Ui.margin(c, f2, 0, 0, 8, 0);
        row1.addView(f1);
        row1.addView(f2);
        row1.addView(f3);
        paramCard.addView(row1, Ui.fill());

        LinearLayout row2 = Ui.row(c);
        Ui.margin(c, row2, 0, 10, 0, 0);
        row2.addView(kindBtn);
        Ui.margin(c, kindBtn, 0, 0, 8, 0);
        row2.addView(dirBtn);
        Ui.margin(c, dirBtn, 0, 0, 10, 0);
        row2.addView(Ui.field(c, "UDP 限速", udpBw, 1f));
        paramCard.addView(row2, Ui.fill());
        body.addView(paramCard);

        // 标签
        LinearLayout labelCard = Ui.card(c, "本次标签");
        labelCard.addView(labelInput, Ui.fill());
        labelCard.addView(Ui.hint(c, "例如：客厅 / 换信道后。会显示在结果卡和历史里。"), Ui.fill());
        body.addView(labelCard);

        // 结果回传
        LinearLayout pcCard = Ui.card(c, "结果回传");
        uploadBtn = Ui.ghost(c, "重传上次结果", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (last == null) {
                    log.add("!! 还没有可重发的结果");
                    refreshLog();
                    return;
                }
                if (!pullParams()) {
                    return;
                }
                session.savePrefs();
                log.add("-- 重新上传上一轮结果");
                refreshLog();
                session.upload(last, "");
            }
        });
        pcCard.addView(uploadBtn, Ui.fill());
        pcCard.addView(Ui.hint(c, "PC 地址在顶栏「⋯ → 设置结果回传 PC」里配。"), Ui.fill());
        body.addView(pcCard);

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setupDialog = new Dialog(c);
        setupDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        setupDialog.setContentView(sheet);
        setupDialog.setCanceledOnTouchOutside(true);
        Window w = setupDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (dm.heightPixels * 0.86f));
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(R.style.SheetAnimation);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        setupDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                updateSummary();
                renderLink();
            }
        });
    }

    private void saveRoles() {
        Prefs.putBool(c(), "test_server", testServer);
        Prefs.putBool(c(), "hotspot_host", hotspotHost);
        Prefs.putBool(c(), "p2p_owner", p2pOwner);
    }

    /** 换外观前把抽屉里还没开始的参数存下来，Activity 重建后不会丢。 */
    void persistSettings() {
        if (pullParams()) {
            session.savePrefs();
            saveRoles();
            Prefs.putStr(c(), "run_label", labelInput.getText().toString().trim());
        }
    }

    private boolean pullParams() {
        int d = Ui.num(duration, session.duration);
        if (d < 1 || d > 3600) {
            log.add("!! 时长要在 1 到 3600 秒之间");
            refreshLog();
            return false;
        }
        int s = Ui.num(streams, session.streams);
        if (s < 1 || s > 32) {
            log.add("!! 并发流要在 1 到 32 之间");
            refreshLog();
            return false;
        }
        int iv = Ui.num(interval, session.interval);
        if (iv < 1 || iv > 60) {
            log.add("!! 采样间隔要在 1 到 60 秒之间");
            refreshLog();
            return false;
        }
        int p = Ui.num(port, session.port);
        if (p < 1 || p > 65535) {
            log.add("!! iperf3 端口要在 1 到 65535 之间");
            refreshLog();
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

    // ---------------------------------------------------------------- 状态回调

    private void setBusy(boolean b, String text) {
        busy = b;
        busyText = text == null ? "" : text;
        setupBarView.setEnabled(!b);
        setupBarView.setAlpha(b ? 0.55f : 1f);
        renderState();
    }

    void setStatusSink(StatusSink s) {
        sink = s;
    }

    private void notifyStatus(boolean active, boolean clientRun) {
        if (sink != null) {
            sink.onStatus(this, active, clientRun);
        }
    }

    boolean isBusy() {
        return busy;
    }

    boolean isClientRun() {
        return busy && !testServer;
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
            return "热点要开在系统设置里。开热点这台建议当服务端，另一台连上热点后扫描对端。";
        }
        if ("p2p".equals(mode)) {
            return "两台不接路由器，用 Wi-Fi Direct 直接互联后再打流。出码这台建议当服务端。";
        }
        return "两台手机连同一个 WiFi，一台当服务端，另一台扫描对端后开始打流。";
    }

    private String kindLabel() {
        return "UDP".equals(session.kind) ? "协议 UDP" : "协议 TCP";
    }

    private String dirLabel() {
        return session.reverse ? "方向 反向 -R" : "方向 正向";
    }

    private Context c() {
        return getContext();
    }

    void stopAll() {
        removeCallbacks(nextRepeat);
        session.stopServing();
        session.savePrefs();
        saveRoles();
        if (p2p != null) {
            p2p.shutdown();
        }
    }

    void refreshHotspot() {
        if (hs != null) {
            hs.refresh();
        }
    }

    /** 扫码结果回来时由宿主 Activity 调用。 */
    void onQrPeer(final Qr.Peer peer) {
        if (peer == null) {
            log.add("!! 没扫到可用的投屏码");
            refreshLog();
            return;
        }
        p2pOwner = false;
        testServer = false;
        saveRoles();
        renderLink();
        if (p2p != null && peer.name != null && peer.name.length() > 0) {
            log.add("-- 投屏码指向 " + peer.name + "，正在搜对端准备组网…");
            refreshLog();
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
                    refreshLog();
                }
            });
            return;
        }
        fillByQr(peer.ip, peer.port);
    }

    @Override
    public void onPeers(final List<Discovery.Peer> peers) {
        post(new Runnable() {
            @Override
            public void run() {
                if (!busy && lastStats == null) {
                    idlePill();
                }
                PeerDialog.show(getContext(), peers, TabPanel.this, new Runnable() {
                    @Override
                    public void run() {
                        startScan();
                    }
                });
            }
        });
    }

    @Override
    public void onSample(final Record rec) {
        post(new Runnable() {
            @Override
            public void run() {
                chart.add(rec);
                updateLive();
            }
        });
    }

    @Override
    public void onLine(final String tag, final String msg) {
        post(new Runnable() {
            @Override
            public void run() {
                log.add(tag == null ? msg : tag + " " + msg);
                refreshLog();
                if (msg != null && msg.startsWith("Accepted connection")) {
                    chart.restartOnNextSample();
                    resultCard.hide();
                    lastStats = null;
                    busyText = "接收中";
                    renderState();
                    nextHint.setText("→ 对端已连上，正在接收数据…");
                }
                if ("服务端已停止".equals(msg)) {
                    if (busy) {
                        setBusy(false, "服务端已停止");
                    }
                    renderState();
                }
                if ("XX".equals(tag)) {
                    if (busy && testServer) {
                        setBusy(false, "服务端启动失败");
                        renderState();
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
                    refreshLog();
                    postDelayed(nextRepeat, 1200);
                    return;
                }
                showResult(bundle, stats, false, doneIndex);
                setBusy(false, "完成");
                if (bundle != null) {
                    log.add("-- 结果包已生成：" + bundle.records.size() + " 条逐秒记录");
                }
                if (note != null && !note.isEmpty()) {
                    log.add("-- " + note);
                }
                refreshLog();
                updateSummary();
            }
        });
    }
}
