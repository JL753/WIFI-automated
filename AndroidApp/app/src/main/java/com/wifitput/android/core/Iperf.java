package com.wifitput.android.core;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** iperf3 的服务端与客户端封装。 */
public class Iperf {

    public static final String LOG_NAME = "iperf_srv.log";
    public static final String DONE_MARK = "iperf Done.";

    public interface OnRecord {
        void onRecord(Record r);
    }

    public static class Result {
        public boolean ok;
        public String error = "";
        public final List<Record> records = new ArrayList<Record>();
        /** --get-server-output 带回来的服务端视角行，只用来取接收端速率，不写进明细。 */
        public final List<Record> serverRecords = new ArrayList<Record>();
        public String rawText = "";
        public String testId = "";
        public String kind = "TCP";
        public boolean reverse;
        public double duration;
        public String senderRate = "";
        public String receiverRate = "";
        public String receiverDetail = "";
        public long endedAt;
    }

    // ---------------------------------------------------------------- 服务端

    private static int servingPort;

    public static File serverLog(Context ctx) {
        return new File(ctx.getFilesDir(), LOG_NAME);
    }

    /**
     * 起服务端并等到真的能连上。失败抛 IOException，消息可直接显示给用户。
     *
     * 三个实测要点：
     * 1) 旧版把 musl 静态二进制直接 exec 会 SIGSYS(159) 秒退 —— 那是 musl 用了
     *    app seccomp 白名单外的 syscall，换动态链接 bionic 版后消失。
     * 2) 仍走 "sh -c '(...) &'"：子进程要脱离本进程，且退出码要落到日志里。
     * 3) 进程脱离后 netstat / pidof 在 app 域被 SELinux 拒（Cannot bind netlink
     *    socket），拿不到监听状态；改用 TCP 连本机端口探活，只需网络权限。
     */
    public static synchronized void startServer(Context ctx, int port) throws IOException {
        File bin = Bin.iperf3(ctx);
        stopServer(ctx);
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        File log = serverLog(ctx);
        if (log.exists() && !log.delete()) {
            // 删不掉不影响启动，只是日志会追加
        }
        // 用括号把 iperf3 包起来，退出时把退出码写进同一个日志：
        // 直接 & 起的话子进程死了我们什么都看不到。
        // --forceflush 必须加：服务端 stdout 重定向到文件时默认整块缓冲，
        // 实测 60 秒测试的 52 行会攒到最后一次性落盘，界面上就是「跑完才出现」。
        String cmd = "( '" + bin.getAbsolutePath() + "' -s --forceflush -p " + port
                + " >> '" + log.getAbsolutePath() + "' 2>&1; echo EXIT=$? >> '"
                + log.getAbsolutePath() + "' ) >/dev/null 2>&1 &";
        try {
            Shell.launch(new String[]{"sh", "-c", cmd}, Bin.env(ctx));
        } catch (IOException e) {
            throw new IOException("启动服务端失败：" + e.getMessage());
        }

        for (int i = 0; i < 60; i++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (isListening(port)) {
                servingPort = port;
                return;
            }
        }
        String tail = readText(log);
        stopServer(ctx);
        throw new IOException("服务端在 15 秒内没有监听端口 " + port
                + (tail.isEmpty() ? "" : "；日志末尾：" + tail));
    }

    public static synchronized void stopServer(Context ctx) {
        // 服务端是脱离的子进程，只能按名字杀；pkill -f 匹配整条命令行，
        // "iperf3" 能命中 ".../libiperf3.so -s -p 5201"
        Shell.kill("iperf3");
        servingPort = 0;
    }

    /** 服务端是否还在监听。 */
    public static boolean serverAlive() {
        return servingPort > 0 && isListening(servingPort);
    }

    /** 用 TCP 连接探活，替代 netstat。 */
    public static boolean isListening(int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String pipedServerLog(Context ctx) {
        return readText(serverLog(ctx));
    }

    /**
     * 从 offset 起读服务端日志里新增的完整行，返回新的 offset。
     * 日志被重建（长度变小）时从 0 重读。
     *
     * 服务端是脱离的子进程，逐秒输出只落到这个文件里，界面想实时看就只能轮询它。
     * 尾部半行不能吐出去：下一次读取会连同后半截一起拿到，否则界面会打出半行字。
     */
    public static synchronized long tailServerLog(Context ctx, long offset, java.util.List<String> out) {
        File f = serverLog(ctx);
        if (!f.exists()) {
            return offset;
        }
        long len = f.length();
        if (len < offset) {
            // startServer 会删旧日志重建，长度变小说明换了一轮，从头再来
            offset = 0;
        }
        if (len <= offset) {
            return offset;
        }
        byte[] buf = new byte[(int) (len - offset)];
        int got = 0;
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                raf.seek(offset);
                while (got < buf.length) {
                    int n = raf.read(buf, got, buf.length - got);
                    if (n <= 0) {
                        break;
                    }
                    got += n;
                }
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            // 读日志失败绝不能影响服务端本身
            return offset;
        }
        if (got <= 0) {
            return offset;
        }
        // 按原始字节找最后一个换行：用 String 长度反推字节数在多字节字符上不准
        int last = -1;
        for (int i = got - 1; i >= 0; i--) {
            if (buf[i] == 0x0A) {
                last = i;
                break;
            }
        }
        if (last < 0) {
            // 还没凑够一整行，原样等下一次
            return offset;
        }
        String text = new String(buf, 0, last + 1, StandardCharsets.UTF_8);
        for (String raw : text.split("\n")) {
            String s = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return offset + last + 1;
    }

    static String readText(File f) {
        if (f == null || !f.exists()) {
            return "";
        }
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n <= 0) {
                        break;
                    }
                    off += n;
                }
            } finally {
                in.close();
            }
            return new String(b, "UTF-8").trim();
        } catch (IOException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 客户端

    public static List<String> clientArgv(Context ctx, String host, int port, String kind,
                                          double duration, int streams, int interval,
                                          boolean reverse,
                                          String udpBw) throws IOException {
        List<String> a = new ArrayList<String>();
        a.add(Bin.iperf3(ctx).getAbsolutePath());
        a.add("-c");
        a.add(host);
        a.add("-p");
        a.add(String.valueOf(port));
        a.add("-t");
        a.add(String.valueOf((int) Math.rint(duration)));
        a.add("-P");
        a.add(String.valueOf(streams));
        a.add("-i");
        a.add(String.valueOf(interval));
        if (reverse) {
            a.add("-R");
        }
        if ("UDP".equalsIgnoreCase(kind)) {
            a.add("-u");
            a.add("-b");
            a.add(udpBw == null || udpBw.isEmpty() ? "50M" : udpBw);
        }
        a.add("--forceflush");
        // 正向打流时本机只拿到发送端数字，接收端速率要服务端上报才看得见
        a.add("--get-server-output");
        return a;
    }

    /**
     * 跑一次客户端。逐行回调 onRecord（可为 null，回调在后台线程）。
     * 成败判定靠输出里有没有 "iperf Done."，不看退出码 —— Android 上管道会把退出码吃掉。
     */
    public static Result runClient(Context ctx, final String testId, final String kind,
                                   String host, int port, double duration, int streams,
                                   int interval, boolean reverse, String udpBw,
                                   final OnRecord onRecord) {
        final Result res = new Result();
        res.testId = testId;
        res.kind = kind;
        res.reverse = reverse;
        res.duration = duration;
        final long t0 = System.currentTimeMillis();
        final StringBuilder raw = new StringBuilder();
        try {
            List<String> argv = clientArgv(ctx, host, port, kind, duration, streams,
                    interval, reverse, udpBw);
            final String dir = reverse ? "receiver" : "sender";
            Shell.run(argv, Bin.env(ctx), new Shell.LineSink() {
                private boolean serverPart;

                @Override
                public void onLine(String line) {
                    raw.append(line).append('\n');
                    if (line.trim().startsWith("Server output:")) {
                        serverPart = true;
                        return;
                    }
                    Record r = Record.of(testId, kind, null, line, clock());
                    if (r == null) {
                        return;
                    }
                    if (serverPart) {
                        // 服务端那半段没有角色标记：正向时服务端在收（receiver），
                        // 反向 -R 时服务端在发（sender），所以兜底角色要跟着 reverse 变
                        r.role = roleOf(r, reverse ? "sender" : "receiver");
                        res.serverRecords.add(r);
                        return;
                    }
                    r.role = roleOf(r, dir);
                    res.records.add(r);
                    if (onRecord != null) {
                        onRecord.onRecord(r);
                    }
                }
            });
        } catch (IOException e) {
            res.ok = false;
            res.error = "启动 iperf3 失败：" + e.getMessage();
            res.rawText = raw.toString();
            res.endedAt = System.currentTimeMillis();
            return res;
        }
        res.rawText = raw.toString();
        res.endedAt = System.currentTimeMillis();
        res.ok = raw.indexOf(DONE_MARK) >= 0;

        // 方向：本机是客户端。正向（不 -R）本机发送 ⇒ 本机行是 sender；
        // 反向（-R）本机接收 ⇒ 本机行是 receiver。iperf3 自己打的 role 更可信，优先用。
        summarize(res);

        if (!res.ok) {
            res.error = firstError(raw.toString());
            if (res.error.isEmpty()) {
                res.error = "iperf3 没有打印 \"" + DONE_MARK + "\"，本次结果不可信";
            }
        } else if (res.records.isEmpty()) {
            res.ok = false;
            res.error = "iperf3 跑完了但没有解析出任何数据行";
        }
        if (res.endedAt - t0 < 500 && !res.ok) {
            // 立刻失败通常是服务端没起来
            if (res.error.isEmpty()) {
                res.error = "iperf3 立即退出，服务端可能没在监听";
            }
        }
        return res;
    }

    static String roleOf(Record r, String fallback) {
        String role = r.role == null ? "" : r.role;
        if (role.isEmpty() || role.startsWith("-")) {
            return fallback;
        }
        return role;
    }

    static void summarize(Result res) {
        // 汇总行 = 区间覆盖整段的那几行。有 [SUM] 就用 SUM，否则退到最后一条带角色的流。
        Record sumSender = null;
        Record sumRecv = null;
        Record lastSender = null;
        Record lastRecv = null;
        List<Record> all = new ArrayList<Record>(res.records);
        all.addAll(res.serverRecords);
        for (Record r : all) {
            boolean isSum = "SUM".equals(r.stream);
            boolean sender = "sender".equals(r.role);
            if (isSum && sender) {
                sumSender = r;
            } else if (isSum && !sender) {
                sumRecv = r;
            }
            if (sender) {
                lastSender = r;
            } else {
                lastRecv = r;
            }
        }
        Record s = sumSender != null ? sumSender : lastSender;
        Record c = sumRecv != null ? sumRecv : lastRecv;
        if (s != null) {
            res.senderRate = s.prettyRate();
        }
        if (c != null) {
            res.receiverRate = c.prettyRate();
            StringBuilder sb = new StringBuilder();
            if (c.jitter.length() > 0) {
                sb.append("jitter ").append(c.jitter).append(" ms");
            }
            if (c.lost.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("  ");
                }
                sb.append("丢包 ").append(c.lost).append('/').append(c.total);
                if (c.lostPct.length() > 0) {
                    sb.append(" (").append(c.lostPct).append("%)");
                }
            }
            if (c.retr.length() > 0 && !"0".equals(c.retr)) {
                if (sb.length() > 0) {
                    sb.append("  ");
                }
                sb.append("重传 ").append(c.retr);
            }
            res.receiverDetail = sb.toString();
        }
    }

    static String firstError(String text) {
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.startsWith("iperf3: error") || s.startsWith("iperf3: Error")) {
                return s;
            }
        }
        return "";
    }

    static String clock() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    /** 跑 iperf3 需要裸 PATH + LD_LIBRARY_PATH，统一从 Bin 取。 */
    public static Map<String, String> envFor(Context ctx) {
        return Bin.env(ctx);
    }
}
