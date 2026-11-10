package com.wifitput.android.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 全屏看一次运行的完整日志：只读、可选中、可复制。 */
public final class LogDialog {

    private static final String EMPTY = "（这次运行没有日志）";

    private LogDialog() {
    }

    /** 弹出日志查看框。text 可能是几十 KB，TextView 一次 setText 没问题，不做别的加工。 */
    public static void show(final Context c, String title, final String text) {
        if (c == null) {
            return;
        }
        final String body = (text == null || text.length() == 0) ? EMPTY : text;

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);

        TextView view = Ui.text(c, body, 12f, Ui.INK, false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        view.setBackgroundColor(Ui.LOG_BG);
        scroll.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 高度写死在容器上，弹窗本身按内容收缩；小屏按屏幕高度收一下，别顶出去。
        LinearLayout box = Ui.column(c);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, logHeight(c)));

        AlertDialog d = new AlertDialog.Builder(c)
                .setTitle(title == null || title.length() == 0 ? "日志" : title)
                .setView(box)
                .setNegativeButton("关闭", null)
                .setNeutralButton("复制", null)
                .create();

        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                AlertDialog a = (AlertDialog) dialog;
                // 默认弹窗左右留白很多，日志一行看不全，撑到接近满宽
                Window w = a.getWindow();
                if (w != null) {
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                }
                // AlertDialog 的按钮点完就自动关窗。这里把复制按钮的监听整个换掉，
                // 复制完弹窗留着，用户还能接着看日志。
                Button cp = a.getButton(DialogInterface.BUTTON_NEUTRAL);
                if (cp != null) {
                    cp.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            copy(c, text);
                        }
                    });
                }
            }
        });
        d.show();
    }

    /** 日志框高度：常规 460dp，屏幕矮的时候收到 72%，避免日志区被挤没。 */
    private static int logHeight(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int cap = (int) (dm.heightPixels * 0.72f);
        return Math.max(Ui.dp(c, 180), Math.min(Ui.dp(c, 460), cap));
    }

    /** 复制文本到剪贴板并提示。HistoryActivity 的「复制日志」按钮也走这里，避免两处各写一份。 */
    static void copy(Context c, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("log", text == null ? "" : text));
            Toast.makeText(c, "日志已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // 个别 ROM 会限制剪贴板，失败也不能把界面弄崩
            Toast.makeText(c, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }
}
