package com.wifitput.android.core;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 对端地址二维码的编解码封装（基于 zxing）。
 *
 * <p>这个二维码只承载对端的 IP 和端口，不含任何密码、密钥或会话令牌，
 * 因此即使被旁观者拍到，也不会泄露认证信息；真正的鉴权仍由连接阶段完成。</p>
 *
 * <p>解析时只接受点分十进制 IPv4，主机名与其他非 IPv4 字面量一律拒绝。</p>
 *
 * <p>可选字段 mac 用于 P2P 投屏直连：它只是 Wi-Fi Direct 设备地址，
 * 不含密码或密钥，扫码方拿到它才能直接 connect 建立群组；
 * 群组本身的加密口令由系统协商，从不进入二维码。</p>
 */
public class Qr {

    /** 二维码文本前缀，形如 wifitput://peer?ip=10.0.0.2&port=5201 */
    public static final String PREFIX = "wifitput://peer?";

    /** 未指定端口时使用的默认端口。 */
    private static final int DEFAULT_PORT = 5201;

    /** 未指定边长时使用的默认像素边长。 */
    private static final int DEFAULT_PX = 512;

    /**
     * Android 12+ 对普通 App 会把本机 WifiP2pDevice.deviceAddress 打码成这个值，
     * 拿它去 connect 一定失败，所以解析时等同于「这张码里没有可直连的地址」。
     */
    private static final String MASKED_MAC = "02:00:00:00:00:00";

    /** 二维码承载的对端信息；mac 为空表示这只是一张普通地址码，不能直连投屏。 */
    public static class Peer {
        public String ip = "";
        public int port = 5201;
        public String mac = "";   // Wi-Fi Direct 设备地址，形如 76:ab:f3:b4:37:b6
        public String name = "";  // 对端设备名，可为空

        /** 是否带 P2P 设备地址（能直接发起 Wi-Fi Direct 连接）。 */
        public boolean cast() {
            return mac.length() > 0;
        }
    }

    /**
     * 生成对端地址的二维码文本。
     *
     * @param ip   对端 IP，null 当作空串，首尾空白会被去掉
     * @param port 对端端口
     * @return 形如 wifitput://peer?ip=10.0.0.2&port=5201 的文本
     */
    public static String encode(String ip, int port) {
        String host = ip == null ? "" : ip.trim();
        return PREFIX + "ip=" + host + "&port=" + port;
    }

    /**
     * 生成投屏用的二维码文本，比 {@link #encode(String, int)} 多带 P2P 设备地址与设备名。
     *
     * <p>设备名可能与端口参数里的字符冲突，所以必须 URL 编码；
     * mac 为空时不写 mac 键，让旧解析器/旧码保持兼容。</p>
     *
     * @param ip   本机在 P2P 群组里的 IP
     * @param port iperf 端口
     * @param mac  本机 Wi-Fi Direct 设备地址，可为空
     * @param name 本机设备名，可为空
     * @return 形如 wifitput://peer?ip=192.168.49.1&port=5201&mac=76:ab:f3:b4:37:b6&name=Infinix+X6878 的文本
     *         （URLEncoder 把空格编码成 +，parsePeer 的 URLDecoder 会还原）
     */
    public static String encodeCast(String ip, int port, String mac, String name) {
        String host = ip == null ? "" : ip.trim();
        String addr = mac == null ? "" : mac.trim();
        String label = name == null ? "" : name.trim();

        StringBuilder sb = new StringBuilder(80);
        sb.append(PREFIX).append("ip=").append(host).append("&port=").append(port);
        if (addr.length() > 0) {
            sb.append("&mac=").append(addr);
        }
        if (label.length() > 0) {
            String enc = label;
            try {
                enc = URLEncoder.encode(label, "UTF-8");
            } catch (Exception ignored) {
            }
            sb.append("&name=").append(enc);
        }
        return sb.toString();
    }

    /**
     * 解析扫描结果。
     *
     * <p>容忍首尾空白、多余的 URL 参数，以及裸的 10.0.0.2:5201 / 10.0.0.2 两种写法。</p>
     *
     * @param text 扫描到的原始文本
     * @return 成功返回 {ip, port}，失败返回 null
     */
    public static String[] parse(String text) {
        Peer p = parsePeer(text);
        if (p == null) {
            return null;
        }
        return new String[]{p.ip, String.valueOf(p.port)};
    }

    /**
     * 解析扫描结果，保留 mac/name 等投屏字段。
     *
     * <p>容错与 {@link #parse(String)} 一致；mac 非法时只丢掉 mac（仍可当普通地址码用），
     * 而不是整张码作废。</p>
     *
     * @param text 扫描到的原始文本
     * @return 成功返回 Peer，失败返回 null
     */
    public static Qr.Peer parsePeer(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        if (raw.isEmpty()) {
            return null;
        }

        String ip = null;
        int port = DEFAULT_PORT;
        String mac = "";
        String name = "";

        if (raw.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            String body = raw.substring(PREFIX.length());
            String[] parts = body.split("&");
            for (String part : parts) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = part.substring(0, eq).trim();
                String value = decode(part.substring(eq + 1));
                if ("ip".equalsIgnoreCase(key)) {
                    ip = value.trim();
                } else if ("port".equalsIgnoreCase(key)) {
                    port = toPort(value.trim(), DEFAULT_PORT);
                } else if ("mac".equalsIgnoreCase(key)) {
                    mac = value.trim();
                } else if ("name".equalsIgnoreCase(key)) {
                    name = value.trim();
                }
            }
        } else {
            int colon = raw.lastIndexOf(':');
            if (colon > 0) {
                ip = raw.substring(0, colon).trim();
                port = toPort(raw.substring(colon + 1).trim(), DEFAULT_PORT);
            } else {
                ip = raw;
            }
        }

        if (ip == null || ip.isEmpty()) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        if (!isIpv4(ip)) {
            return null;
        }

        Peer peer = new Peer();
        peer.ip = ip;
        peer.port = port;
        peer.mac = isMac(mac) ? mac : "";
        if (MASKED_MAC.equalsIgnoreCase(peer.mac)) {
            peer.mac = "";
        }
        peer.name = name;
        return peer;
    }

    /** 是否是合法的点分十进制 IPv4；不接受前导零与越界段。 */
    private static boolean isIpv4(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            int n = p.length();
            if (n < 1 || n > 3) {
                return false;
            }
            if (n > 1 && p.charAt(0) == '0') {
                return false;
            }
            for (int i = 0; i < n; i++) {
                char ch = p.charAt(i);
                if (ch < '0' || ch > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    /** 是否是 xx:xx:xx:xx:xx:xx 形式的 Wi-Fi Direct 设备地址，大小写都接受。 */
    private static boolean isMac(String s) {
        if (s == null || s.length() != 17) {
            return false;
        }
        for (int i = 0; i < 17; i++) {
            char ch = s.charAt(i);
            if (i % 3 == 2) {
                if (ch != ':') {
                    return false;
                }
            } else if (!isHex(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }

    /**
     * 生成二维码位图。
     *
     * @param text 要编码的文本
     * @param px   边长像素，小于等于 0 时按 512 处理
     * @return 位图，失败返回 null
     */
    public static Bitmap bitmap(String text, int px) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int size = px <= 0 ? DEFAULT_PX : px;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** 端口解析，失败回落到 fallback。 */
    private static int toPort(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** URL 解码，失败时返回原值。 */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
