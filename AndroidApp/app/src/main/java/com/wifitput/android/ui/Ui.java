package com.wifitput.android.ui;

import android.content.Context;
import android.content.res.Configuration;
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

    /** true = 深色模式；由各 Activity 的 onCreate 调 init() 设置。 */
    static boolean dark;

    // 这些不是 final：跟随系统的浅色/深色切换，Activity 重建后重新赋值
    static int BLUE = 0xFF1565C0;
    static int BLUE_DARK = 0xFF0D47A1;
    static int GREY = 0xFF5F6B7A;
    static int MUTED = 0xFF6B7480;
    static int GREEN = 0xFF2E7D32;
    static int RED = 0xFFC62828;
    static int AMBER = 0xFFB26A00;
    static int INK = 0xFF1F242B;

    static int PAGE_BG = 0xFFF4F6F9;
    static int CARD = 0xFFFFFFFF;
    static int CARD_LINE = 0xFFE7EBF1;
    static int FIELD_BG = 0xFFF5F7FA;
    static int CHIP_BG = 0xFFEFF3F8;
    static int CHIP_LINE = 0xFFDCE3ED;

    static int SUCCESS_BG = 0xFFE7F4E9;
    static int WARN_BG = 0xFFFFF6E5;
    static int DANGER_BG = 0xFFFBEAEA;
    static int PILL_BLUE_BG = 0xFFE8F0FB;
    static int LOG_BG = 0xFFF7F9FC;
    static int RESULT_BG = 0xFFF7F9FC;
    static int GRID = 0xFFEDF0F4;
    static int AXIS = 0xFFD9DEE5;

    static int HEADER_START = 0xFF1E7BD6;
    static int HEADER_END = 0xFF0D47A1;
    static int HEADER_SUB = 0xFFD6E6FA;
    static int TAB_INACTIVE = 0xFFDCE9F8;
    static int TAB_ON_BG = 0xFFFFFFFF;
    static int TAB_ON_INK = 0xFF1565C0;
    /** 实心主按钮的底色（和文字色的区分：深色下用深一点的蓝保证白字对比度）。 */
    static int PRIMARY_FILL = 0xFF1565C0;
    static int PRIMARY_DOWN = 0xFF0D47A1;
    static int SEG_ON_BG = 0xFFFFFFFF;

    private Ui() {
    }

    /** 每个 Activity 建界面之前调用一次；跟随系统浅色/深色。 */
    static void init(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = mode == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            BLUE = 0xFF5AA2F0;
            BLUE_DARK = 0xFF1E7BD6;
            GREY = 0xFFA7B0BC;
            MUTED = 0xFF8A929C;
            GREEN = 0xFF7ED08A;
            RED = 0xFFF08A8A;
            AMBER = 0xFFF0B45E;
            INK = 0xFFE8EAED;

            PAGE_BG = 0xFF101318;
            CARD = 0xFF181C22;
            CARD_LINE = 0xFF2A313B;
            FIELD_BG = 0xFF1F242C;
            CHIP_BG = 0xFF232A33;
            CHIP_LINE = 0xFF333B46;

            SUCCESS_BG = 0xFF1B2C20;
            WARN_BG = 0xFF332713;
            DANGER_BG = 0xFF3A1E1E;
            PILL_BLUE_BG = 0xFF1B2A3D;
            LOG_BG = 0xFF14181E;
            RESULT_BG = 0xFF14181E;
            GRID = 0xFF232A33;
            AXIS = 0xFF333B46;

            HEADER_START = 0xFF1B5E9E;
            HEADER_END = 0xFF0B2B4F;
            HEADER_SUB = 0xFFC7D8EA;
            TAB_INACTIVE = 0xFF9FB6CF;
            TAB_ON_BG = 0xFFD6E6FA;
            TAB_ON_INK = 0xFF0B2B4F;
            PRIMARY_FILL = 0xFF1F6FC0;
            PRIMARY_DOWN = 0xFF145A9E;
            SEG_ON_BG = 0xFF2B3948;
        } else {
            BLUE = 0xFF1565C0;
            BLUE_DARK = 0xFF0D47A1;
            GREY = 0xFF5F6B7A;
            MUTED = 0xFF6B7480;
            GREEN = 0xFF2E7D32;
            RED = 0xFFC62828;
            AMBER = 0xFFB26A00;
            INK = 0xFF1F242B;

            PAGE_BG = 0xFFF4F6F9;
            CARD = 0xFFFFFFFF;
            CARD_LINE = 0xFFE7EBF1;
            FIELD_BG = 0xFFF5F7FA;
            CHIP_BG = 0xFFEFF3F8;
            CHIP_LINE = 0xFFDCE3ED;

            SUCCESS_BG = 0xFFE7F4E9;
            WARN_BG = 0xFFFFF6E5;
            DANGER_BG = 0xFFFBEAEA;
            PILL_BLUE_BG = 0xFFE8F0FB;
            LOG_BG = 0xFFF7F9FC;
            RESULT_BG = 0xFFF7F9FC;
            GRID = 0xFFEDF0F4;
            AXIS = 0xFFD9DEE5;

            HEADER_START = 0xFF1E7BD6;
            HEADER_END = 0xFF0D47A1;
            HEADER_SUB = 0xFFD6E6FA;
            TAB_INACTIVE = 0xFFDCE9F8;
            TAB_ON_BG = 0xFFFFFFFF;
            TAB_ON_INK = 0xFF1565C0;
            PRIMARY_FILL = 0xFF1565C0;
            PRIMARY_DOWN = 0xFF0D47A1;
            SEG_ON_BG = 0xFFFFFFFF;
        }
    }

    /** 状态栏保持顶栏深蓝、白字；导航栏跟页面底色，图标明暗跟着主题。 */
    static void applyBars(android.app.Activity a) {
        if (a == null) {
            return;
        }
        android.view.Window w = a.getWindow();
        w.setStatusBarColor(HEADER_START);
        w.setNavigationBarColor(PAGE_BG);
        int vis = w.getDecorView().getSystemUiVisibility();
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (dark) {
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        w.getDecorView().setSystemUiVisibility(vis);
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** 给主题色加透明度，用于曲线下的淡色面积。 */
    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
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
        TextView t = text(c, s, 11.5f, MUTED, false);
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
        box.setBackground(round(c, CARD, CARD_LINE, 14f));
        box.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 13));
        box.setElevation(dp(c, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        box.setLayoutParams(lp);
        if (title != null && title.length() > 0) {
            box.addView(cardTitle(c, title));
        }
        return box;
    }

    /** 顶栏渐变背景，比纯色更有层次。 */
    static GradientDrawable headerBg(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{HEADER_START, HEADER_END});
    }

    /** 把已有的自定义控件（热点面板、P2P 面板）套成和卡片一样的外观。 */
    static void cardify(Context c, View v) {
        v.setBackground(round(c, CARD, CARD_LINE, 14f));
        v.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 13));
    }

    /** 主按钮：蓝底白字。 */
    static Button primary(Context c, String s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(pressed(c, PRIMARY_FILL, PRIMARY_DOWN, 0, 12f));
        b.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        return b;
    }

    /** 次按钮：卡片底、蓝框蓝字。 */
    static Button ghost(Context c, String s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setTextColor(BLUE);
        b.setTextSize(14f);
        b.setBackground(pressed(c, CARD, PILL_BLUE_BG, CHIP_LINE, 12f));
        b.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        return b;
    }

    /** 浅底小胶囊，用于「协议 TCP」「方向 正向」这类开关。 */
    static Button chip(Context c, String s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setTextColor(INK);
        b.setTextSize(13.5f);
        b.setBackground(pressed(c, CHIP_BG, CHIP_LINE, CHIP_LINE, 12f));
        b.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
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
        e.setHintTextColor(MUTED);
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
        TextView t = text(c, name, 11.5f, MUTED, false);
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
