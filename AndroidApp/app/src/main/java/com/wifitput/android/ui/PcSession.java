package com.wifitput.android.ui;

import android.content.Context;

import com.wifitput.android.core.Bin;
import com.wifitput.android.core.Bundle;
import com.wifitput.android.core.Dev;
import com.wifitput.android.core.History;
import com.wifitput.android.core.Iperf;
import com.wifitput.android.core.PcPing;
import com.wifitput.android.core.Record;
import com.wifitput.android.core.Shell;
import com.wifitput.android.core.Stats;
import com.wifitput.android.core.Telemetry;
import com.wifitput.android.core.Upload;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 「连PC」一次测试：空闲 ping → 下载 → 上传 → 遥测 → 结果包 → 上传 PC → 历史。
 * 跑在后台线程；每方向都跑满设定时长，所以总时长约为 2×。
 */
class PcSession {

    interface Listener {
        void onPhase(String phase);

        void onLine(String tag, String msg);

        void onRecord(Record rec, boolean down);

        void onDone(Bundle bundle, Stats down, Stats up, PcPing.Result ping, String note);
    }

    String host = "";
    int iperfPort = 5201;
    int httpPort = 0;
    String serverLabel = "";
    String serverVersion = "";
    int duration = 60;
    int streams = 4;
    int interval = 1;
    int pingCount = 10;
    boolean longRun;
    String locationPreset = "";
    int distanceM = -1;
    String label = "";

    private final Context ctx;
    private final Listener listener;
    private final List<String> runLog = new ArrayList<String>();
    private Thread worker;
    private volatile boolean cancelled;
    private String lastHistoryId = "";

    PcSession(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    boolean running() {
        return worker != null && worker.isAlive();
    }

    String lastHistoryId() {
        return lastHistoryId;
    }

    void run() {
        if (running()) {
            line("!!", "上一轮还没跑完，先停下来");
            return;
        }
        cancelled = false;
        lastHistoryId = "";
        synchronized (runLog) {
            runLog.clear();
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                oneRun();
            }
        }, "pc-session");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        cancelled = true;
        Shell.kill("iperf3");
    }

    private void oneRun() {
        Bundle bundle = null;
        Stats downStats = null;
        Stats upStats = null;
        PcPing.Result ping = null;
        String note = "";
        Telemetry telemetry = null;
        try {
            Bin.iperf3(ctx);
            String clientVersion = Bin.version(ctx);
            line("OK", "iperf3 已就绪：" + clientVersion);
            if (host == null || host.trim().isEmpty()) {
                line("XX", "还没选 PC 服务端 —— 点「扫描 PC」或手工填地址");
                note = "没有 PC 地址";
                return;
            }
            bundle = new Bundle("pc");
            bundle.duration = duration;
            bundle.streams = streams;
            bundle.interval = interval;
            bundle.port = iperfPort;
            bundle.reverse = false;
            bundle.testKind = "pc_tput";
            bundle.toolClient = clientVersion;
            bundle.toolServer = serverVersion;
            bundle.direction = "both";
            bundle.bandwidth = "tcp_unlimited";
            bundle.locationPreset = locationPreset;
            bundle.distanceM = distanceM;
            bundle.longRun = longRun;
            JSONObject dev = Dev.collect(ctx);
            bundle.addDevice(dev);
            bundle.server = new JSONObject()
                    .put("label", serverLabel)
                    .put("ip", host)
                    .put("iperf_port", iperfPort)
                    .put("http_port", httpPort)
                    .put("version", serverVersion);

            double linkMbps = -1;
            JSONObject wifi = dev.optJSONObject("wifi");
            if (wifi != null) {
                linkMbps = wifi.optDouble("link_tx_mbps", -1);
            }

            line("--", "目标 PC " + host + ":" + iperfPort
                    + (serverLabel.isEmpty() ? "" : "（" + serverLabel + "）"));
            line("--", "TCP 不限速 · 每方向 " + duration + " 秒 · " + streams + " 流"
                    + " · 采样 " + interval + " 秒"
                    + (locationPreset.isEmpty() ? "" : " · " + locationPreset));

            listener.onPhase("ping");
            ping = PcPing.probe(host, 8738, iperfPort, pingCount, 900);
            line("--", "空闲探测（" + ping.method + "）：丢包 "
                    + PcPing.fmt(ping.lossPct, 1) + "%，平均 "
                    + PcPing.fmt(ping.avgMs, 1) + " ms");
            if (cancelled) {
                note = "本轮被手动停止，结果可能不完整";
            }

            telemetry = new Telemetry(ctx, longRun ? 5 : 1);
            telemetry.start();

            Iperf.Result down = null;
            if (!cancelled) {
                listener.onPhase("down");
                line("--", "下载（PC → 手机）开始");
                down = Iperf.runClient(ctx, "pc_down", "TCP", host, iperfPort,
                        duration, streams, interval, true, "", new Iperf.OnRecord() {
                            @Override
                            public void onRecord(Record rec) {
                                listener.onRecord(rec, true);
                            }
                        });
                if (down.ok) {
                    line("OK", "下载完成：" + down.receiverRate + "（接收） / "
                            + down.senderRate + "（PC 发送）");
                } else {
                    line("XX", "下载失败：" + down.error);
                }
            }

            Iperf.Result up = null;
            if (!cancelled) {
                listener.onPhase("up");
                line("--", "上传（手机 → PC）开始");
                up = Iperf.runClient(ctx, "pc_up", "TCP", host, iperfPort,
                        duration, streams, interval, false, "", new Iperf.OnRecord() {
                            @Override
                            public void onRecord(Record rec) {
                                listener.onRecord(rec, false);
                            }
                        });
                if (up.ok) {
                    line("OK", "上传完成：" + up.senderRate + "（手机发送） / "
                            + up.receiverRate + "（PC 接收）");
                } else {
                    line("XX", "上传失败：" + up.error);
                }
            }

            if (cancelled) {
                note = "本轮被手动停止，结果可能不完整";
            }
            if (down != null) {
                if (longRun) {
                    keepSum(down.records);
                }
                downStats = Stats.of(down);
                downStats.linkMbps = linkMbps;
                bundle.addResult("pc_down", "连PC 下载（PC→手机）", down);
            }
            if (up != null) {
                if (longRun) {
                    keepSum(up.records);
                }
                upStats = Stats.of(up);
                upStats.linkMbps = linkMbps;
                bundle.addResult("pc_up", "连PC 上传（手机→PC）", up);
            }
            bundle.ping = pingJson(ping);
            if (telemetry != null) {
                bundle.telemetry = telemetry.toJson();
                bundle.telemetrySummary = telemetry.summary();
            }
            bundle.finishedAt = Bundle.stamp();
            if (note != null && !note.isEmpty()) {
                bundle.addNote(note);
            }
            line("--", "结果包已生成：下载 " + (down == null ? 0 : down.records.size())
                    + " 行、上传 " + (up == null ? 0 : up.records.size()) + " 行");

            String json = bundle.toJsonString();
            if (httpPort > 0) {
                line("--", "正在上传到 PC " + host + ":" + httpPort + "（" + json.length() + " 字节）");
                Upload.Outcome o = Upload.post(host, httpPort, json);
                if (o.ok) {
                    line("OK", o.message);
                } else {
                    line("XX", "上传失败：" + o.message + "；结果已保存在本机历史");
                }
            } else {
                line("--", "PC 没有广播结果接收端口，结果已保存在本机历史");
            }

            try {
                String summary = "下载 " + (downStats == null || !downStats.hasData() ? "--"
                        : Stats.fmtMb(downStats.mainMb()) + " Mb/s")
                        + " · 上传 " + (upStats == null || upStats.senderMb < 0 ? "--"
                        : Stats.fmtMb(upStats.senderMb) + " Mb/s")
                        + " · 每方向 " + duration + " 秒 · " + streams + " 流";
                lastHistoryId = History.save(ctx, "pc", "client", bundle.startedAt,
                        bundle.finishedAt, summary, runLogText(), json, label);
            } catch (Exception e) {
                line("!!", "历史记录保存失败：" + msg(e));
            }
        } catch (Exception e) {
            line("XX", "连PC 测试异常：" + msg(e));
            note = "测试异常：" + msg(e);
        } finally {
            if (telemetry != null) {
                telemetry.stop();
            }
            listener.onDone(bundle, downStats, upStats, ping, note);
        }
    }

    /** 长稳只留 SUM 行；单流没有 SUM 时保持原样。 */
    private static void keepSum(List<Record> recs) {
        boolean hasSum = false;
        for (Record r : recs) {
            if ("SUM".equalsIgnoreCase(r.stream)) {
                hasSum = true;
                break;
            }
        }
        if (!hasSum) {
            return;
        }
        Iterator<Record> it = recs.iterator();
        while (it.hasNext()) {
            Record r = it.next();
            if (!"SUM".equalsIgnoreCase(r.stream)) {
                it.remove();
            }
        }
    }

    private static JSONObject pingJson(PcPing.Result p) {
        JSONObject o = new JSONObject();
        if (p == null) {
            return o;
        }
        try {
            o.put("method", p.method);
            o.put("probes", p.probes);
            o.put("lost", p.lost);
            o.put("loss_pct", round(p.lossPct, 2));
            o.put("avg_ms", p.avgMs >= 0 ? round(p.avgMs, 2) : JSONObject.NULL);
            o.put("min_ms", p.minMs >= 0 ? round(p.minMs, 2) : JSONObject.NULL);
            o.put("max_ms", p.maxMs >= 0 ? round(p.maxMs, 2) : JSONObject.NULL);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private void line(String tag, String msg) {
        synchronized (runLog) {
            runLog.add(clock() + " | " + (tag == null ? "" : tag + " ") + msg);
        }
        listener.onLine(tag, msg);
    }

    private String runLogText() {
        StringBuilder sb = new StringBuilder();
        synchronized (runLog) {
            for (String s : runLog) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    private static String clock() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    static String msg(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return s;
    }
}
