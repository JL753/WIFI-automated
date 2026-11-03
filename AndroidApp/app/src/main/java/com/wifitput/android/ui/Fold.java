package com.wifitput.android.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Prefs;

/**
 * 可收起/展开的卡片。标题行常驻并带「展开 / 收起」，内容折叠后整张卡只剩一行，
 * 折叠状态记在 Prefs 里，下次进来还是上次的样子。
 */
class Fold extends LinearLayout {

    private final String prefKey;
    private final TextView arrow;
    private final LinearLayout body;
    private boolean open;

    Fold(Context c, String titleText, String key, boolean defaultOpen) {
        super(c);
        prefKey = "fold_" + key;
        setOrientation(VERTICAL);
        setBackground(Ui.round(c, Color.WHITE, Ui.CARD_LINE, 12f));
        setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 9);
        setLayoutParams(lp);

        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(Ui.pressed(c, 0, 0xFFF4F7FB, 0, 8f));
        head.setPadding(0, Ui.dp(c, 1), 0, Ui.dp(c, 1));
        TextView title = Ui.text(c, titleText, 13f, Ui.BLUE, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        arrow = Ui.text(c, "", 11.5f, Ui.GREY, false);
        head.addView(title);
        head.addView(arrow);
        head.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setOpen(!open, true);
            }
        });
        addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body = Ui.column(c);
        body.setPadding(0, Ui.dp(c, 6), 0, 0);
        addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setOpen(Prefs.getBool(c, prefKey, defaultOpen), false);
    }

    LinearLayout body() {
        return body;
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean open, boolean save) {
        this.open = open;
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        arrow.setText(open ? "收起 ▴" : "展开 ▾");
        if (save) {
            Prefs.putBool(getContext(), prefKey, open);
        }
    }
}
