package com.wifitput.android.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 把一次测试的全部产物拼成 PC 端认的结果包（schema wifitput.result/1）。 */
public class Bundle {

    public static final String SCHEMA = "wifitput.result/1";
    public static final String VERSION = "1.0";

    public final String mode;
    public final String runId;
    public final String startedAt;
    public String finishedAt = "";
    public int duration = 30;
    public int streams = 4;
    public int interval = 1;
    public boolean reverse = true;
    public int port = 5201;
    public String udpBw = "";

    /** 连PC 专用：标准报告需要的元数据。 */
    public String testKind = "";
    public String toolClient = "";
    public String toolServer = "";
    public String direction = "";
    public String bandwidth = "";
    public String locationPreset = "";
    public int distanceM = -1;
    public boolean longRun;
    public JSONObject ping;
    public JSONArray telemetry;
    public JSONObject telemetrySummary;
    public JSONObject server;

    public final JSONArray devices = new JSONArray();
    public final JSONArray results = new JSONArray();
    public final List<Record> records = new ArrayList<Record>();
    public final JSONArray notes = new JSONArray();

    public Bundle(String mode) {
        this.mode = mode;
        String now = stamp();
        this.startedAt = now;
        this.runId = "run_" + now.replaceAll("[^0-9]", "");
    }

    public String itemId() {
        if ("pc".equals(mode)) {
            return "pc_tput";
        }
        if ("hotspot".equals(mode)) {
            return "hotspot_tput";
        }
        return "wifi_tput";
    }

    public void addDevice(JSONObject d) throws JSONException {
        devices.put(d);
    }

    public void addNote(String s) {
        if (s != null && !s.trim().isEmpty()) {
            notes.put(s.trim());
        }
    }

    /** 把一次 iperf3 客户端结果登记成一条 result，并把逐秒记录并入全局 records。 */
    public void addResult(String testId, String name, Iperf.Result r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("test_id", testId);
        o.put("name", name);
        o.put("kind", r.kind);
        JSONObject p = new JSONObject();
        p.put("duration", (int) Math.rint(r.duration));
        p.put("streams", streams);
        p.put("reverse", r.reverse);
        p.put("mode", mode);
        o.put("params", p);
        o.put("sender", rateObj(r.senderRate));
        o.put("receiver", rateObj(r.receiverRate));
        o.put("ok", r.ok);
        o.put("error", r.error == null ? "" : r.error);
        o.put("records_n", r.records.size());
        JSONArray rs = new JSONArray();
        for (Record rec : r.records) {
            rs.put(rec.toJson());
        }
        o.put("records", rs);
        if (!r.receiverDetail.isEmpty()) {
            o.put("receiver_detail", r.receiverDetail);
        }
        results.put(o);
        records.addAll(r.records);
    }

    static JSONObject rateObj(String text) throws JSONException {
        JSONObject o = new JSONObject();
        if (text == null || text.trim().isEmpty()) {
            o.put("rate", 0.0);
            o.put("unit", "Mbits/sec");
            return o;
        }
        String[] parts = text.trim().split("\\s+");
        double v = 0.0;
        try {
            v = Double.parseDouble(parts[0]);
        } catch (NumberFormatException ignored) {
        }
        o.put("rate", v);
        o.put("unit", unitFull(parts.length > 1 ? parts[1] : "Mb/s"));
        return o;
    }

    static String unitFull(String shortUnit) {
        if ("b/s".equals(shortUnit)) return "bits/sec";
        if ("Kb/s".equals(shortUnit)) return "Kbits/sec";
        if ("Gb/s".equals(shortUnit)) return "Gbits/sec";
        if ("Tb/s".equals(shortUnit)) return "Tbits/sec";
        return "Mbits/sec";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject b = new JSONObject();
        b.put("schema", SCHEMA);
        b.put("app", new JSONObject()
                .put("name", "WiFiTput")
                .put("version", VERSION)
                .put("package", "com.wifitput.android"));
        b.put("run", new JSONObject()
                .put("id", runId)
                .put("mode", mode)
                .put("started_at", startedAt)
                .put("finished_at", finishedAt.isEmpty() ? stamp() : finishedAt));
        JSONObject m = new JSONObject();
        m.put("items", new JSONArray().put(itemId()));
        m.put("duration", duration);
        m.put("streams", streams);
        m.put("interval", interval);
        m.put("reverse", reverse);
        m.put("port", port);
        m.put("udp_bw", udpBw);
        m.put("mode", mode);
        if (!testKind.isEmpty()) {
            m.put("test_kind", testKind);
            m.put("duration_per_direction", duration);
            m.put("long_run", longRun);
        }
        if (!toolClient.isEmpty() || !toolServer.isEmpty()) {
            m.put("tools", new JSONObject().put("client", toolClient).put("server", toolServer));
        }
        if (!direction.isEmpty()) {
            m.put("direction", direction);
        }
        if (!bandwidth.isEmpty()) {
            m.put("bandwidth", bandwidth);
        }
        if (!locationPreset.isEmpty() || distanceM >= 0) {
            JSONObject loc = new JSONObject();
            loc.put("preset", locationPreset);
            loc.put("distance_m", distanceM);
            m.put("location", loc);
        }
        b.put("meta", m);
        b.put("devices", devices);
        b.put("results", results);
        JSONArray recs = new JSONArray();
        for (Record r : records) {
            recs.put(r.toJson());
        }
        b.put("records", recs);
        b.put("screen", JSONObject.NULL);
        b.put("notes", notes);
        if (ping != null) {
            b.put("ping", ping);
        }
        if (telemetry != null) {
            b.put("telemetry", telemetry);
        }
        if (telemetrySummary != null) {
            b.put("telemetry_summary", telemetrySummary);
        }
        if (server != null) {
            b.put("server", server);
        }
        return b;
    }

    public String toJsonString() throws JSONException {
        return toJson().toString();
    }

    public static String stamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new java.util.Date());
    }
}
