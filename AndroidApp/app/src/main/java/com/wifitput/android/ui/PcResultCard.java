package com.wifitput.android.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Bundle;
import com.wifitput.android.core.PcPing;
import com.wifitput.android.core.Stats;

import org.json.JSONArray;
import org.json.JSONObject;

/** 连PC 的结果卡：下载/上传 + ping 四项主指标 + 标准报告摘要。 */
class PcResultCard extends LinearLayout {

    interface Actions {
        void onAgain();

        void onShare();

        void onCsv();

        void onLabel();
    }

    private final TextView title;
    private final TextView labelBtn;
    private final TextView subtitle;
    private final TextView downValue;
    private final TextView upValue;
    private final TextView pingLoss;
    private final TextView pingMs;
    private final TextView retr;
    private final TextView stalls;
    private final TextView cpu;
    private final TextView rssi;
    private final TextView verdict;
    private final TextView standard;
    private final Button again;
    private final Button share;
    private final Button csv;

    private Actions actions;

    PcResultCard(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(Ui.round(c, Ui.RESULT_BG, Ui.CARD_LINE, 12f));
        setPadding(Ui.dp(c, 12), Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
        setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);

        LinearLayout head = Ui.row(c);
        title = Ui.text(c, "连PC 结果", 14f, Ui.INK, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelBtn = Ui.text(c, "加标签", 12f, Ui.BLUE, true);
        labelBtn.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), 0, Ui.dp(c, 2));
        labelBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onLabel();
                }
            }
        });
        head.addView(labelBtn);
        addView(head, Ui.fill());

        subtitle = Ui.text(c, "", 11f, Ui.MUTED, false);
        subtitle.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 8));
        addView(subtitle, Ui.fill());

        LinearLayout big = Ui.row(c);
        LinearLayout dl = bigCell(c, "下载（PC → 手机）");
        downValue = (TextView) dl.getTag();
        big.addView(dl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout ul = bigCell(c, "上传（手机 → PC）");
        upValue = (TextView) ul.getTag();
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ulp.leftMargin = Ui.dp(c, 10);
        big.addView(ul, ulp);
        addView(big, Ui.fill());

        LinearLayout small = Ui.row(c);
        pingLoss = smallCell(c, small, "ping 丢包");
        pingMs = smallCell(c, small, "闲置 ping");
        addView(small, Ui.fill());

        LinearLayout metrics = Ui.row(c);
        retr = metric(c, metrics, "重传(下/上)");
        stalls = metric(c, metrics, "断流");
        cpu = metric(c, metrics, "CPU");
        rssi = metric(c, metrics, "RSSI");
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(c, 9);
        addView(metrics, mlp);

        verdict = Ui.text(c, "", 12f, Ui.INK, false);
        verdict.setLineSpacing(Ui.dp(c, 1), 1f);
        verdict.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = Ui.dp(c, 10);
        addView(verdict, vlp);

        standard = Ui.text(c, "", 11f, Ui.MUTED, false);
        standard.setLineSpacing(Ui.dp(c, 1), 1f);
        standard.setPadding(0, Ui.dp(c, 8), 0, 0);
        addView(standard, Ui.fill());

        LinearLayout row1 = Ui.row(c);
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1.topMargin = Ui.dp(c, 11);
        again = Ui.primary(c, "再测一次", new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onAgain();
                }
            }
        });
        share = Ui.ghost(c, "分享摘要", new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onShare();
                }
            }
        });
        row1.addView(again, Ui.w(1.1f));
        Ui.margin(c, again, 0, 0, 7, 0);
        row1.addView(share, Ui.w(1f));
        addView(row1, r1);

        LinearLayout row2 = Ui.row(c);
        csv = Ui.ghost(c, "导出 CSV（下载+上传）", new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onCsv();
                }
            }
        });
        row2.addView(csv, Ui.w(1f));
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2.topMargin = Ui.dp(c, 7);
        addView(row2, r2);
    }

    void setActions(Actions a) {
        actions = a;
    }

    void show(Bundle b, Stats down, Stats up, PcPing.Result ping, String label) {
        if (b == null || (down == null && up == null)) {
            hide();
            return;
        }
        String name = label == null ? "" : label.trim();
        title.setText(name.isEmpty() ? "连PC 结果" : name);
        labelBtn.setText(name.isEmpty() ? "加标签" : "改标签");

        String time = b.startedAt != null && b.startedAt.length() >= 19
                ? b.startedAt.substring(11) : "";
        String loc = b.locationPreset == null ? "" : b.locationPreset;
        subtitle.setText((time.isEmpty() ? "" : time + " · ")
                + "双向 · 每方向 " + b.duration + " 秒 · " + b.streams + " 流"
                + " · 采样 " + b.interval + " 秒"
                + (loc.isEmpty() ? "" : " · " + loc
                + (b.distanceM >= 0 ? " " + b.distanceM + "m" : "")));

        double dm = downMb(down);
        double um = upMb(up);
        downValue.setText(dm >= 0 ? Stats.fmtMb(dm) : "--");
        upValue.setText(um >= 0 ? Stats.fmtMb(um) : "--");

        pingLoss.setText(ping == null ? "--" : PcPing.fmt(ping.lossPct, 1) + "%");
        pingMs.setText(ping == null ? "--" : PcPing.fmt(ping.avgMs, 1) + " ms");

        int rd = down == null ? -1 : down.retr;
        int ru = up == null ? -1 : up.retr;
        retr.setText(retrText(rd, ru));

        JSONObject ts = b.telemetrySummary;
        int disc = ts == null ? -1 : ts.optInt("disconnects", -1);
        stalls.setText((disc >= 0 ? String.valueOf(disc) : "--")
                + stallSuffix(down, up));
        double cpuV = ts == null ? -1 : ts.optDouble("cpu_avg", -1);
        cpu.setText(cpuV >= 0 ? Stats.fmtPct(cpuV) + "%" : "--");
        double rssiV = ts == null ? -1 : ts.optDouble("rssi_avg", -1);
        rssi.setText(rssiV != 0 && rssiV > -200 ? Math.round(rssiV) + " dBm" : "--");

        int level = level(down, up, ping);
        verdict.setText(verdictText(down, up, ping, ts));
        verdict.setBackground(Ui.round(getContext(),
                level == 2 ? Ui.DANGER_BG : level == 1 ? Ui.WARN_BG : Ui.SUCCESS_BG, 0, 9f));
        verdict.setTextColor(level == 2 ? Ui.RED : level == 1 ? Ui.AMBER : Ui.GREEN);

        standard.setText(standardText(b, down, up));
        setVisibility(VISIBLE);
        requestLayout();
        post(new Runnable() {
            @Override
            public void run() {
                requestRectangleOnScreen(new android.graphics.Rect(
                        0, 0, getWidth(), getHeight()), true);
            }
        });
    }

    void setLabel(String label) {
        String name = label == null ? "" : label.trim();
        title.setText(name.isEmpty() ? "连PC 结果" : name);
        labelBtn.setText(name.isEmpty() ? "加标签" : "改标签");
    }

    void hide() {
        setVisibility(GONE);
    }

    // ---------------------------------------------------------------- 取值

    static double downMb(Stats s) {
        if (s == null) {
            return -1;
        }
        return s.receiverMb >= 0 ? s.receiverMb : s.avgMb;
    }

    static double upMb(Stats s) {
        if (s == null) {
            return -1;
        }
        return s.senderMb >= 0 ? s.senderMb : s.avgMb;
    }

    private static String retrText(int d, int u) {
        String a = d >= 0 ? String.valueOf(d) : "--";
        String b = u >= 0 ? String.valueOf(u) : "--";
        return a + "/" + b;
    }

    private static String stallSuffix(Stats down, Stats up) {
        int n = 0;
        if (down != null) {
            n += down.stalls;
        }
        if (up != null) {
            n += up.stalls;
        }
        return n > 0 ? "（" + n + "秒0速）" : "";
    }

    private static int level(Stats down, Stats up, PcPing.Result ping) {
        if (ping != null && ping.lossPct >= 5) {
            return 2;
        }
        if (down != null && down.stalls > 0 || up != null && up.stalls > 0) {
            return 1;
        }
        if (down != null && down.mainMb() > 0 && down.mainMb() < 10) {
            return 2;
        }
        if (up != null && up.senderMb > 0 && up.senderMb < 10) {
            return 2;
        }
        return 0;
    }

    private static String verdictText(Stats down, Stats up, PcPing.Result ping, JSONObject ts) {
        StringBuilder sb = new StringBuilder();
        sb.append("下载 ").append(down != null ? Stats.fmtMb(downMb(down)) : "--")
                .append(" Mb/s，上传 ").append(up != null ? Stats.fmtMb(upMb(up)) : "--")
                .append(" Mb/s");
        if (ping != null) {
            sb.append("；ping 丢包 ").append(PcPing.fmt(ping.lossPct, 1)).append("%");
        }
        int s = 0;
        if (down != null) {
            s += down.stalls;
        }
        if (up != null) {
            s += up.stalls;
        }
        if (s > 0) {
            sb.append("，有 ").append(s).append(" 秒速率为 0");
        }
        int disc = ts == null ? -1 : ts.optInt("disconnects", -1);
        if (disc > 0) {
            sb.append("，网络断开 ").append(disc).append(" 次");
        }
        sb.append("。");
        return sb.toString();
    }

    private String standardText(Bundle b, Stats down, Stats up) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具  ").append(trimVer(b.toolClient)).append(" / PC ")
                .append(trimVer(b.toolServer)).append("\n");
        sb.append("参数  双向 · TCP 不限速 · 每方向 ").append(b.duration)
                .append(" 秒 · ").append(b.streams).append(" 流 · 采样 ")
                .append(b.interval).append(" 秒");
        if (down != null && down.linkMbps > 0) {
            sb.append("\n协商  ").append(Stats.fmtMb(down.linkMbps)).append(" Mbps · ")
                    .append("下载占 ").append(Math.round(downMb(down) * 100 / down.linkMbps)).append("%");
            if (up != null && upMb(up) > 0) {
                sb.append(" · 上传占 ").append(Math.round(upMb(up) * 100 / down.linkMbps)).append("%");
            }
        }
        String radio = radioText(b);
        if (!radio.isEmpty()) {
            sb.append("\n链路  ").append(radio);
        }
        sb.append("\nMCS/SNR  不可用（Android 公共 API 不提供）");
        return sb.toString();
    }

    private static String radioText(Bundle b) {
        try {
            JSONArray ds = b.devices;
            if (ds == null || ds.length() == 0) {
                return "";
            }
            JSONObject d = ds.optJSONObject(0);
            JSONObject w = d == null ? null : d.optJSONObject("wifi");
            if (w == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            String band = w.optString("band", "");
            int ch = w.optInt("channel", 0);
            int freq = w.optInt("freq_mhz", 0);
            int rssi = w.optInt("rssi", 0);
            if (!band.isEmpty()) {
                sb.append(band);
            }
            if (ch > 0) {
                sb.append(sb.length() > 0 ? " " : "").append("ch").append(ch);
            }
            if (freq > 0) {
                sb.append(sb.length() > 0 ? " " : "").append(freq).append("MHz");
            }
            if (rssi != 0) {
                sb.append(sb.length() > 0 ? " · " : "").append(rssi).append("dBm");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimVer(String v) {
        if (v == null || v.isEmpty()) {
            return "iperf3";
        }
        // 只留 “iperf 3.22”，去掉 cJSON 等括号内容
        int p = v.indexOf('(');
        return p > 0 ? v.substring(0, p).trim() : v;
    }

    private LinearLayout bigCell(Context c, String label) {
        LinearLayout col = Ui.column(c);
        TextView l = Ui.text(c, label, 10.5f, Ui.MUTED, false);
        col.addView(l, Ui.fill());
        LinearLayout row = Ui.row(c);
        TextView v = Ui.text(c, "--", 26f, Ui.INK, true);
        row.addView(v);
        TextView u = Ui.text(c, "Mb/s", 10.5f, Ui.MUTED, false);
        u.setPadding(Ui.dp(c, 4), 0, 0, Ui.dp(c, 2));
        u.setGravity(Gravity.BOTTOM);
        row.addView(u);
        col.addView(row, Ui.fill());
        col.setTag(v);
        return col;
    }

    private TextView smallCell(Context c, LinearLayout parent, String label) {
        LinearLayout col = Ui.column(c);
        col.setPadding(0, Ui.dp(c, 8), 0, 0);
        col.addView(Ui.text(c, label, 10.5f, Ui.MUTED, false), Ui.fill());
        TextView v = Ui.text(c, "--", 15f, Ui.INK, true);
        v.setPadding(0, Ui.dp(c, 1), 0, 0);
        col.addView(v, Ui.fill());
        parent.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private TextView metric(Context c, LinearLayout parent, String label) {
        LinearLayout col = Ui.column(c);
        col.addView(Ui.text(c, label, 9.5f, Ui.MUTED, false), Ui.fill());
        TextView v = Ui.text(c, "--", 12f, Ui.INK, true);
        v.setPadding(0, Ui.dp(c, 2), 0, 0);
        col.addView(v, Ui.fill());
        parent.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }
}
