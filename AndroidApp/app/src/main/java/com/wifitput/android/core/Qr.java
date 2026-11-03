package com.wifitput.android.core;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 对端地址二维码的编解码封装（基于 zxing）。
 *
 * <p>这个二维码只承载对端的 IP 和端口，不含任何密码、密钥或会话令牌，
 * 因此即使被旁观者拍到，也不会泄露认证信息；真正的鉴权仍由连接阶段完成。</p>
 *
 * <p>解析时只接受点分十进制 IPv4，主机名与其他非 IPv4 字面量一律拒绝。</p>
 */
public class Qr {

    /** 二维码文本前缀，形如 wifitput://peer?ip=10.0.0.2&port=5201 */
    public static final String PREFIX = "wifitput://peer?";

    /** 未指定端口时使用的默认端口。 */
    private static final int DEFAULT_PORT = 5201;

    /** 未指定边长时使用的默认像素边长。 */
    private static final int DEFAULT_PX = 512;

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
     * 解析扫描结果。
     *
     * <p>容忍首尾空白、多余的 URL 参数，以及裸的 10.0.0.2:5201 / 10.0.0.2 两种写法。</p>
     *
     * @param text 扫描到的原始文本
     * @return 成功返回 {ip, port}，失败返回 null
     */
    public static String[] parse(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        if (raw.isEmpty()) {
            return null;
        }

        String ip = null;
        int port = DEFAULT_PORT;

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
        return new String[]{ip, String.valueOf(port)};
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
