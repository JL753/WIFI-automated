package com.wifitput.android.ui;

import android.content.Context;
import android.text.Editable;
import android.text.Layout;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 逐秒滚动的日志框。行数有上限，长时间跑不会把内存吃光。 */
class LogBox extends ScrollView {

    private static final int MAX_LINES = 300;
    /** 四流时每秒回四条记录，逐条重建整串再 setText 会把主线程拖住，攒一小会儿一次追加。 */
    private static final long FLUSH_DELAY_MS = 120;

    private final TextView view;
    private final List<String> lines = new ArrayList<String>();
    private final StringBuilder pending = new StringBuilder();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final int bottomTolerance;
    private int shownLines;
    private boolean flushScheduled;

    LogBox(Context c) {
        super(c);
        bottomTolerance = Ui.dp(c, 8);
        view = Ui.text(c, "", 12f, Ui.INK, false);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setMovementMethod(new ScrollingMovementMethod());
        view.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
        view.setBackgroundColor(0xFFF5F5F5);
        setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 230)));
        addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    void add(String msg) {
        if (msg == null) {
            return;
        }
        String line = clock.format(new Date()) + " | " + msg;
        lines.add(line);
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
        pending.append(line).append('\n');
        if (!flushScheduled) {
            flushScheduled = true;
            postDelayed(flush, FLUSH_DELAY_MS);
        }
    }

    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            if (pending.length() == 0) {
                return;
            }
            boolean stick = atBottom();
            String chunk = pending.toString();
            pending.setLength(0);
            view.append(chunk);
            shownLines += countLines(chunk);
            trimToLimit();
            if (stick) {
                // 追加后子 View 还没重新排版，此刻 fullScroll 会滚到追加前的位置，等这一帧排完再滚。
                post(scrollToBottom);
            }
        }
    };

    private final Runnable scrollToBottom = new Runnable() {
        @Override
        public void run() {
            // 不能用 fullScroll(FOCUS_DOWN)：它会请求子 View 取得焦点，外层页面 ScrollView
            // 会跟着滚到日志框那里，一进页面就看不到顶部的模式卡片。
            android.view.View child = getChildAt(0);
            if (child != null) {
                scrollTo(0, Math.max(0, child.getBottom() - getHeight()));
            }
        }
    };

    private static int countLines(String chunk) {
        int n = 0;
        for (int i = 0; i < chunk.length(); i++) {
            if (chunk.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 从 Editable 头部按整行删，避免每来一行就重建整段文本。 */
    private void trimToLimit() {
        Editable e = view.getEditableText();
        if (e == null) {
            return;
        }
        while (shownLines > MAX_LINES) {
            int nl = TextUtils.indexOf(e, '\n');
            if (nl < 0) {
                break;
            }
            e.delete(0, nl + 1);
            shownLines--;
        }
    }

    /** 用户自己往上翻的时候不要抢他的位置，只有本来就贴着底才继续跟。 */
    private boolean atBottom() {
        Layout layout = view.getLayout();
        if (layout == null) {
            return true;
        }
        int contentBottom = layout.getLineTop(layout.getLineCount());
        return contentBottom - (view.getScrollY() + view.getHeight()) <= bottomTolerance;
    }

    void clear() {
        removeCallbacks(flush);
        removeCallbacks(scrollToBottom);
        flushScheduled = false;
        pending.setLength(0);
        lines.clear();
        shownLines = 0;
        view.setText("");
    }

    String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }
}