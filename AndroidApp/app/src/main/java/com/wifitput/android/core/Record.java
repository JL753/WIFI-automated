package com.wifitput.android.core;

import org.json.JSONException;
import org.json.JSONObject;

/** 逐秒明细的一行，字段与 PC 端 / 命令行版契约完全一致（18 个键）。 */
public class Record {
    public String t = "";
    public String test = "";
    public String kind = "TCP";
    public String stream = "";
    public double start;
    public double end;
    public double transfer;
    public String unit = "";
    public double rate;
    public String rateUnit = "Mbits/sec";
    public String retr = "";
    public String jitter = "";
    public String lost = "";
    public String total = "";
    public String lostPct = "";
    public String role = "";
    public String raw = "";

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("t", t);
        o.put("elapsed", end);
        o.put("test", test);
        o.put("kind", kind);
        o.put("stream", stream);
        o.put("start", start);
        o.put("end", end);
        o.put("transfer", transfer);
        o.put("unit", unit);
        o.put("rate", rate);
        o.put("rate_unit", rateUnit);
        o.put("retr", retr);
        o.put("jitter", jitter);
        o.put("lost", lost);
        o.put("total", total);
        o.put("lost_pct", lostPct);
        o.put("role", role);
        o.put("raw", raw);
        return o;
    }

    /** 从 iperf3 的数值行构造；解析失败返回 null。 */
    public static Record of(String testId, String kind, String direction, String line, String clock) {
        Parsed p = Parsed.parse(line, kind);
        if (p == null) {
            return null;
        }
        Record r = new Record();
        r.t = clock;
        r.test = testId;
        r.kind = kind;
        r.stream = p.stream;
        r.start = p.start;
        r.end = p.end;
        r.transfer = p.transfer;
        r.unit = p.unit;
        r.rate = p.rate;
        r.rateUnit = p.rateUnit;
        r.retr = p.retr;
        r.jitter = p.jitter;
        r.lost = p.lost;
        r.total = p.total;
        r.lostPct = p.lostPct;
        r.role = direction;
        r.raw = line;
        return r;
    }

    /** 显示用：形如 `#4   0-1s   37.1 MB  311.0 Mb/s  retr 0`。 */
    public String pretty() {
        StringBuilder sb = new StringBuilder();
        sb.append(pad(stream, 4)).append(' ');
        sb.append(pad(fmt(start) + "-" + fmt(end) + "s", 9)).append(' ');
        sb.append(lpad(trim(transfer), 9)).append(' ').append(unit).append(' ');
        sb.append(lpad(trim(rate), 9)).append(' ').append(shortUnit()).append(' ');
        if (jitter.length() > 0) {
            sb.append("jitter ").append(jitter).append(" ms ");
        }
        if (lost.length() > 0) {
            sb.append("lost ").append(lost);
            if (total.length() > 0) {
                sb.append('/').append(total);
            }
            if (lostPct.length() > 0) {
                sb.append(" (").append(lostPct).append("%)");
            }
            sb.append(' ');
        }
        if (retr.length() > 0 && !"0".equals(retr)) {
            sb.append("retr ").append(retr);
        }
        return sb.toString().trim();
    }

    public String shortUnit() {
        String u = rateUnit == null ? "" : rateUnit;
        if ("bits/sec".equals(u)) return "b/s";
        if ("Kbits/sec".equals(u)) return "Kb/s";
        if ("Mbits/sec".equals(u)) return "Mb/s";
        if ("Gbits/sec".equals(u)) return "Gb/s";
        if ("Tbits/sec".equals(u)) return "Tb/s";
        return u;
    }

    public String prettyRate() {
        return trim(rate) + " " + shortUnit();
    }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }

    static String lpad(String s, int n) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < n) {
            sb.insert(0, ' ');
        }
        return sb.toString();
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    static String trim(double v) {
        String s = String.format(java.util.Locale.US, "%.1f", v);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }
}
