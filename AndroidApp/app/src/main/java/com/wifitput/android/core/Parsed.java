package com.wifitput.android.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 一行 iperf3 数值输出的解析结果。表头、分隔线、控制行一律返回 null。 */
public class Parsed {

    // [  4]   0.00-1.00   sec  21.8 MBytes  182 Mbits/sec  0             sender
    // [SUM]   0.00-5.00   sec   118 MBytes  197 Mbits/sec  0.306 ms  6/21586 (0.028%)  receiver
    private static final Pattern LINE = Pattern.compile(
            "^\\[\\s*(\\S+?)\\s*\\]\\s+(\\d+(?:\\.\\d+)?)-(\\d+(?:\\.\\d+)?)\\s+sec\\s+"
            + "(\\d+(?:\\.\\d+)?)\\s+(\\S+)\\s+(\\d+(?:\\.\\d+)?)\\s+(\\S+)\\s*(.*)$");

    private static final Pattern LOSS = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*ms\\s+(\\d+)/(\\d+)\\s*\\(([\\d.]+)%\\)");

    private static final Pattern INTEGER = Pattern.compile("^(\\d+)");

    public String stream = "";
    public double start;
    public double end;
    public double transfer;
    public String unit = "";
    public double rate;
    public String rateUnit = "";
    public String retr = "";
    public String jitter = "";
    public String lost = "";
    public String total = "";
    public String lostPct = "";
    public String role = "";
    public boolean isSum;

    /** kind 取 "TCP" 或 "UDP"，决定尾部那个孤零零的整数是重传还是总包数。 */
    public static Parsed parse(String line, String kind) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (!s.startsWith("[")) {
            return null;
        }
        Matcher m = LINE.matcher(s);
        if (!m.find()) {
            return null;
        }
        Parsed p = new Parsed();
        try {
            p.stream = "#" + m.group(1);
            p.start = Double.parseDouble(m.group(2));
            p.end = Double.parseDouble(m.group(3));
            p.transfer = Double.parseDouble(m.group(4));
            p.unit = m.group(5);
            p.rate = Double.parseDouble(m.group(6));
            p.rateUnit = m.group(7);
        } catch (NumberFormatException e) {
            return null;
        }
        p.isSum = "SUM".equalsIgnoreCase(m.group(1));
        if (p.isSum) {
            p.stream = "SUM";
        }
        String tail = m.group(8) == null ? "" : m.group(8).trim();

        // 角色在最后，先剥掉
        if (tail.endsWith("receiver")) {
            p.role = "receiver";
            tail = tail.substring(0, tail.length() - 8).trim();
        } else if (tail.endsWith("sender")) {
            p.role = "sender";
            tail = tail.substring(0, tail.length() - 6).trim();
        }

        Matcher lm = LOSS.matcher(tail);
        if (lm.find()) {
            p.jitter = lm.group(1);
            p.lost = lm.group(2);
            p.total = lm.group(3);
            p.lostPct = lm.group(4);
            return p;
        }

        Matcher im = INTEGER.matcher(tail);
        if (im.find()) {
            String digits = im.group(1);
            if ("UDP".equalsIgnoreCase(kind) && !"receiver".equals(p.role)) {
                // UDP 发送端的这个数是总包数，不是重传
                p.total = digits;
            } else {
                p.retr = digits;
            }
        }
        return p;
    }
}
