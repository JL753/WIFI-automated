package com.wifitput.android.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 纯代码搭界面用到的小工具：配色、圆角卡片、主次按钮、输入框。 */
final class Ui {

    static final int BLUE = 0xFF1565C0;
    static final int BLUE_DARK = 0xFF0D47A1;
    static final int GREY = 0xFF757575;
    static final int GREEN = 0xFF2E7D32;
    static final int RED = 0xFFC62828;
    static final int INK = 0xFF212121;

    static final int PAGE_BG = 0xFFF2F4F7;
    static final int CARD_LINE = 0xFFE4E8EE;
    static final int FIELD_BG = 0xFFF5F7FA;
    static final int CHIP_BG = 0xFFEFF2F7;
    static final int CHIP_LINE = 0xFFDCE3ED;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static GradientDrawable round(Context c, int fill, int line, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (line != 0) {
            d.setStroke(Math.max(1, dp(c, 1)), line);
        }
        return d;
    }

    /** 按下变色的圆角背景，用于可点的卡片行和按钮。 */
    static StateListDrawable pressed(Context c, int fill, int down, int line, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(c, down, line, radiusDp));
        s.addState(new int[0], round(c, fill, line, radiusDp));
        return s;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    static TextView hint(Context c, String s) {
        TextView t = text(c, s, 11.5f, GREY, false);
        t.setLineSpacing(dp(c, 1), 1f);
        t.setPadding(0, dp(c, 2), 0, dp(c, 4));
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s, 13f, INK, false);
        t.setPadding(0, 0, dp(c, 8), 0);
        return t;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 14f, BLUE, true);
        t.setPadding(0, dp(c, 14), 0, dp(c, 6));
        return t;
    }

    /** 卡片内的标题：不带上下外边距，靠卡片自己的内边距撑开。 */
    static TextView cardTitle(Context c, String s) {
        TextView t = text(c, s, 13f, BLUE, true);
        t.setPadding(0, 0, 0, dp(c, 8));
        return t;
    }

    /** 白色圆角卡片，返回容器，调用方往里塞内容。title 为 null 时不出标题。 */
    static LinearLayout card(Context c, String title) {
        LinearLayout box = column(c);
        box.setBackground(round(c, Color.WHITE, CARD_LINE, 12f));
        box.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 9);
        box.setLayoutParams(lp);
        if (title != null && title.length() > 0) {
            box.addView(cardTitle(c, title));
        }
        return box;
    }

    /** 把已有的自定义控件（热点面板、P2P 面板）套成和卡片一样的外观。 */
    static void cardify(Context c, View v) {
        v.setBackground(round(c, Color.WHITE, CARD_LINE, 12f));
        v.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 11));
    }

    /** 主按钮：蓝底白字。 */
    static Button primary(Context c, String s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(pressed(c, BLUE, BLUE_DARK, 0, 10f));
        b.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        b.setMinHeight(dp(c, 42));
        b.setMinimumHeight(dp(c, 42));
        return b;
    }

    /** 次按钮：白底蓝框蓝字。 */
    static Button ghost(Context c, String s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setTextColor(BLUE);
        b.setTextSize(14f);
        b.setBackground(pressed(c, Color.WHITE, 0xFFEDF3FB, CHIP_LINE, 10f));
        b.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        b.setMinHeight(dp(c, 42));
        b.setMinimumHeight(dp(c, 42));
        return b;
    }

    /** 浅底小胶囊，用于「协议 TCP」「方向 正向」这类开关。 */
    static Button chip(Context c, String s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setTextColor(INK);
        b.setTextSize(13.5f);
        b.setBackground(pressed(c, CHIP_BG, 0xFFE2E8F1, CHIP_LINE, 10f));
        b.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        b.setMinHeight(dp(c, 42));
        b.setMinimumHeight(dp(c, 42));
        return b;
    }

    static Button button(Context c, String s, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        return b;
    }

    static EditText input(Context c, String s, int widthDp) {
        EditText e = new EditText(c);
        e.setText(s);
        e.setTextSize(14f);
        e.setTextColor(INK);
        e.setHintTextColor(GREY);
        e.setSingleLine(true);
        e.setBackground(round(c, FIELD_BG, CHIP_LINE, 10f));
        e.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        e.setMinHeight(dp(c, 42));
        e.setMinimumHeight(dp(c, 42));
        if (widthDp > 0) {
            e.setLayoutParams(new LinearLayout.LayoutParams(dp(c, widthDp),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            e.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return e;
    }

    static EditText number(Context c, String s, int widthDp) {
        EditText e = input(c, s, widthDp);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** 小标签在上、控件在下的组合；weight > 0 时按比例分宽。 */
    static LinearLayout field(Context c, String name, View v, float weight) {
        LinearLayout col = column(c);
        TextView t = text(c, name, 11.5f, GREY, false);
        t.setPadding(dp(c, 2), 0, 0, dp(c, 3));
        col.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (weight > 0) {
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        } else {
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return col;
    }

    /** 固定宽度的组合，用于「端口」这种短字段。 */
    static LinearLayout narrowField(Context c, String name, View v, int widthDp) {
        LinearLayout col = field(c, name, v, 0f);
        col.setLayoutParams(new LinearLayout.LayoutParams(dp(c, widthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    static LinearLayout.LayoutParams w(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    static void margin(Context c, View v, float left, float top, float right, float bottom) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) p;
            lp.leftMargin = dp(c, left);
            lp.topMargin = dp(c, top);
            lp.rightMargin = dp(c, right);
            lp.bottomMargin = dp(c, bottom);
        }
    }

    /** 卡片在纵向列表里的标准外边距（底部留 12dp 间隔）。 */
    static LinearLayout.LayoutParams cardLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 9);
        return lp;
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static void gap(Context c, LinearLayout parent, float dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, dp)));
        parent.addView(v);
    }
}
