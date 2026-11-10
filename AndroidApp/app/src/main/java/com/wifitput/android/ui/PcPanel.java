package com.wifitput.android.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
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
import com.wifitput.android.core.Discovery;
import com.wifitput.android.core.History;
import com.wifitput.android.core.PcPing;
import com.wifitput.android.core.Prefs;
import com.wifitput.android.core.Record;
import com.wifitput.android.core.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 「连PC」页：手机对着同网段 PC 跑空闲 ping + 下载 + 上传。 */
class PcPanel extends ScrollView implements PanelHost, PcSession.Listener {

    private final PcSession session;
    private final LogStore log = new LogStore();
    private final RateChart chart;
    private final PcResultCard resultCard;

    private TextView status;
    private TextView pcLine;
    private TextView phaseLine;
    private TextView liveValue;
    private TextView liveRole;
    private TextView nextHint;
    private LinearLayout dataBox;
    private LinearLayout actionRow;
    private Button primaryBtn;
    private Button secondaryBtn;
    private LinearLayout setupBarView;
    private TextView setupSummary;
    private TextView lastLog;

    private EditText host;
    private EditText iperfPort;
    private EditText httpPort;
    private EditText durationInput;
    private EditText streamsInput;
    private EditText intervalInput;
    private EditText distanceInput;
    private EditText labelInput;
    private TextView durationChip;
    private TextView locationChip;

    private Dialog setupDialog;
    private LinearLayout setupSheetView;
    private TextView roleChip;

    private String serverLabel = "";
    private String serverVersion = "";
    private String location = "";
    private boolean busy;
    private long runStartMs;
    private PanelHost.StatusSink sink;

    private Bundle lastBundle;
    private Stats lastDown;
    private Stats lastUp;
    private PcPing.Result lastPing;
    private String lastShareText = "";
    private String lastHistoryId = "";

    private static final int[] PRESETS = {60, 7200, 28800, 86400};

    PcPanel(Context c) {
        super(c);
        session = new PcSession(c, this);
        location = Prefs.getStr(c, "pc_location", "");
        setBackgroundColor(Ui.PAGE_BG);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);

        host = Ui.input(c, Prefs.getStr(c, "pc_single_host", ""), 0);
        host.setHint("PC 的局域网地址");
        iperfPort = Ui.number(c, String.valueOf(Prefs.getInt(c, "pc_iperf_port", 5201)), 78);
        httpPort = Ui.number(c, String.valueOf(Prefs.getInt(c, "pc_http_port", 8737)), 78);
        durationInput = Ui.number(c, String.valueOf(Prefs.getInt(c, "pc_duration", 60)), 0);
        streamsInput = Ui.number(c, String.valueOf(Prefs.getInt(c, "pc_streams", 4)), 0);
        intervalInput = Ui.number(c, String.valueOf(Prefs.getInt(c, "pc_interval", 1)), 0);
        int distSaved = Prefs.getInt(c, "pc_distance", -1);
        distanceInput = Ui.number(c, distSaved >= 0 ? String.valueOf(distSaved) : "", 0);
        distanceInput.setHint("米，可选");
        labelInput = Ui.input(c, Prefs.getStr(c, "pc_label", ""), 0);
        labelInput.setHint("例如：实验室A / 近点");

        chart = new RateChart(c);
        resultCard = new PcResultCard(c);
        resultCard.setActions(new PcResultCard.Actions() {
            @Override
            public void onAgain() {
                if (!busy) {
                    startTest();
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

        LinearLayout root = Ui.column(c);
        root.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 20));

        LinearLayout console = Ui.card(c, null);
        LinearLayout head = Ui.row(c);
        TextView title = Ui.text(c, "连PC", 18f, Ui.INK, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        status = Ui.text(c, "未连接", 12f, Ui.BLUE, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(c, 10), Ui.dp(c, 5), Ui.dp(c, 10), Ui.dp(c, 5));
        status.setBackground(Ui.round(c, Ui.PILL_BLUE_BG, 0, 9f));
        head.addView(status);
        console.addView(head, Ui.fill());

        pcLine = Ui.text(c, "PC 服务端：未选择", 12f, Ui.INK, false);
        pcLine.setPadding(0, Ui.dp(c, 8), 0, 0);
        console.addView(pcLine, Ui.fill());

        phaseLine = Ui.text(c, "", 11.5f, Ui.MUTED, false);
        phaseLine.setPadding(0, Ui.dp(c, 3), 0, 0);
        console.addView(phaseLine, Ui.fill());

        dataBox = Ui.column(c);
        dataBox.setVisibility(GONE);
        LinearLayout live = Ui.row(c);
        LinearLayout left = Ui.column(c);
        liveRole = Ui.text(c, "当前速率", 11f, Ui.MUTED, false);
        left.addView(liveRole, Ui.fill());
        LinearLayout vr = Ui.row(c);
        liveValue = Ui.text(c, "--", 30f, Ui.INK, true);
        vr.addView(liveValue);
        TextView unit = Ui.text(c, "Mb/s", 11f, Ui.MUTED, false);
        unit.setPadding(Ui.dp(c, 5), 0, 0, Ui.dp(c, 2));
        unit.setGravity(Gravity.BOTTOM);
        vr.addView(unit);
        left.addView(vr, Ui.fill());
        live.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dataBox.addView(live, Ui.fill());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 142));
        clp.topMargin = Ui.dp(c, 6);
        dataBox.addView(chart, clp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(c, 10);
        dataBox.addView(resultCard, rlp);
        console.addView(dataBox, Ui.fill());

        actionRow = Ui.row(c);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(c, 10);
        primaryBtn = Ui.primary(c, "扫描 PC", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPrimary();
            }
        });
        secondaryBtn = Ui.ghost(c, "设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSetup();
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

        final TextView[] setupText = new TextView[1];
        setupBarView = bar(c, setupText, "⚙ 设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!busy) {
                    showSetup();
                }
            }
        });
        setupSummary = setupText[0];
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) setupBarView.getLayoutParams();
        slp.bottomMargin = Ui.dp(c, 8);
        root.addView(setupBarView, slp);

        final TextView[] logText = new TextView[1];
        LinearLayout logBar = bar(c, logText, "日志 · 等待开始", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogDialog.show(c(), "连PC · 日志", log.dump());
            }
        });
        lastLog = logText[0];
        root.addView(logBar);

        addView(root);
        log.add("-- 连PC 就绪。" + Prefs.getStr(c, "pc_single_host", ""));
        refreshLog();
        updateSummary();
        renderIdle();
    }

    // ---------------------------------------------------------------- 动作

    private void onPrimary() {
        if (busy) {
            session.stop();
            setBusy(true, "停止中…");
            return;
        }
        if (host.getText().toString().trim().isEmpty()) {
            scanPc();
            return;
        }
        startTest();
    }

    private void startTest() {
        if (!pullParams()) {
            return;
        }
        saveSettings();
        resultCard.hide();
        chart.reset(0);
        serverLabel = serverLabel == null ? "" : serverLabel;
        session.host = host.getText().toString().trim();
        session.iperfPort = Ui.num(iperfPort, 5201);
        session.httpPort = Ui.num(httpPort, 0);
        session.serverLabel = serverLabel;
        session.serverVersion = serverVersion;
        session.duration = Ui.num(durationInput, 60);
        session.streams = Ui.num(streamsInput, 4);
        session.longRun = session.duration > 3600;
        int iv = Ui.num(intervalInput, 1);
        session.interval = session.longRun ? Math.max(5, iv) : iv;
        session.locationPreset = locationValue();
        session.distanceM = Ui.num(distanceInput, -1);
        session.label = labelInput.getText().toString().trim();
        session.pingCount = 10;
        lastBundle = null;
        lastDown = null;
        lastUp = null;
        lastPing = null;
        runStartMs = SystemClock.elapsedRealtime();
        setBusy(true, "测试中…");
        notifyStatus(true, true);
        session.run();
    }

    private boolean pullParams() {
        int d = Ui.num(durationInput, 60);
        if (d < 1 || d > 86400) {
            log.add("!! 时长要在 1 到 86400 秒之间");
            refreshLog();
            return false;
        }
        int s = Ui.num(streamsInput, 4);
        if (s < 1 || s > 32) {
            log.add("!! 并发流要在 1 到 32 之间");
            refreshLog();
            return false;
        }
        int iv = Ui.num(intervalInput, 1);
        if (iv < 1 || iv > 60) {
            log.add("!! 采样间隔要在 1 到 60 秒之间");
            refreshLog();
            return false;
        }
        int p = Ui.num(iperfPort, 5201);
        if (p < 1 || p > 65535) {
            log.add("!! iperf3 端口要在 1 到 65535 之间");
            refreshLog();
            return false;
        }
        return true;
    }

    private void scanPc() {
        if (busy) {
            return;
        }
        log.add("-- 正在扫描同一网段的 PC 服务端…");
        refreshLog();
        setStatus("正在扫描…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Discovery.Peer> all = Discovery.scan(3000);
                final List<Discovery.Peer> pcs = new ArrayList<Discovery.Peer>();
                for (Discovery.Peer p : all) {
                    if (p.isPc()) {
                        pcs.add(p);
                    }
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        showPeers(pcs);
                    }
                });
            }
        }, "pc-scan").start();
    }

    private void showPeers(final List<Discovery.Peer> peers) {
        if (peers.isEmpty()) {
            setStatus("未连接", false);
            new AlertDialog.Builder(c())
                    .setTitle("没找到 PC 服务端")
                    .setMessage("先在 PC 端工具的「本机服务端」页点「启动服务端」，"
                            + "确认手机和 PC 在同一个 WiFi，并允许 Windows 防火墙访问。\n\n"
                            + "也可以点「设置」手工填 PC 地址。")
                    .setPositiveButton("知道了", null)
                    .show();
            refreshLog();
            return;
        }
        final CharSequence[] items = new CharSequence[peers.size()];
        for (int i = 0; i < peers.size(); i++) {
            Discovery.Peer p = peers.get(i);
            items[i] = p.text() + "  ·  接收端口 " + (p.httpPort > 0 ? p.httpPort : "未开");
        }
        new AlertDialog.Builder(c())
                .setTitle("选择 PC 服务端")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Discovery.Peer p = peers.get(which);
                        applyPeer(p);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyPeer(Discovery.Peer p) {
        host.setText(p.ip);
        iperfPort.setText(String.valueOf(p.port));
        serverLabel = p.label;
        serverVersion = p.version;
        if (p.httpPort > 0) {
            httpPort.setText(String.valueOf(p.httpPort));
        }
        saveSettings();
        updateSummary();
        renderIdle();
        log.add("OK 已选中 " + p.text());
        refreshLog();
    }

    private void setBusy(boolean b, String text) {
        busy = b;
        setupBarView.setEnabled(!b);
        setupBarView.setAlpha(b ? 0.55f : 1f);
        actionRow.setVisibility(View.VISIBLE);
        resultCard.setVisibility(b ? GONE : resultCard.getVisibility());
        if (b) {
            primaryBtn.setText("停止");
            setStatus(text, false);
            dataBox.setVisibility(VISIBLE);
        } else {
            primaryBtn.setText(host.getText().toString().trim().isEmpty() ? "扫描 PC" : "开始测试");
            setStatus(text, true);
        }
        secondaryBtn.setText("设置");
        nextHint.setText(b ? "→ 正在跑下载/上传，曲线实时更新"
                : (host.getText().toString().trim().isEmpty()
                ? "→ 下一步：扫描 PC 服务端，或打开设置手工填地址"
                : "→ 下一步：点「开始测试」（下载 + 上传各跑满时长）"));
    }

    private void renderIdle() {
        dataBox.setVisibility(GONE);
        actionRow.setVisibility(VISIBLE);
        primaryBtn.setText(host.getText().toString().trim().isEmpty() ? "扫描 PC" : "开始测试");
        pcLine.setText(host.getText().toString().trim().isEmpty()
                ? "PC 服务端：未选择"
                : "PC 服务端：" + host.getText().toString().trim() + ":" + Ui.num(iperfPort, 5201)
                + (serverVersion.isEmpty() ? "" : "  " + serverVersion));
        phaseLine.setText("空闲 ping → 下载 → 上传；每方向跑满设定时长");
        setStatus(host.getText().toString().trim().isEmpty() ? "未连接" : "已找到 PC", false);
        nextHint.setText(host.getText().toString().trim().isEmpty()
                ? "→ 下一步：扫描 PC 服务端，或打开设置手工填地址"
                : "→ 下一步：点「开始测试」（下载 + 上传各跑满时长）");
    }

    private void setStatus(String text, boolean done) {
        status.setText(text);
        status.setTextColor(done ? Ui.GREEN : Ui.BLUE);
        status.setBackground(Ui.round(c(), done ? Ui.SUCCESS_BG : Ui.PILL_BLUE_BG, 0, 9f));
    }

    private void shareLast() {
        if (lastShareText == null || lastShareText.isEmpty()) {
            log.add("!! 还没有可分享的结果");
            refreshLog();
            return;
        }
        Share.send(c(), lastShareText);
    }

    private void exportCsv() {
        if (lastBundle == null) {
            log.add("!! 还没有可导出的结果");
            refreshLog();
            return;
        }
        CsvExport.shareBundle(c(), labelInput.getText().toString().trim(),
                lastBundle.startedAt, lastBundle);
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
                        Prefs.putStr(c(), "pc_label", s);
                        if (!lastHistoryId.isEmpty()) {
                            History.updateLabel(c(), lastHistoryId, s);
                        }
                        resultCard.setLabel(s);
                        updateSummary();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 设置

    private void showSetup() {
        if (setupDialog == null) {
            buildSetup();
        }
        if (setupDialog == null) {
            return;
        }
        if (setupSheetView != null) {
            setupSheetView.setTranslationY(0);
        }
        updateSummary();
        try {
            setupDialog.show();
        } catch (Exception ignored) {
        }
    }

    private void buildSetup() {
        Context c = c();
        final LinearLayout sheet = Ui.column(c);
        sheet.setBackground(Ui.round(c, Ui.PAGE_BG, 0, 18f));
        setupSheetView = sheet;

        LinearLayout handleBox = Ui.column(c);
        handleBox.setGravity(Gravity.CENTER_HORIZONTAL);
        handleBox.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 6));
        View handle = new View(c);
        handle.setBackground(Ui.round(c, Ui.CHIP_LINE, 0, 2f));
        handleBox.addView(handle, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 4)));
        sheet.addView(handleBox, Ui.fill());
        final float[] startY = new float[1];
        View.OnTouchListener drag = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    startY[0] = e.getRawY();
                    return true;
                }
                if (a == android.view.MotionEvent.ACTION_MOVE) {
                    sheet.setTranslationY(Math.max(0f, e.getRawY() - startY[0]));
                    return true;
                }
                if (a == android.view.MotionEvent.ACTION_UP
                        || a == android.view.MotionEvent.ACTION_CANCEL) {
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
        head.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 8));
        TextView t = Ui.text(c, "连PC 设置", 17f, Ui.INK, true);
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

        LinearLayout addr = Ui.card(c, "PC 服务端");
        LinearLayout hr = Ui.row(c);
        LinearLayout hf = Ui.field(c, "PC 地址", host, 1f);
        Ui.margin(c, hf, 0, 0, 8, 0);
        hr.addView(hf);
        hr.addView(Ui.narrowField(c, "iperf 端口", iperfPort, 82));
        addr.addView(hr, Ui.fill());
        LinearLayout hr2 = Ui.row(c);
        Ui.margin(c, hr2, 0, 8, 0, 0);
        hr2.addView(Ui.narrowField(c, "结果回传端口", httpPort, 110));
        Button scan = Ui.ghost(c, "扫描 PC", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanPc();
            }
        });
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scLp.leftMargin = Ui.dp(c, 8);
        hr2.addView(scan, scLp);
        addr.addView(hr2, Ui.fill());
        addr.addView(Ui.hint(c, "PC 工具「本机服务端」页会显示地址；手机在同一网段才能扫描到。"), Ui.fill());
        body.addView(addr);

        LinearLayout param = Ui.card(c, "测试参数");
        LinearLayout row = Ui.row(c);
        LinearLayout df = Ui.field(c, "每方向时长(秒)", durationInput, 1f);
        LinearLayout sf = Ui.field(c, "并发流", streamsInput, 1f);
        LinearLayout ivf = Ui.field(c, "采样(秒)", intervalInput, 1f);
        Ui.margin(c, df, 0, 0, 8, 0);
        Ui.margin(c, sf, 0, 0, 8, 0);
        row.addView(df);
        row.addView(sf);
        row.addView(ivf);
        param.addView(row, Ui.fill());
        LinearLayout presetRow = Ui.row(c);
        Ui.margin(c, presetRow, 0, 8, 0, 0);
        durationChip = Ui.chip(c, durationLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleDuration();
            }
        });
        presetRow.addView(durationChip);
        Ui.margin(c, durationChip, 0, 0, 8, 0);
        locationChip = Ui.chip(c, locationLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleLocation();
            }
        });
        presetRow.addView(locationChip);
        param.addView(presetRow, Ui.fill());
        LinearLayout locRow = Ui.row(c);
        Ui.margin(c, locRow, 0, 8, 0, 0);
        locRow.addView(Ui.field(c, "距离(米，可选)", distanceInput, 0.6f));
        LinearLayout lf = Ui.field(c, "标签", labelInput, 1f);
        Ui.margin(c, lf, 8, 0, 0, 0);
        locRow.addView(lf);
        param.addView(locRow, Ui.fill());
        param.addView(Ui.hint(c, "长稳 2/8/24 小时会自动把采样调到 5 秒；下载+上传各跑满，24h 约 48h。"), Ui.fill());
        body.addView(param);

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
                saveSettings();
                updateSummary();
            }
        });
    }

    private void cycleDuration() {
        int cur = Ui.num(durationInput, 60);
        int next = PRESETS[0];
        for (int i = 0; i < PRESETS.length; i++) {
            if (cur == PRESETS[i]) {
                next = PRESETS[(i + 1) % PRESETS.length];
                break;
            }
        }
        durationInput.setText(String.valueOf(next));
        if (next > 3600 && Ui.num(intervalInput, 1) == 1) {
            intervalInput.setText("5");
        }
        durationChip.setText(durationLabel());
        updateSummary();
    }

    private void cycleLocation() {
        if (location == null || location.isEmpty()) {
            location = "近点";
        } else if ("近点".equals(location)) {
            location = "中场";
        } else if ("中场".equals(location)) {
            location = "远点";
        } else {
            location = "";
        }
        locationChip.setText(location.isEmpty() ? "位置 未设置" : "位置 " + location);
        updateSummary();
    }

    private String durationLabel() {
        int d = Ui.num(durationInput, 60);
        if (d == 7200) {
            return "时长 2 小时";
        }
        if (d == 28800) {
            return "时长 8 小时";
        }
        if (d == 86400) {
            return "时长 24 小时";
        }
        return "时长 " + d + " 秒";
    }

    private String locationValue() {
        return location == null ? "" : location;
    }

    private String locationLabel() {
        return location == null || location.isEmpty() ? "位置 未设置" : "位置 " + location;
    }

    private void updateSummary() {
        String h = host.getText().toString().trim();
        int d = Ui.num(durationInput, 60);
        String loc = locationValue();
        String label = labelInput.getText().toString().trim();
        String s = (h.isEmpty() ? "未选 PC" : h)
                + " · 每方向 " + d + " 秒 · " + Ui.num(streamsInput, 4) + " 流"
                + (loc.isEmpty() ? "" : " · " + loc)
                + (label.isEmpty() ? "" : " · " + label);
        setBarText(setupSummary, "⚙ 设置 · " + s);
        if (durationChip != null) {
            durationChip.setText(durationLabel());
        }
        if (locationChip != null) {
            locationChip.setText(loc.isEmpty() ? "位置 未设置" : "位置 " + loc);
        }
        if (!busy && resultCard.getVisibility() != VISIBLE) {
            pcLine.setText(h.isEmpty() ? "PC 服务端：未选择"
                    : "PC 服务端：" + h + ":" + Ui.num(iperfPort, 5201)
                    + (serverVersion.isEmpty() ? "" : "  " + serverVersion));
        }
    }

    private void saveSettings() {
        Prefs.putStr(c(), "pc_single_host", host.getText().toString().trim());
        Prefs.putInt(c(), "pc_iperf_port", Ui.num(iperfPort, 5201));
        Prefs.putInt(c(), "pc_http_port", Ui.num(httpPort, 8737));
        Prefs.putInt(c(), "pc_duration", Ui.num(durationInput, 60));
        Prefs.putInt(c(), "pc_streams", Ui.num(streamsInput, 4));
        Prefs.putInt(c(), "pc_interval", Ui.num(intervalInput, 1));
        Prefs.putInt(c(), "pc_distance", Ui.num(distanceInput, -1));
        Prefs.putStr(c(), "pc_label", labelInput.getText().toString().trim());
        String loc = locationValue();
        Prefs.putStr(c(), "pc_location", loc);
    }

    private Context c() {
        return getContext();
    }

    private void refreshLog() {
        String s = log.last();
        setBarText(lastLog, "日志 · " + (s == null || s.isEmpty() ? "等待开始" : s));
    }

    private LinearLayout bar(Context c, TextView[] outText, String text,
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

    // ---------------------------------------------------------------- 回调

    @Override
    public void onPhase(final String phase) {
        post(new Runnable() {
            @Override
            public void run() {
                liveValue.setText("--");
                if ("ping".equals(phase)) {
                    liveRole.setText("空闲探测");
                    phaseLine.setText("空闲 ping …");
                } else if ("down".equals(phase)) {
                    liveRole.setText("下载速率（PC → 手机）");
                    phaseLine.setText("下载中（PC → 手机）");
                    chart.reset(0);
                } else {
                    liveRole.setText("上传速率（手机 → PC）");
                    phaseLine.setText("上传中（手机 → PC）");
                    chart.reset(0);
                }
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
                if ("XX".equals(tag)) {
                    setStatus("出错，看日志", false);
                }
            }
        });
    }

    @Override
    public void onRecord(final Record rec, final boolean down) {
        post(new Runnable() {
            @Override
            public void run() {
                chart.add(rec);
                float v = chart.lastValue();
                liveValue.setText(v >= 0 ? Stats.fmtMb(v) : "--");
            }
        });
    }

    @Override
    public void onDone(final Bundle bundle, final Stats down, final Stats up,
                       final PcPing.Result ping, final String note) {
        post(new Runnable() {
            @Override
            public void run() {
                chart.finish();
                lastBundle = bundle;
                lastDown = down;
                lastUp = up;
                lastPing = ping;
                busy = false;
                setupBarView.setEnabled(true);
                setupBarView.setAlpha(1f);
                boolean has = bundle != null && (down != null || up != null);
                if (!has) {
                    dataBox.setVisibility(GONE);
                    resultCard.hide();
                    actionRow.setVisibility(VISIBLE);
                    renderIdle();
                    setStatus("失败", false);
                    log.add("!! 本轮没有产出有效结果，看上面的日志");
                    refreshLog();
                    notifyStatus(false, false);
                    return;
                }
                dataBox.setVisibility(VISIBLE);
                actionRow.setVisibility(GONE);
                resultCard.show(bundle, down, up, ping,
                        labelInput.getText().toString().trim());
                lastShareText = shareText(bundle, down, up, ping);
                lastHistoryId = session.lastHistoryId();
                setStatus("完成", true);
                nextHint.setText("→ 完成。可再测一次、分享或导出 CSV；报告可在 PC 端生成。");
                if (note != null && !note.isEmpty()) {
                    log.add("-- " + note);
                }
                refreshLog();
                notifyStatus(false, false);
            }
        });
    }

    private String shareText(Bundle b, Stats down, Stats up, PcPing.Result ping) {
        StringBuilder sb = new StringBuilder("WiFiTput 连PC 测试结果\n");
        if (b != null) {
            String loc = b.locationPreset == null ? "" : b.locationPreset;
            sb.append((loc.isEmpty() ? "未设置位置" : loc)
                    + (b.distanceM >= 0 ? " " + b.distanceM + "m" : "")
                    + " · 双向 · 每方向 " + b.duration + " 秒 · " + b.streams + " 流\n");
        }
        sb.append("下载 ").append(down == null ? "--" : Stats.fmtMb(PcResultCard.downMb(down)))
                .append(" Mb/s   上传 ")
                .append(up == null ? "--" : Stats.fmtMb(PcResultCard.upMb(up)))
                .append(" Mb/s\n");
        if (ping != null) {
            sb.append("ping 丢包 ").append(PcPing.fmt(ping.lossPct, 1))
                    .append("%   闲置 ping ").append(PcPing.fmt(ping.avgMs, 1))
                    .append(" ms（").append(ping.method).append("）\n");
        }
        if (b != null) {
            sb.append("工具 ").append(b.toolClient).append(" / PC ").append(b.toolServer)
                    .append("\n");
            if (b.telemetrySummary != null) {
                int disc = b.telemetrySummary.optInt("disconnects", 0);
                double cpu = b.telemetrySummary.optDouble("cpu_avg", -1);
                double rssi = b.telemetrySummary.optDouble("rssi_avg", -1);
                sb.append("断流 ").append(disc).append(" 次");
                if (cpu >= 0) {
                    sb.append("   CPU 均值 ").append(Stats.fmtPct(cpu)).append("%");
                }
                if (rssi > -200) {
                    sb.append("   RSSI 均值 ").append(Math.round(rssi)).append(" dBm");
                }
                sb.append('\n');
            }
        }
        sb.append("（WiFiTput Android · 连PC）");
        return sb.toString();
    }

    // ---------------------------------------------------------------- PanelHost

    public void setStatusSink(PanelHost.StatusSink s) {
        sink = s;
    }

    private void notifyStatus(boolean active, boolean clientRun) {
        if (sink != null) {
            sink.onStatus(this, active, clientRun);
        }
    }

    public boolean isBusy() {
        return busy;
    }

    public boolean isClientRun() {
        return busy;
    }

    public long runStartMs() {
        return runStartMs;
    }

    public String modeTitle() {
        return "连PC";
    }

    public void stopAll() {
        session.stop();
        saveSettings();
    }

    public void refreshHotspot() {
        // 连PC 不涉及本机热点
    }

    public void persistSettings() {
        saveSettings();
    }
}
