package com.wifitput.android.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次测试的汇总统计，口径与 PC 端 report.py 对齐：
 * 平均取全程汇总行，最低/最高取逐秒行（有 SUM 用 SUM），重传取整段值，
 * 停滞秒数按逐秒速率为 0 计。
 *
 * 手机端结果卡、历史对比都用这里，避免同一个数在 App 和报告里算法不一致。
 */
public class Stats {

    public String kind = "TCP";
    public boolean reverse;
    public int duration;
    public int streams;
    public int interval = 1;

    /** 发送端/接收端整段速率（Mb/s），取不到为 -1。 */
    public double senderMb = -1;
    public double receiverMb = -1;
    /** 发起端 WiFi 协商速率（Mbps），取不到为 -1；用于算「占协商速率百分比」。 */
    public double linkMbps = -1;

    public double avgMb = -1;
    public double minMb = -1;
    public double maxMb = -1;
    /** 逐秒速率完全为 0 的秒数。 */
    public int stalls;
    /** 重传合计；非 TCP 或无数据为 -1。 */
    public int retr = -1;
    public double jitter = -1;
    public int lost = -1;
    public int total = -1;
    public double lostPct = -1;
    public double transfer = -1;
    public String transferUnit = "";
    public int recordsN;

    public boolean hasData() {
        return avgMb >= 0 || senderMb >= 0 || receiverMb >= 0;
    }

    /** 结果卡上的主数字：优先接收端，其次平均，最后发送端。 */
    public double mainMb() {
        if (receiverMb >= 0) {
            return receiverMb;
        }
        if (avgMb >= 0) {
            return avgMb;
        }
        return senderMb;
    }

    // ---------------------------------------------------------------- 构造

    /** 客户端一次 iperf3 运行的结果。 */
    public static Stats of(Iperf.Result r) {
        if (r == null) {
            return null;
        }
        Stats s = new Stats();
        s.kind = r.kind == null ? "TCP" : r.kind;
        s.reverse = r.reverse;
        s.duration = (int) Math.rint(r.duration);
        s.senderMb = prettyRate(r.senderRate);
        s.receiverMb = prettyRate(r.receiverRate);
        s.compute(r.records);
        // 正向 UDP 时抖动/丢包在对端（服务端）的汇总里，客户端的逐秒行未必有
        s.applyReceiverDetail(r.receiverDetail);
        // 对端视角的汇总行（server output）里可能有本机视角缺的字段：
        // 正向时是接收端的抖动/丢包，反向时是发送端的重传数。
        if (r.serverRecords != null && !r.serverRecords.isEmpty()
                && (s.retr < 0 || s.jitter < 0 || s.lost < 0)) {
            Stats remote = new Stats();
            remote.compute(r.serverRecords);
            if (s.retr < 0) {
                s.retr = remote.retr;
            }
            if (s.jitter < 0) {
                s.jitter = remote.jitter;
            }
            if (s.lost < 0) {
                s.lost = remote.lost;
                s.total = remote.total;
                s.lostPct = remote.lostPct;
            }
        }
        return s;
    }

    /** 服务端当次收到的逐秒记录（没有发送端/接收端整段速率）。 */
    public static Stats ofRecords(List<Record> records) {
        Stats s = new Stats();
        s.compute(records);
        if (records != null && !records.isEmpty()) {
            s.kind = records.get(0).kind == null ? "TCP" : records.get(0).kind;
        }
        return s;
    }

    /**
     * 从历史记录里保存的结果包还原统计；解析失败返回 null。
     * 结果包里的 records 是发起端视角的逐秒行，与客户端运行一致。
     */
    public static Stats ofBundle(String json) {
        return ofBundle(json, 0);
    }

    /** 连PC 的结果包有下载/上传两条 result；index=0 下载，1 上传。 */
    public static Stats ofBundle(String json, int index) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            JSONObject meta = o.optJSONObject("meta");
            JSONArray results = o.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            JSONObject r0 = results.optJSONObject(Math.max(0, Math.min(index, results.length() - 1)));
            if (r0 == null) {
                return null;
            }
            Stats s = new Stats();
            s.kind = r0.optString("kind", "TCP");
            JSONObject p = r0.optJSONObject("params");
            int metaDuration = meta == null ? 0 : meta.optInt("duration", 0);
            int metaStreams = meta == null ? 0 : meta.optInt("streams", 0);
            int metaInterval = meta == null ? 1 : meta.optInt("interval", 1);
            s.duration = p == null ? metaDuration : p.optInt("duration", metaDuration);
            s.streams = p == null ? metaStreams : p.optInt("streams", metaStreams);
            s.interval = metaInterval <= 0 ? 1 : metaInterval;
            s.reverse = p != null && p.optBoolean("reverse", false);
            s.senderMb = rateObj(r0.optJSONObject("sender"));
            s.receiverMb = rateObj(r0.optJSONObject("receiver"));
            JSONArray devs = o.optJSONArray("devices");
            if (devs != null && devs.length() > 0) {
                JSONObject d = devs.optJSONObject(0);
                JSONObject w = d == null ? null : d.optJSONObject("wifi");
                if (w != null) {
                    s.linkMbps = w.optDouble("link_tx_mbps", -1);
                }
            }
            JSONArray arr = r0.optJSONArray("records");
            List<Record> recs = new ArrayList<Record>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject ro = arr.optJSONObject(i);
                    if (ro != null) {
                        recs.add(recordOf(ro));
                    }
                }
            }
            s.compute(recs);
            s.applyReceiverDetail(r0.optString("receiver_detail", ""));
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 Iperf.summarize 拼出来的接收端摘要里补抖动/丢包，例如
     * "jitter 1.250 ms  丢包 5/19000 (0.026%)"。只在逐秒数据里没拿到时补。
     */
    private void applyReceiverDetail(String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            return;
        }
        if (jitter < 0) {
            Matcher m = Pattern.compile("jitter\\s+([\\d.]+)\\s*ms").matcher(detail);
            if (m.find()) {
                jitter = doubleOf(m.group(1));
            }
        }
        if (lost < 0) {
            Matcher m = Pattern.compile("丢包\\s+(\\d+)/(\\d+)\\s*\\(([\\d.]+)%\\)").matcher(detail);
            if (m.find()) {
                lost = intOf(m.group(1));
                total = intOf(m.group(2));
                lostPct = doubleOf(m.group(3));
            }
        }
    }

    private static Record recordOf(JSONObject o) {
        Record r = new Record();
        r.t = o.optString("t", "");
        r.test = o.optString("test", "");
        r.kind = o.optString("kind", "TCP");
        r.stream = o.optString("stream", "");
        r.start = o.optDouble("start", 0);
        r.end = o.optDouble("elapsed", o.optDouble("end", 0));
        r.transfer = o.optDouble("transfer", 0);
        r.unit = o.optString("unit", "");
        r.rate = o.optDouble("rate", 0);
        r.rateUnit = o.optString("rate_unit", "Mbits/sec");
        r.retr = o.optString("retr", "");
        r.jitter = o.optString("jitter", "");
        r.lost = o.optString("lost", "");
        r.total = o.optString("total", "");
        r.lostPct = o.optString("lost_pct", "");
        r.role = o.optString("role", "");
        r.raw = o.optString("raw", "");
        return r;
    }

    private static double rateObj(JSONObject o) {
        if (o == null) {
            return -1;
        }
        double v = o.optDouble("rate", -1);
        if (v < 0) {
            return -1;
        }
        return mbps(v, o.optString("unit", "Mbits/sec"));
    }

    // ---------------------------------------------------------------- 统计

    private void compute(List<Record> recs) {
        if (recs == null || recs.isEmpty()) {
            return;
        }
        recordsN = recs.size();
        double maxEnd = 0;
        for (Record r : recs) {
            if (r.end > maxEnd) {
                maxEnd = r.end;
            }
        }
        // 全程汇总行：从 0 开始、一直覆盖到最后（如 0.00-10.00 sec）。
        // 单流时 iperf3 不打 [SUM]，这行会混在普通行里，必须单独挑出来。
        Record full = null;
        for (Record r : recs) {
            if (r.start <= 0.001 && r.end >= maxEnd - 0.001) {
                full = r;
            }
        }
        List<Record> sumRows = new ArrayList<Record>();
        for (Record r : recs) {
            if ("SUM".equalsIgnoreCase(r.stream)) {
                sumRows.add(r);
            }
        }
        // 多流用 SUM 行看每秒；单流只能用普通行。全程汇总行不参与最低/最高。
        List<Record> span = new ArrayList<Record>();
        for (Record r : sumRows) {
            if (r != full) {
                span.add(r);
            }
        }
        if (span.isEmpty()) {
            if (!sumRows.isEmpty() && full != null) {
                // 单 interval 的测试（时长 = 采样间隔）没有独立的逐秒 SUM 行，
                // 整段汇总行就是唯一的一秒；拿它当区间，才不会把单流当成合计。
                span.add(full);
            } else {
                for (Record r : recs) {
                    if (r != full) {
                        span.add(r);
                    }
                }
            }
        }

        List<Double> rates = new ArrayList<Double>();
        for (Record r : span) {
            double v = mbps(r.rate, r.rateUnit);
            rates.add(v);
            if (v <= 0.0001) {
                stalls++;
            }
        }
        if (!rates.isEmpty()) {
            double lo = rates.get(0);
            double hi = rates.get(0);
            for (double v : rates) {
                if (v < lo) {
                    lo = v;
                }
                if (v > hi) {
                    hi = v;
                }
            }
            avgMb = full != null ? mbps(full.rate, full.rateUnit) : mean(rates);
            if (avgMb < 0) {
                avgMb = mean(rates);
            }
            minMb = Math.min(lo, avgMb);
            maxMb = Math.max(hi, avgMb);
        } else if (full != null) {
            avgMb = mbps(full.rate, full.rateUnit);
            minMb = avgMb;
            maxMb = avgMb;
        }

        // 重传：SUM 行是整段/合计口径，取最大值；没有 SUM 才把逐秒行相加
        if (!sumRows.isEmpty()) {
            int best = -1;
            for (Record r : sumRows) {
                int v = intOf(r.retr);
                if (v > best) {
                    best = v;
                }
            }
            retr = best;
        } else {
            int sum = 0;
            boolean seen = false;
            for (Record r : recs) {
                int v = intOf(r.retr);
                if (v >= 0) {
                    sum += v;
                    seen = true;
                }
            }
            retr = seen ? sum : -1;
        }

        if (full != null && !full.jitter.isEmpty()) {
            jitter = doubleOf(full.jitter);
        } else {
            double js = 0;
            int n = 0;
            for (Record r : recs) {
                double v = doubleOf(r.jitter);
                if (v >= 0) {
                    js += v;
                    n++;
                }
            }
            if (n > 0) {
                jitter = js / n;
            }
        }

        Record lastLoss = null;
        for (Record r : recs) {
            if (!r.lost.isEmpty()) {
                lastLoss = r;
            }
        }
        if (full != null && !full.lost.isEmpty()) {
            lastLoss = full;
        }
        if (lastLoss != null) {
            lost = intOf(lastLoss.lost);
            total = intOf(lastLoss.total);
            lostPct = doubleOf(lastLoss.lostPct);
            if (lostPct < 0 && lost >= 0 && total > 0) {
                lostPct = lost * 100.0 / total;
            }
        }

        if (full != null) {
            transfer = full.transfer;
            transferUnit = full.unit;
        } else {
            double sum = 0;
            boolean seen = false;
            for (Record r : recs) {
                if (r != full && r.stream != null && !"SUM".equalsIgnoreCase(r.stream)) {
                    // 没有汇总行时按普通行相加；多流 SUM 行只取一条，避免重复
                    sum += r.transfer;
                    seen = true;
                    transferUnit = r.unit;
                }
            }
            if (!seen) {
                for (Record r : recs) {
                    sum += r.transfer;
                    transferUnit = r.unit;
                }
            }
            transfer = sum;
        }
    }

    /** 参数是否一致：对比时必须同参数，否则没有可比性。 */
    public boolean sameParams(Stats o) {
        if (o == null) {
            return false;
        }
        return duration == o.duration
                && streams == o.streams
                && interval == o.interval
                && reverse == o.reverse
                && (kind == null ? "TCP" : kind).equalsIgnoreCase(o.kind == null ? "TCP" : o.kind);
    }

    // ---------------------------------------------------------------- 结论

    /** 一句话结论。linkMbps <= 0 时跳过「占协商速率」判断。 */
    public String verdict(double linkMbps) {
        if (!hasData()) {
            return "没有测到有效速率。";
        }
        double main = mainMb();
        StringBuilder sb = new StringBuilder();
        sb.append(receiverMb >= 0 ? "接收端 " : "平均 ");
        sb.append(fmtMb(main)).append(" Mb/s");
        if (linkMbps > 0 && main > 0) {
            double pct = main * 100.0 / linkMbps;
            if (pct > 150) {
                sb.append("，超过协商速率，数据可能没走空口");
            } else {
                sb.append("，约协商速率的 ").append(Math.round(pct)).append("%");
            }
        }
        if (stalls > 0) {
            sb.append("，有 ").append(stalls).append(" 秒完全停滞");
        }
        if (lostPct > 1.0) {
            sb.append("，丢包 ").append(fmtPct(lostPct)).append("%，可能有干扰");
        } else if (retr > 0) {
            sb.append("，重传 ").append(retr).append(" 次");
        }
        return sb.append("。").toString();
    }

    /** 0 正常、1 注意、2 明显异常；结果卡据此选颜色。 */
    public int level(double linkMbps) {
        if (!hasData()) {
            return 0;
        }
        if (stalls > 0 || lostPct > 1.0) {
            return 2;
        }
        double main = mainMb();
        if (linkMbps > 0 && main > 0 && main * 100.0 / linkMbps < 30) {
            return 2;
        }
        // 零星重传/极低丢包是正常波动，不要一有数字就变黄
        if (retr >= 10 || lostPct > 0.1
                || (linkMbps > 0 && main > 0 && main * 100.0 / linkMbps < 50)) {
            return 1;
        }
        return 0;
    }

    // ---------------------------------------------------------------- 格式

    public static String fmtMb(double v) {
        if (v < 0) {
            return "--";
        }
        if (v >= 100) {
            return String.format(Locale.US, "%.0f", v);
        }
        if (v >= 10) {
            return String.format(Locale.US, "%.1f", v);
        }
        return String.format(Locale.US, "%.2f", v);
    }

    public static String fmtPct(double v) {
        if (v < 0) {
            return "--";
        }
        if (v >= 10) {
            return String.format(Locale.US, "%.1f", v);
        }
        return String.format(Locale.US, "%.2f", v);
    }

    // ---------------------------------------------------------------- 工具

    static double mbps(double rate, String unit) {
        String u = unit == null ? "" : unit.trim();
        if (u.startsWith("G")) {
            return rate * 1000;
        }
        if (u.startsWith("T")) {
            return rate * 1000000;
        }
        if (u.startsWith("K")) {
            return rate / 1000;
        }
        if (u.startsWith("bits")) {
            return rate / 1e6;
        }
        return rate;
    }

    static double prettyRate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return -1;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 0) {
            return -1;
        }
        try {
            double v = Double.parseDouble(parts[0]);
            String u = parts.length > 1 ? parts[1] : "Mb/s";
            if ("Gb/s".equals(u)) {
                return v * 1000;
            }
            if ("Kb/s".equals(u)) {
                return v / 1000;
            }
            if ("b/s".equals(u)) {
                return v / 1e6;
            }
            return v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static double doubleOf(String s) {
        if (s == null || s.trim().isEmpty()) {
            return -1;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int intOf(String s) {
        if (s == null || s.trim().isEmpty()) {
            return -1;
        }
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static double mean(List<Double> v) {
        if (v == null || v.isEmpty()) {
            return -1;
        }
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.size();
    }
}
