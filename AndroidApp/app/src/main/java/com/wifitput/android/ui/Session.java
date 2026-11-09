package com.wifitput.android.ui;

import android.content.Context;
import android.os.Build;

import com.wifitput.android.core.Bin;
import com.wifitput.android.core.Bundle;
import com.wifitput.android.core.Dev;
import com.wifitput.android.core.Discovery;
import com.wifitput.android.core.History;
import com.wifitput.android.core.Iperf;
import com.wifitput.android.core.KeepAlive;
import com.wifitput.android.core.Prefs;
import com.wifitput.android.core.Record;
import com.wifitput.android.core.Upload;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次测试的完整流程：当服务端等对端来打，或者当客户端去打对端。
 * 跑在自己的线程里，所有输出都通过 Listener 回抛给界面。
 */
class Session {

    interface Listener {
        void onLine(String tag, String msg);

        /** 一条逐秒数据行，界面拿去画实时速率曲线（工作线程回调，操作控件要 post）。 */
        void onSample(Record rec);

        void onDone(Bundle bundle, String note);

        /** 扫描结束，把扫到的全部对端抛给界面弹窗。空列表表示一个都没找到。 */
        void onPeers(List<Discovery.Peer> peers);
    }

    final String mode;
    String role = "client";
    String host = "";
    int port = 5201;
    int duration = 10;
    int streams = 4;
    int interval = 1;
    String kind = "TCP";
    String udpBw = "";
    boolean reverse = false;

    private final Context ctx;
    private final Listener listener;
    private Thread worker;
    private volatile boolean cancelled;
    private volatile boolean serving;

    /** 本次运行 / 本次服务的完整日志，跑完存进历史。工作线程写、主线程读，靠 runLog 加锁。 */
    private final List<String> runLog = new ArrayList<String>();
    private String serveStartedAt = "";
    /** 服务端最后一条 SUM 的显示文本，停服时当历史摘要。跨线程读，加 volatile。 */
    private volatile String serveLastSum = "";
    /** 停服可能从界面线程和 startServing 的 finally 同时进来，靠这把锁保证只存一条历史。 */
    private final Object serveLock = new Object();

    Session(Context c, String mode, Listener l) {
        this.ctx = c.getApplicationContext();
        this.mode = mode;
        this.listener = l;
    }

    boolean running() {
        return worker != null && worker.isAlive();
    }

    boolean serving() {
        return serving;
    }

    String modeName() {
        if ("hotspot".equals(mode)) {
            return "热点";
        }
        if ("p2p".equals(mode)) {
            return "P2P";
        }
        return "WiFi";
    }

    private void line(String tag, String msg) {
        synchronized (runLog) {
            runLog.add(clock() + " | " + (tag == null ? "" : tag + " ") + msg);
        }
        listener.onLine(tag, msg);
    }

    /** 每次运行/服务开始前清空，否则上一轮的日志会混进这一轮的历史记录。 */
    private void resetRunLog() {
        synchronized (runLog) {
            runLog.clear();
        }
    }

    /** 本次运行的完整日志，存历史用。 */
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

    /** 起服务端并挂住，等对端打过来。返回后仍在后台等待，直到 stop。 */
    void startServing() {
        if (running()) {
            line("!!", "还有测试在跑，先停下来");
            return;
        }
        cancelled = false;
        resetRunLog();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bin.iperf3(ctx);
                    line("OK", "iperf3 已就绪：" + Bin.version(ctx));
                    Iperf.startServer(ctx, port);
                    line("OK", "服务端已监听 " + port + "，等对端来打流");
                    // 先记开始时间再置 serving，否则两个赋值之间被 stop 会存出空时间戳
                    serveStartedAt = Bundle.stamp();
                    serving = true;
                    KeepAlive.start(ctx, "服务端已就绪，端口 " + port);
                    Discovery.startResponder(label(), port);
                    line("--", "已开启对端发现，同网段的另一台手机能扫到你");
                    // 服务端是脱离的子进程，逐秒输出只落在日志文件里，只能轮询着读回来
                    long offset = 0;
                    final List<String> chunk = new ArrayList<String>();
                    serveLastSum = "";
                    while (!cancelled) {
                        try {
                            chunk.clear();
                            offset = Iperf.tailServerLog(ctx, offset, chunk);
                            for (String raw : chunk) {
                                if (raw.trim().isEmpty()) {
                                    continue;
                                }
                                // 启动那行前面已经打过「服务端已监听 N」，不重复
                                if (raw.startsWith("Server listening on")) {
                                    continue;
                                }
                                if (dashes(raw)) {
                                    continue;
                                }
                                // 探活是裸 TCP 连一下端口，iperf3 会把它当成半截控制连接报这个错。
                                // 我们自己造的噪声，别让用户以为服务端坏了。
                                if (raw.startsWith("iperf3: error - unable to receive cookie")) {
                                    continue;
                                }
                                Record rec = Record.of("srv", kind, "", raw, clock());
                                if (rec != null) {
                                    String pretty = rec.pretty();
                                    line("--", pretty);
                                    listener.onSample(rec);
                                    // 单流时 iperf3 不打 [SUM]，收尾汇总那行是 "[ 5] 0-60.06s ... sender"；
                                    // 多流才打 [SUM]。两种都认：带 SUM，或行尾带 sender/receiver 的汇总行。
                                    String tail = raw.trim();
                                    if ("SUM".equals(rec.stream)
                                            || tail.endsWith("sender") || tail.endsWith("receiver")) {
                                        serveLastSum = pretty;
                                    }
                                } else {
                                    // Accepted connection / iperf Done. / EXIT=0 这类原文照打
                                    line("--", raw);
                                }
                            }
                        } catch (Exception e) {
                            // tail 出问题不能把服务端循环干掉
                            line("!!", "读取服务端日志失败：" + msg(e));
                        }
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    line("XX", "起服务端失败：" + msg(e));
                } finally {
                    stopServing();
                }
            }
        }, "session-serve");
        worker.setDaemon(true);
        worker.start();
    }

    String label() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        return model + " · " + modeName();
    }

    /** 停服务端 / 停客户端，一把杀掉 iperf3。 */
    void stopServing() {
        cancelled = true;
        Discovery.stopResponder();
        Iperf.stopServer(ctx);
        // startServing 的 finally 也会走到这里，只有真的在服务时才存，避免存出两条重复记录
        synchronized (serveLock) {
            if (serving) {
                serving = false;
                KeepAlive.stop(ctx);
                saveServeHistory();
                line("--", "服务端已停止");
            }
        }
    }

    /** 服务端历史：摘要 = 端口 + 最后一条 SUM。存不下只提示，绝不往外抛。 */
    private void saveServeHistory() {
        String summary = "端口 " + port + "\n"
                + (serveLastSum == null || serveLastSum.isEmpty() ? "服务端未收到测试流量" : serveLastSum);
        try {
            History.save(ctx, mode, "server", serveStartedAt, Bundle.stamp(), summary, runLogText(), "");
        } catch (Exception e) {
            line("!!", "历史记录保存失败：" + msg(e));
        }
    }

    /** 当客户端打一轮，出结果包并尝试上传。 */
    void runOnce() {
        if (running()) {
            line("!!", "上一轮还没跑完，先停下来");
            return;
        }
        cancelled = false;
        resetRunLog();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                Bundle bundle = null;
                String note = "";
                try {
                    Bin.iperf3(ctx);
                    line("OK", "iperf3 已就绪：" + Bin.version(ctx));
                    if (host == null || host.trim().isEmpty()) {
                        line("XX", "还没填对端地址 —— 点「扫描对端」或手工填 IP");
                        return;
                    }
                    line("--", "目标 " + host + ":" + port + "  "
                            + kind + "  " + duration + " 秒  " + streams + " 条流"
                            + "  采样 " + interval + " 秒"
                            + (reverse ? "  反向 -R" : "")
                            + ("UDP".equals(kind) ? "  限速 " + udpBw : ""));
                    bundle = new Bundle(mode);
                    bundle.duration = duration;
                    bundle.streams = streams;
                    bundle.interval = interval;
                    bundle.reverse = reverse;
                    bundle.port = port;
                    bundle.udpBw = "UDP".equals(kind) ? udpBw : "";
                    bundle.addDevice(Dev.collect(ctx));

                    KeepAlive.start(ctx, "正在打流 · " + modeName());
                    String testId = reverse ? "tcp_down" : "tcp_up";
                    if ("UDP".equals(kind)) {
                        testId = reverse ? "udp_down" : "udp_up";
                    }
                    String name = modeName() + "吞吐量（" + kind
                            + (reverse ? " 反向 -R" : " 正向") + "）";
                    Iperf.Result r = Iperf.runClient(ctx, testId, kind, host, port,
                            duration, streams, interval, reverse, udpBw, new Iperf.OnRecord() {
                                @Override
                                public void onRecord(Record rec) {
                                    // 四流时每秒四条，以前按条数取模会看起来像丢了几秒，逐条交给界面攒批。
                                    line("--", rec.pretty());
                                    listener.onSample(rec);
                                }
                            });
                    bundle.addResult(testId, name, r);
                    if (r.ok) {
                        line("OK", "完成，用时 " + Math.round((r.endedAt - System.currentTimeMillis()) / 1000.0 + duration)
                                + " 秒，" + r.records.size() + " 行数据");
                        String sr = r.senderRate == null || r.senderRate.isEmpty() ? "--" : r.senderRate;
                        String rr = r.receiverRate == null || r.receiverRate.isEmpty() ? "--" : r.receiverRate;
                        line("--", "发送端 " + sr + "   接收端 " + rr);
                        if (!r.receiverDetail.isEmpty()) {
                            line("--", r.receiverDetail);
                        }
                    } else {
                        line("XX", "失败：" + (r.error.isEmpty() ? "iperf3 没有报 iperf Done." : r.error));
                    }
                    if (cancelled) {
                        note = "本轮被手动停止，结果可能不完整";
                        bundle.addNote(note);
                    }
                    bundle.finishedAt = Bundle.stamp();
                    note = upload(bundle, note);
                    // 客户端历史：日志 + 结果包一起存，重发时不用再跑一次
                    // bundle 为 null 说明这轮根本没起测试（比如没填对端地址），没有可存的东西
                    if (bundle != null) {
                        try {
                            String summary = "时长 " + duration + " 秒 · " + streams + " 流 · 采样 "
                                    + interval + " 秒 · " + kind + (reverse ? " 反向" : " 正向");
                            History.save(ctx, mode, "client", bundle.startedAt, bundle.finishedAt,
                                    summary, runLogText(), bundle.toJsonString());
                        } catch (Exception e) {
                            line("!!", "历史记录保存失败：" + msg(e));
                        }
                    }
                } catch (Exception e) {
                    line("XX", "测试异常：" + msg(e));
                    note = "测试异常：" + msg(e);
                } finally {
                    KeepAlive.stop(ctx);
                    listener.onDone(bundle, note);
                }
            }
        }, "session-run");
        worker.setDaemon(true);
        worker.start();
    }

    /** 只上传已有结果（给「重发上一次」用）。 */
    String upload(Bundle bundle, String note) {
        if (bundle == null) {
            line("!!", "还没有结果可以上传");
            return note;
        }
        // PC 地址由设置界面直接写 Prefs，这里现读，避免 Session 里存一份过期副本
        String pcHost = Prefs.getStr(ctx, "pc_host", "");
        int pcPort = Prefs.getInt(ctx, "pc_port", 8737);
        if (pcHost == null || pcHost.trim().isEmpty()) {
            line("--", "没填 PC 地址，结果留在手机里，可以稍后在「设置」里填地址重发");
            return note;
        }
        try {
            String json = bundle.toJsonString();
            line("--", "正在上传到 " + pcHost + ":" + pcPort + "（" + json.length() + " 字节 JSON）");
            Upload.Outcome o = Upload.post(pcHost, pcPort, json);
            if (o.ok) {
                line("OK", o.message);
                return note;
            }
            line("XX", "上传失败：" + o.message);
            // 失败已经打进日志了，不要再塞进 note —— 否则界面会把同一句再打一遍。
            return note;
        } catch (Exception e) {
            line("XX", "上传异常：" + msg(e));
            return note;
        }
    }

    /** 在后台扫对端。 */
    void scan(final int waitMs) {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                line("--", "正在广播寻找对端（" + (waitMs / 1000.0) + " 秒）…");
                List<Discovery.Peer> peers = Discovery.scan(waitMs);
                if (peers.isEmpty()) {
                    line("!!", "没找到对端 —— 两台手机要连同一个热点或同一个 WiFi；"
                            + "或对方还没在这个 App 里点「当服务端」");
                } else {
                    for (Discovery.Peer p : peers) {
                        line("OK", "找到 " + p.text());
                    }
                }
                listener.onPeers(peers);
            }
        }, "session-scan");
        t.setDaemon(true);
        t.start();
    }

    /**
     * iperf3 的分隔线，回显时没必要打出来。
     * TCP 段是连着的 "-----"，UDP 段是 "- - - -" 这种带空格的，空格也要忽略。
     */
    static boolean dashes(String s) {
        boolean seen = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                continue;
            }
            if (c != '-') {
                return false;
            }
            seen = true;
        }
        return seen;
    }

    static String msg(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return s;
    }

    /** 从设备信息里挑一个「对端可能可达」的本机地址，给用户看。 */
    String myHint() {
        try {
            List<String> ips = Upload.localIps();
            return ips.isEmpty() ? "本机没有可用 IPv4 地址" : "本机 " + ips.get(0);
        } catch (Exception e) {
            return "本机地址读取失败";
        }
    }

    void savePrefs() {
        // pc_host / pc_port 由设置界面直接写 Prefs，这里不再覆盖
        Prefs.putInt(ctx, "duration", duration);
        Prefs.putInt(ctx, "streams", streams);
        Prefs.putInt(ctx, "interval", interval);
        Prefs.putInt(ctx, "port", port);
        Prefs.putStr(ctx, "kind", kind);
        Prefs.putStr(ctx, "udp_bw", udpBw);
        Prefs.putStr(ctx, "host", host);
    }

    static JSONObject dummy() {
        return new JSONObject();
    }
}
