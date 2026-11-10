package com.wifitput.android.ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 控制台只显示最新一行日志，完整的留给全屏弹窗和本机历史。
 * 以前那个常驻的日志框被收掉后，这里只做文本累积，不再牵涉 View。
 */
final class LogStore {

    private static final int MAX_LINES = 2000;

    private final List<String> lines = new ArrayList<String>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    void add(String msg) {
        if (msg == null) {
            return;
        }
        lines.add(clock.format(new Date()) + " | " + msg);
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
    }

    String last() {
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }

    String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    void clear() {
        lines.clear();
    }
}
