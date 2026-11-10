package com.wifitput.android.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 测试期间的链路/系统遥测：WiFi 信号、协商速率、CPU、断流。
 * MCS/SNR/频宽在普通 Android 上没有公开 API，汇总里如实置 null。
 */
public class Telemetry {

    public static class Sample {
        public double t;
        public boolean wifi;
        public int rssi;
        public int linkTx;
        public int freq;
        public String band = "";
        public int channel;
        public String standard = "";
        public double cpuPct = -1;
        public double appCpuPct = -1;
    }

    private final Context ctx;
    private final int intervalMs;
    private final List<Sample> samples = new ArrayList<Sample>();
    private final long t0 = System.currentTimeMillis();
    private volatile boolean running;
    private Thread thread;
    private boolean lastWifi = true;
    private int disconnects;
    private long lastCpuTotal = -1;
    private long lastCpuIdle = -1;
    private long lastAppJiffies = -1;
    private long lastSampleAt = -1;

    public Telemetry(Context c, int intervalSeconds) {
        ctx = c.getApplicationContext();
        intervalMs = Math.max(1, intervalSeconds) * 1000;
    }

    public void start() {
        synchronized (samples) {
            samples.clear();
        }
        running = true;
        lastWifi = true;
        disconnects = 0;
        lastCpuTotal = -1;
        lastCpuIdle = -1;
        lastAppJiffies = -1;
        readCpuBaseline();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        sample();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "pc-telemetry");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sample() {
        Sample s = new Sample();
        s.t = (System.currentTimeMillis() - t0) / 1000.0;
        JSONObject w = null;
        try {
            w = Dev.wifi(ctx);
        } catch (Throwable ignored) {
        }
        if (w != null) {
            s.wifi = w.optBoolean("connected", false);
            s.rssi = w.optInt("rssi", 0);
            s.linkTx = w.optInt("link_tx_mbps", 0);
            s.freq = w.optInt("freq_mhz", 0);
            s.band = w.optString("band", "");
            s.channel = w.optInt("channel", 0);
            s.standard = w.optString("standard", "");
        }
        if (lastWifi && !s.wifi) {
            disconnects++;
        }
        lastWifi = s.wifi;
        readCpu(s);
        synchronized (samples) {
            if (samples.size() < 200000) {
                samples.add(s);
            }
        }
    }

    private void readCpuBaseline() {
        long[] st = readProcStat();
        long[] app = readAppJiffies();
        if (st != null) {
            lastCpuTotal = st[0];
            lastCpuIdle = st[1];
        }
        if (app != null) {
            lastAppJiffies = app[0];
        }
        lastSampleAt = System.currentTimeMillis();
    }

    private void readCpu(Sample s) {
        long now = System.currentTimeMillis();
        long[] st = readProcStat();
        long[] app = readAppJiffies();
        if (st != null && lastCpuTotal >= 0) {
            long dt = st[0] - lastCpuTotal;
            long di = st[1] - lastCpuIdle;
            if (dt > 0) {
                s.cpuPct = Math.max(0, Math.min(100, (dt - di) * 100.0 / dt));
                if (app != null && lastAppJiffies >= 0) {
                    long da = app[0] - lastAppJiffies;
                    s.appCpuPct = Math.max(0, Math.min(100, da * 100.0 / dt));
                }
            }
            lastCpuTotal = st[0];
            lastCpuIdle = st[1];
        }
        if (app != null) {
            lastAppJiffies = app[0];
        }
        if (lastSampleAt > 0 && now <= lastSampleAt) {
            // 时间没走，不算
        }
        lastSampleAt = now;
    }

    /** 返回 {total, idle}（jiffies）。 */
    private static long[] readProcStat() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/stat"));
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu ")) {
                return null;
            }
            String[] p = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < p.length && i <= 8; i++) {
                total += Long.parseLong(p[i]);
            }
            long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
            return new long[]{total, idle};
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 本进程 utime+stime（jiffies），取不到返回 null。 */
    private static long[] readAppJiffies() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/stat"));
            String line = r.readLine();
            if (line == null) {
                return null;
            }
            int close = line.lastIndexOf(')');
            if (close < 0 || close + 2 >= line.length()) {
                return null;
            }
            String[] p = line.substring(close + 2).trim().split("\\s+");
            // 剩余字段从 state 开始：state 是第 1 个，utime 是第 12 个，stime 第 13 个
            if (p.length < 13) {
                return null;
            }
            long u = Long.parseLong(p[11]);
            long s = Long.parseLong(p[12]);
            return new long[]{u + s};
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public JSONArray toJson() {
        JSONArray arr = new JSONArray();
        synchronized (samples) {
            for (Sample s : samples) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("t", round(s.t, 1));
                    o.put("wifi_connected", s.wifi);
                    o.put("rssi", s.rssi);
                    o.put("link_tx_mbps", s.linkTx);
                    o.put("freq_mhz", s.freq);
                    o.put("band", s.band);
                    o.put("channel", s.channel);
                    o.put("standard", s.standard);
                    if (s.cpuPct >= 0) {
                        o.put("cpu_pct", round(s.cpuPct, 1));
                    }
                    if (s.appCpuPct >= 0) {
                        o.put("app_cpu_pct", round(s.appCpuPct, 1));
                    }
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        return arr;
    }

    public JSONObject summary() {
        JSONObject o = new JSONObject();
        List<Sample> copy;
        synchronized (samples) {
            copy = new ArrayList<Sample>(samples);
        }
        double rssiSum = 0;
        int rssiN = 0;
        int rssiMin = 0;
        double cpuSum = 0;
        double cpuMax = 0;
        int cpuN = 0;
        double appSum = 0;
        int appN = 0;
        for (Sample s : copy) {
            if (s.wifi && s.rssi != 0) {
                if (rssiN == 0 || s.rssi < rssiMin) {
                    rssiMin = s.rssi;
                }
                rssiSum += s.rssi;
                rssiN++;
            }
            if (s.cpuPct >= 0) {
                cpuSum += s.cpuPct;
                cpuMax = Math.max(cpuMax, s.cpuPct);
                cpuN++;
            }
            if (s.appCpuPct >= 0) {
                appSum += s.appCpuPct;
                appN++;
            }
        }
        try {
            o.put("samples", copy.size());
            o.put("rssi_avg", rssiN > 0 ? round(rssiSum / rssiN, 1) : JSONObject.NULL);
            o.put("rssi_min", rssiN > 0 ? rssiMin : JSONObject.NULL);
            o.put("cpu_avg", cpuN > 0 ? round(cpuSum / cpuN, 1) : JSONObject.NULL);
            o.put("cpu_max", cpuN > 0 ? round(cpuMax, 1) : JSONObject.NULL);
            o.put("app_cpu_avg", appN > 0 ? round(appSum / appN, 1) : JSONObject.NULL);
            o.put("disconnects", disconnects);
            o.put("mcs", JSONObject.NULL);
            o.put("snr_db", JSONObject.NULL);
            o.put("channel_width", JSONObject.NULL);
            o.put("note", "MCS/SNR/频宽在 Android 公共 API 不可得；频宽可在设置里手填。");
        } catch (Exception ignored) {
        }
        return o;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    public static String fmt(double v, int digits) {
        if (v < 0) {
            return "--";
        }
        return String.format(Locale.US, "%." + digits + "f", v);
    }
}
