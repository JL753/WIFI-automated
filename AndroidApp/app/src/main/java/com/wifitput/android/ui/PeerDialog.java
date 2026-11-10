package com.wifitput.android.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Discovery;

import java.util.List;

/** 把扫描到的对端列出来，点一条就把地址回写到输入框。 */
public final class PeerDialog {

    private PeerDialog() {
    }

    /**
     * 弹出扫描结果；用户点一条就回写地址并关窗。
     * peers 为空时给排查提示，rescan 为 null 表示不提供「重新扫描」按钮。
     */
    public static void show(Context c, List<Discovery.Peer> peers, final AddrSink sink,
                            final Runnable rescan) {
        AlertDialog.Builder b = new AlertDialog.Builder(c);
        if (peers == null || peers.isEmpty()) {
            b.setTitle("没扫到对端");
            b.setView(emptyBody(c));
            b.setNegativeButton("手工填地址", null);
            if (rescan != null) {
                b.setPositiveButton("重新扫描", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        // 等这轮弹窗彻底关掉再开新的一轮，否则会叠两层对话框。
                        new Handler(Looper.getMainLooper()).postDelayed(rescan, 200);
                    }
                });
            }
            b.show();
            return;
        }
        b.setTitle("选择对端");
        b.setNegativeButton("取消", null);
        // 条目要能关掉自己所在的窗口，但窗口得等内容搭完才存在，先用一格数组占位。
        final AlertDialog[] host = new AlertDialog[1];
        b.setView(peerBody(c, peers, sink, host));
        host[0] = b.show();
    }

    private static LinearLayout emptyBody(Context c) {
        LinearLayout box = Ui.column(c);
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 4));
        box.addView(Ui.text(c, "先确认这三件事：", 13f, Ui.INK, false), Ui.fill());
        box.addView(tip(c, "1. 另一台手机已经点了「当服务端」，并且界面上显示「服务端已监听」"), Ui.fill());
        box.addView(tip(c, "2. 两台手机连的是同一个 WiFi / 同一个热点"), Ui.fill());
        box.addView(tip(c, "3. 不是连在路由器的访客网络上（访客网络会互相隔离）"), Ui.fill());
        return box;
    }

    private static TextView tip(Context c, String s) {
        TextView t = Ui.text(c, s, 12.5f, Ui.GREY, false);
        t.setPadding(0, 0, 0, Ui.dp(c, 6));
        return t;
    }

    private static LinearLayout peerBody(Context c, List<Discovery.Peer> peers,
                                         final AddrSink sink, final AlertDialog[] host) {
        LinearLayout box = Ui.column(c);
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 4));
        box.addView(Ui.hint(c, "两台手机要在同一个 WiFi / 同一个热点下才能互相看到。"), Ui.fill());
        for (int i = 0; i < peers.size(); i++) {
            final Discovery.Peer p = peers.get(i);
            LinearLayout card = Ui.row(c);
            card.setBackground(cardBackground(c));
            card.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = Ui.dp(c, 8);
            card.setLayoutParams(cardLp);

            LinearLayout left = Ui.column(c);
            String title = p.label == null || p.label.trim().isEmpty() ? p.ip : p.label;
            left.addView(Ui.text(c, title, 15f, Ui.INK, true), Ui.fill());
            left.addView(Ui.text(c, p.ip + ":" + p.port, 12.5f, Ui.GREY, false), Ui.fill());
            card.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView go = Ui.text(c, "填入", 12.5f, Ui.BLUE, true);
            LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            goLp.gravity = Gravity.END;
            card.addView(go, goLp);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sink.fill(p.ip, p.port);
                    if (host[0] != null) {
                        host[0].dismiss();
                    }
                }
            });
            box.addView(card, cardLp);
        }
        return box;
    }

    /** 按下变色靠状态列表，比再套一层触摸监听省事，也不会漏掉手指滑出去的复位。 */
    private static StateListDrawable cardBackground(Context c) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, cardDrawable(c, Ui.PILL_BLUE_BG));
        bg.addState(new int[0], cardDrawable(c, Ui.CARD));
        return bg;
    }

    private static GradientDrawable cardDrawable(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Ui.dp(c, 10));
        d.setStroke(1, Ui.CARD_LINE);
        return d;
    }
}