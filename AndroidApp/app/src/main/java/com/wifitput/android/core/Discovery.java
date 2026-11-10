package com.wifitput.android.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网内的对端发现。服务端用一个 UDP 端口应答，客户端广播询问。
 * 只在同一个局域网内有效（同一热点 / 同一 WiFi / P2P 组），不需要任何权限。
 */
public class Discovery {

    public static final int PORT = 8738;
    public static final String ASK = "WIFITPUT/DISCOVER/1";
    public static final String HERE = "WIFITPUT/HERE/1";

    /** 一个应答过来的对端。 */
    public static class Peer {
        public String ip = "";
        public String label = "";
        public int port;
        /** 对端 iperf3 版本（老对端为空）。 */
        public String version = "";
        /** 对端的结果接收 HTTP 端口，0 表示不接收。 */
        public int httpPort;
        /** "PC" / "PHONE"；老对端按 PHONE 处理。 */
        public String kind = "PHONE";

        public String text() {
            return label + "  " + ip + ":" + port
                    + (version == null || version.isEmpty() ? "" : "  " + version);
        }

        public boolean isPc() {
            return "PC".equalsIgnoreCase(kind);
        }
    }

    private static volatile boolean responding;
    private static DatagramSocket sock;
    private static Thread thread;

    /** 手机端应答：额外带上 iperf3 版本和 PHONE 类型。 */
    public static synchronized void startResponder(String label, int port) {
        startResponder(label, port, "", 0, "PHONE");
    }

    /**
     * 开始应答广播询问。label 显示给对方看，port 是 iperf3 服务端口；
     * version/httpPort/kind 供「连PC」和 PC 工具识别。
     */
    public static synchronized void startResponder(final String label, final int port,
                                                   final String version, final int httpPort,
                                                   final String kind) {
        stopResponder();
        responding = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket s = null;
                try {
                    s = new DatagramSocket(null);
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(PORT));
                    s.setSoTimeout(800);
                    sock = s;
                    byte[] buf = new byte[512];
                    while (responding) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            s.receive(p);
                        } catch (SocketTimeoutException e) {
                            continue;
                        } catch (IOException e) {
                            break;
                        }
                        String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                        if (!ASK.equals(msg)) {
                            continue;
                        }
                        byte[] out = (HERE + "|" + safe(label) + "|" + port
                                + "|" + safe(version) + "|" + Math.max(0, httpPort)
                                + "|" + safe(kind)).getBytes("UTF-8");
                        try {
                            s.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                        } catch (IOException ignored) {
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (s != null) {
                        s.close();
                    }
                    sock = null;
                }
            }
        }, "discovery-responder");
        t.setDaemon(true);
        t.start();
        thread = t;
    }

    public static synchronized void stopResponder() {
        responding = false;
        DatagramSocket s = sock;
        sock = null;
        if (s != null) {
            s.close();
        }
        thread = null;
    }

    public static boolean isResponding() {
        return responding;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\n", " ").trim();
    }

    /** 广播询问，收集 waitMs 内的应答。同一个 IP 只留一条。 */
    public static List<Peer> scan(int waitMs) {
        List<Peer> out = new ArrayList<Peer>();
        Map<String, Peer> seen = new LinkedHashMap<String, Peer>();
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] ask = ASK.getBytes("UTF-8");
            for (InetAddress a : targets()) {
                try {
                    s.send(new DatagramPacket(ask, ask.length, a, PORT));
                } catch (IOException ignored) {
                }
            }
            long deadline = System.currentTimeMillis() + Math.max(600, waitMs);
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                if (!msg.startsWith(HERE)) {
                    continue;
                }
                String[] parts = (msg + "|||||").split("\\|");
                Peer peer = new Peer();
                peer.label = parts.length > 1 ? parts[1] : "对端";
                peer.port = 5201;
                try {
                    peer.port = Integer.parseInt(parts.length > 2 ? parts[2].trim() : "5201");
                } catch (NumberFormatException ignored) {
                }
                peer.version = parts.length > 3 ? parts[3].trim() : "";
                try {
                    peer.httpPort = parts.length > 4 ? Integer.parseInt(parts[4].trim()) : 0;
                } catch (NumberFormatException ignored) {
                    peer.httpPort = 0;
                }
                peer.kind = parts.length > 5 && !parts[5].trim().isEmpty()
                        ? parts[5].trim() : "PHONE";
                peer.ip = p.getAddress().getHostAddress();
                if (!seen.containsKey(peer.ip)) {
                    seen.put(peer.ip, peer);
                    out.add(peer);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) {
                s.close();
            }
        }
        return out;
    }

    /** 有限广播地址 + 各网卡的定向广播地址。 */
    static List<InetAddress> targets() {
        List<InetAddress> list = new ArrayList<InetAddress>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        for (String s : Dev.addrs()) {
            String ip = s;
            int mask = 24;
            if (s.contains("/")) {
                ip = s.substring(0, s.indexOf('/'));
                try {
                    mask = Integer.parseInt(s.substring(s.indexOf('/') + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (ip.startsWith("127.") || ip.indexOf(':') >= 0 || mask < 1 || mask > 30) {
                continue;
            }
            try {
                byte[] b = InetAddress.getByName(ip).getAddress();
                int bits = 0xFFFFFFFF << (32 - mask);
                int addr = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                int bc = addr | ~bits;
                byte[] o = new byte[]{(byte) (bc >>> 24), (byte) (bc >>> 16), (byte) (bc >>> 8), (byte) bc};
                InetAddress a = InetAddress.getByAddress(o);
                if (!list.contains(a)) {
                    list.add(a);
                }
            } catch (Exception ignored) {
            }
        }
        return list;
    }
}
