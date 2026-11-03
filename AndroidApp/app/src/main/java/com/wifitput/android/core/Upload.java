package com.wifitput.android.core;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** 把结果包 POST 给 PC 接收器，并探测本机可用地址。 */
public class Upload {

    public static class Outcome {
        public boolean ok;
        public String message = "";
        public String savedTo = "";
        public int status;
    }

    /** 本机非回环 IPv4 地址。 */
    public static List<String> localIps() {
        List<String> out = new ArrayList<String>();
        for (String s : Dev.addrs()) {
            String ip = s.contains("/") ? s.substring(0, s.indexOf('/')) : s;
            if (!ip.startsWith("127.") && ip.indexOf(':') < 0 && !out.contains(ip)) {
                out.add(ip);
            }
        }
        return out;
    }

    public static Outcome ping(String host, int port) {
        Outcome o = new Outcome();
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + host + ":" + port + "/api/v1/ping");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestMethod("GET");
            o.status = c.getResponseCode();
            String body = read(c);
            if (o.status == 200) {
                o.ok = true;
                o.message = "PC 接收器在线";
            } else {
                o.message = "PC 返回 HTTP " + o.status + "：" + head(body);
            }
        } catch (Exception e) {
            o.ok = false;
            o.message = describe(e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return o;
    }

    public static Outcome post(String host, int port, String json) {
        Outcome o = new Outcome();
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + host + ":" + port + "/api/v1/results");
            byte[] body = json.getBytes("UTF-8");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = c.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                os.close();
            }
            o.status = c.getResponseCode();
            String text = read(c);
            o.ok = o.status == 200;
            if (o.ok) {
                try {
                    JSONObject j = new JSONObject(text);
                    o.savedTo = j.optString("saved_to", "");
                    o.message = o.savedTo.isEmpty() ? "PC 已接收" : "已保存到 " + o.savedTo;
                } catch (JSONException e) {
                    o.message = "PC 已接收";
                }
            } else {
                o.message = "PC 返回 HTTP " + o.status + "：" + head(text);
            }
        } catch (Exception e) {
            o.ok = false;
            o.message = describe(e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return o;
    }

    static String read(HttpURLConnection c) {
        try {
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                return "";
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    static String head(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    static String describe(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return "连接超时 —— 两个设备不在同一个局域网，或 PC 防火墙拦了端口";
        }
        if (e instanceof java.net.ConnectException) {
            return "对方拒绝连接 —— PC 端的接收器没在跑，或端口写错了";
        }
        if (e instanceof UnknownHostException) {
            return "找不到这个地址";
        }
        return "发送失败：" + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage());
    }
}
