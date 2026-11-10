package com.wifitput.android.ui;

import android.content.Context;
import android.content.Intent;

import com.wifitput.android.core.Stats;

/** 把一次结果拼成可直接发出去的文本，并拉起系统分享面板。 */
final class Share {

    private Share() {
    }

    static String text(String title, String subtitle, Stats st, String extra) {
        if (st == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("WiFiTput 测试结果\n");
        if (title != null && !title.trim().isEmpty()) {
            sb.append(title.trim()).append('\n');
        }
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            sb.append(subtitle.trim()).append('\n');
        }
        sb.append('\n');
        sb.append(st.receiverMb >= 0 ? "接收端 " : "平均 ")
                .append(Stats.fmtMb(st.mainMb())).append(" Mb/s");
        if (st.senderMb >= 0 && st.receiverMb >= 0) {
            sb.append("（发送端 ").append(Stats.fmtMb(st.senderMb)).append(" Mb/s）");
        }
        sb.append('\n');
        sb.append("平均 ").append(Stats.fmtMb(st.avgMb))
                .append(" · 最低 ").append(Stats.fmtMb(st.minMb))
                .append(" · 最高 ").append(Stats.fmtMb(st.maxMb));
        if (st.retr >= 0) {
            sb.append(" · 重传 ").append(st.retr);
        }
        if (st.lostPct >= 0) {
            sb.append(" · 丢包 ").append(Stats.fmtPct(st.lostPct)).append("%");
        }
        if (st.jitter >= 0) {
            sb.append(" · 抖动 ").append(Stats.fmtPct(st.jitter)).append("ms");
        }
        sb.append('\n').append(st.verdict(st.linkMbps));
        if (extra != null && !extra.trim().isEmpty()) {
            sb.append('\n').append(extra.trim());
        }
        sb.append("\n（WiFiTput Android）");
        return sb.toString();
    }

    static void send(Context ctx, String text) {
        if (ctx == null || text == null || text.isEmpty()) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(send, "分享测试结果");
        if (!(ctx instanceof android.app.Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            ctx.startActivity(chooser);
        } catch (Exception ignored) {
            // 没有可分享的应用时不要因为分享把页面崩掉
        }
    }
}
