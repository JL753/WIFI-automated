package com.wifitput.android.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 连PC 的空闲探测。优先 UDP 8738 的 PING/PONG（不受 Windows 防火墙 ICMP 拦截影响）；
 * UDP 全丢时退回 TCP 握手到 iperf3 端口，并如实标注 method=tcp。
 */
public class PcPing {

    public static class Result {
        public String method = "udp";
        public int probes;
        public int lost;
        public double lossPct;
        public double avgMs = -1;
        public double minMs = -1;
        public double maxMs = -1;

        public boolean ok() {
            return probes > 0 && lost < probes;
        }
    }

    public static Result probe(String host, int udpPort, int iperfPort, int count, int timeoutMs) {
        Result r = probeUdp(host, udpPort, count, timeoutMs);
        if (r != null && r.ok()) {
            return r;
        }
        Result tcp = probeTcp(host, iperfPort, Math.min(3, Math.max(1, count)), timeoutMs);
        if (tcp != null && tcp.ok()) {
            return tcp;
        }
        return r != null ? r : (tcp != null ? tcp : new Result());
    }

    private static Result probeUdp(String host, int port, int count, int timeoutMs) {
        Result r = new Result();
        r.method = "udp";
        r.probes = Math.max(1, Math.min(count, 50));
        if (host == null || host.trim().isEmpty()) {
            r.lost = r.probes;
            r.lossPct = 100;
            return r;
        }
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(Math.max(200, timeoutMs));
            InetAddress addr = InetAddress.getByName(host.trim());
            List<Double> rtts = new ArrayList<Double>();
            byte[] buf = new byte[512];
            for (int i = 0; i < r.probes; i++) {
                String nonce = Long.toHexString(System.nanoTime());
                byte[] out = ("WIFITPUT/PING/1|" + nonce).getBytes("UTF-8");
                long t0 = System.nanoTime();
                try {
                    s.send(new DatagramPacket(out, out.length, addr, port));
                    long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
                    boolean got = false;
                    while (System.currentTimeMillis() < deadline) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                        if (msg.startsWith("WIFITPUT/PONG/1") && msg.endsWith(nonce)) {
                            got = true;
                            break;
                        }
                    }
                    if (got) {
                        rtts.add((System.nanoTime() - t0) / 1e6);
                    } else {
                        r.lost++;
                    }
                } catch (java.net.SocketTimeoutException e) {
                    r.lost++;
                }
                if (i + 1 < r.probes) {
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            fill(r, rtts);
            return r;
        } catch (Exception e) {
            r.lost = r.probes;
            r.lossPct = 100;
            return r;
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    private static Result probeTcp(String host, int port, int count, int timeoutMs) {
        Result r = new Result();
        r.method = "tcp";
        r.probes = Math.max(1, count);
        if (host == null || host.trim().isEmpty() || port <= 0) {
            r.lost = r.probes;
            r.lossPct = 100;
            return r;
        }
        List<Double> rtts = new ArrayList<Double>();
        for (int i = 0; i < r.probes; i++) {
            long t0 = System.nanoTime();
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host.trim(), port), Math.max(300, timeoutMs));
                rtts.add((System.nanoTime() - t0) / 1e6);
            } catch (Exception e) {
                r.lost++;
            } finally {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
        fill(r, rtts);
        return r;
    }

    private static void fill(Result r, List<Double> rtts) {
        if (rtts.isEmpty()) {
            r.lossPct = 100;
            return;
        }
        double sum = 0;
        double min = rtts.get(0);
        double max = rtts.get(0);
        for (double v : rtts) {
            sum += v;
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        r.avgMs = sum / rtts.size();
        r.minMs = min;
        r.maxMs = max;
        r.lossPct = r.probes <= 0 ? 100 : r.lost * 100.0 / r.probes;
    }

    public static String fmt(double v, int digits) {
        if (v < 0) {
            return "--";
        }
        return String.format(Locale.US, "%." + digits + "f", v);
    }
}
