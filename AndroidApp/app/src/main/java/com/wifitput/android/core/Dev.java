package com.wifitput.android.core;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 采集设备与链路信息，产出与 PC 端 DeviceInfo 同构的 JSON。
 *
 * 注意：普通应用进程里 dumpsys / ip / netstat / /proc/uptime 全部被 SELinux 拒绝
 * （实测 Cannot bind netlink socket: Permission denied），所以这里一律走 Java API，
 * 只有 getprop 这类只读命令才用 Shell。
 */
public class Dev {

    public static String serial(Context ctx) {
        String s = Build.SERIAL;
        if (s == null || s.isEmpty() || "unknown".equals(s)) {
            try {
                String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (id != null && id.length() >= 8) {
                    s = Build.MODEL + "-" + id.substring(0, 8);
                }
            } catch (Exception ignored) {
            }
        }
        if (s == null || s.isEmpty() || "unknown".equals(s)) {
            s = Build.MODEL + "-" + Long.toHexString(Build.FINGERPRINT.hashCode() & 0xffffffffL);
        }
        return s;
    }

    public static JSONObject collect(Context ctx) throws JSONException {
        JSONObject d = new JSONObject();
        d.put("serial", serial(ctx));
        d.put("state", "device");
        d.put("model", nz(Build.MODEL));
        d.put("brand", nz(Build.BRAND));
        d.put("device", nz(Build.DEVICE));
        d.put("android", nz(Build.VERSION.RELEASE));
        d.put("sdk", nz(Build.VERSION.SDK));
        d.put("abi", nz(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : ""));
        d.put("hardware", nz(Build.HARDWARE));
        d.put("fingerprint", nz(Build.FINGERPRINT));
        d.put("screen", screenState(ctx));
        d.put("battery", battery(ctx));
        d.put("ram_mb", ramMb(ctx));
        d.put("cores", Runtime.getRuntime().availableProcessors());
        d.put("storage_free_gb", storageFreeGb());
        d.put("uptime_s", uptimeS());
        d.put("wifi", wifi(ctx));
        d.put("hotspot", hotspot(ctx));
        // 形状必须跟 tools\_contract.md 一致，PC 端 report.py 直接按 dict 取键
        d.put("addrs", addrObjs());
        d.put("ifaces", ifaceNames());
        d.put("default_route", defaultRouteObj(ctx));
        d.put("iperf", new JSONObject().put("version", Bin.version(ctx))
                .put("path", safeBinPath(ctx)));
        return d;
    }

    static String safeBinPath(Context ctx) {
        try {
            File f = Bin.iperf3(ctx);
            return f == null ? "" : f.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String screenState(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return "--";
        }
        return pm.isInteractive() ? "Awake" : "Asleep";
    }

    static JSONObject battery(Context ctx) throws JSONException {
        JSONObject b = new JSONObject();
        b.put("level", -1);
        b.put("temp_c", -1);
        try {
            android.content.Intent it = ctx.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (it != null) {
                int level = it.getIntExtra("level", -1);
                int scale = it.getIntExtra("scale", 100);
                int temp = it.getIntExtra("temperature", -1);
                if (scale > 0 && level >= 0) {
                    b.put("level", level * 100 / scale);
                }
                if (temp >= 0) {
                    b.put("temp_c", Math.round(temp / 10.0 * 10) / 10.0);
                }
            }
        } catch (Exception ignored) {
        }
        return b;
    }

    static int ramMb(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return 0;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return (int) (mi.totalMem / (1024 * 1024));
    }

    static double storageFreeGb() {
        try {
            StatFs st = new StatFs(android.os.Environment.getDataDirectory().getAbsolutePath());
            double bytes = (double) st.getAvailableBlocksLong() * (double) st.getBlockSizeLong();
            return Math.round(bytes / (1024.0 * 1024.0 * 1024.0) * 10) / 10.0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** /proc/uptime 在应用进程里读不到，用开机毫秒数代替。 */
    static long uptimeS() {
        return SystemClock.elapsedRealtime() / 1000L;
    }

    static JSONObject wifi(Context ctx) throws JSONException {
        JSONObject w = new JSONObject();
        w.put("connected", false);
        w.put("enabled", false);
        w.put("ssid", "");
        w.put("band", "");
        w.put("channel", 0);
        w.put("freq_mhz", 0);
        w.put("rssi", 0);
        w.put("link_tx_mbps", 0);
        w.put("link_rx_mbps", 0);
        // 契约里这一项叫 standard（tools\_contract.md:114），不是 phy
        w.put("standard", "");
        w.put("bssid", "");
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return w;
            }
            w.put("enabled", wm.isWifiEnabled());
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null || wi.getNetworkId() < 0) {
                return w;
            }
            w.put("connected", true);
            w.put("ssid", cleanSsid(wi.getSSID()));
            w.put("bssid", nz(wi.getBSSID()));
            int freq = wi.getFrequency();
            w.put("freq_mhz", freq);
            w.put("band", bandOf(freq));
            w.put("channel", channelOf(freq));
            w.put("rssi", wi.getRssi());
            int tx = wi.getLinkSpeed();
            w.put("link_tx_mbps", tx);
            w.put("link_rx_mbps", tx);
            if (Build.VERSION.SDK_INT >= 30) {
                w.put("standard", phyOf(wi.getWifiStandard()));
            }
        } catch (Exception ignored) {
        }
        return w;
    }

    static String cleanSsid(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\"", "").trim();
    }

    static String bandOf(int freq) {
        if (freq <= 0) {
            return "";
        }
        if (freq < 3000) {
            return "2.4GHz";
        }
        if (freq < 5925) {
            return "5GHz";
        }
        return "6GHz";
    }

    static int channelOf(int freq) {
        if (freq <= 0) {
            return 0;
        }
        if (freq == 2484) {
            return 14;
        }
        if (freq < 2484) {
            return (freq - 2407) / 5;
        }
        if (freq < 5000) {
            return 0;
        }
        return (freq - 5000) / 5;
    }

    static String phyOf(int std) {
        switch (std) {
            case 1: return "11a";
            case 2: return "11b";
            case 3: return "11g";
            case 4: return "11n";
            case 5: return "11ac";
            case 6: return "11ax";
            case 7: return "11be";
            default: return "";
        }
    }

    /**
     * 热点状态。dumpsys wifi 在应用进程里被拒（实测 Permission Denial），
     * 只能靠已废弃的 isWifiApEnabled 反射，再按网段特征猜。
     */
    static JSONObject hotspot(Context ctx) throws JSONException {
        JSONObject h = new JSONObject();
        boolean on = false;
        try {
            java.lang.reflect.Method m = WifiManager.class.getMethod("isWifiApEnabled");
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            Object v = m.invoke(wm);
            on = v instanceof Boolean && (Boolean) v;
        } catch (Exception ignored) {
        }
        JSONArray a = new JSONArray();
        String iface = "";
        JSONObject route = routeInfo(ctx);
        String dev = route.optString("dev", "");
        String ip = route.optString("ip", "");
        // SoftAp 网段：老机型 192.168.43.x，新机型多在 192.168.x.x / 10.x 的 wlan1 上
        if (!on && !ip.isEmpty() && !ipConnectsToRouter(ctx, dev)) {
            if (ip.startsWith("192.168.43.") || dev.startsWith("wlan1") || dev.startsWith("ap")) {
                on = true;
            }
        }
        if (on) {
            iface = dev.isEmpty() ? "wlan1" : dev;
            if (!ip.isEmpty()) {
                a.put(ip + "/24");
            }
        }
        h.put("on", on);
        h.put("ssid", on ? nz(Build.MODEL) : "");
        h.put("iface", iface);
        // 契约要的是单值 ip（tools\_contract.md:115）；addrs 留给 card() 显示
        h.put("ip", on && !ip.isEmpty() ? ip : "");
        h.put("addrs", a);
        h.put("freq_mhz", 0);
        h.put("clients", 0);
        return h;
    }

    /** 当前默认网络是不是连在路由器（而不是自己开的 SoftAp）上。 */
    static boolean ipConnectsToRouter(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network n = cm.getActiveNetwork();
            if (n == null) {
                return false;
            }
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) {
                return false;
            }
            // 自己开热点时，上网流量走的是蜂窝网
            return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean ipConnectsToRouter(Context ctx, String dev) {
        return ipConnectsToRouter(ctx);
    }

    public static List<String> addrs() {
        List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (ip == null || ip.startsWith("127.")) {
                        continue;
                    }
                    out.add(ip + "/" + prefixOf(ni));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static int prefixOf(NetworkInterface ni) {
        try {
            for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address) {
                    return ia.getNetworkPrefixLength();
                }
            }
        } catch (Exception ignored) {
        }
        return 24;
    }

    /** 契约形状：[{"iface":"wlan0","ip":"10.0.0.2","prefix":24}]。 */
    public static JSONArray addrObjs() {
        JSONArray out = new JSONArray();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (ip == null || ip.startsWith("127.")) {
                        continue;
                    }
                    out.put(new JSONObject().put("iface", ni.getName())
                            .put("ip", ip).put("prefix", prefixOf(ni)));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 契约形状：只放网卡名，[wlan0, wlan1]。 */
    public static JSONArray ifaceNames() {
        JSONArray out = new JSONArray();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        for (String s : ifaces()) {
            String name = s.contains(" ") ? s.substring(0, s.indexOf(' ')) : s;
            if (!name.isEmpty() && seen.add(name)) {
                out.put(name);
            }
        }
        return out;
    }

    /** 契约形状：{"gw":"10.0.0.1","iface":"wlan0"}，取不到就给空串。 */
    public static JSONObject defaultRouteObj(Context ctx) {
        try {
            JSONObject o = routeInfo(ctx);
            return new JSONObject().put("gw", o.optString("gw", ""))
                    .put("iface", o.optString("dev", ""));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static List<String> ifaces() {
        List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(ni.getName() + " " + a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static JSONObject routeInfo(Context ctx) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("dev", "");
        o.put("gw", "");
        o.put("ip", "");
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return o;
            }
            Network n = cm.getActiveNetwork();
            if (n == null) {
                return o;
            }
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null) {
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    o.put("dev", "wlan0");
                } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    o.put("dev", "rmnet");
                } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    o.put("dev", "eth0");
                }
            }
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        o.put("ip", a.getHostAddress());
                        break;
                    }
                }
                for (RouteInfo r : lp.getRoutes()) {
                    if (r.isDefaultRoute() && r.getGateway() != null) {
                        o.put("gw", r.getGateway().getHostAddress());
                        break;
                    }
                }
                if (!lp.getInterfaceName().isEmpty()) {
                    o.put("dev", lp.getInterfaceName());
                }
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public static String defaultRoute(Context ctx) {
        try {
            JSONObject o = routeInfo(ctx);
            String dev = o.optString("dev", "");
            String gw = o.optString("gw", "");
            if (dev.isEmpty()) {
                return "";
            }
            return gw.isEmpty() ? dev : dev + " " + gw;
        } catch (Exception e) {
            return "";
        }
    }

    /** 设备卡的纯文本行，供日志区显示。 */
    public static String card(JSONObject d) {
        try {
            JSONObject w = d.getJSONObject("wifi");
            JSONObject h = d.getJSONObject("hotspot");
            StringBuilder sb = new StringBuilder();
            sb.append("型号 ").append(d.getString("model"))
              .append("  安卓 ").append(d.getString("android"))
              .append(" (SDK ").append(d.getString("sdk")).append(")")
              .append("  ").append(d.getString("abi")).append('\n');
            if (w.getBoolean("connected")) {
                sb.append("WiFi ").append(w.getString("ssid"))
                  .append("  ").append(w.getString("band"))
                  .append(" ch").append(w.getInt("channel"))
                  .append("  RSSI ").append(w.getInt("rssi")).append(" dBm")
                  .append("  速率 ").append(w.getInt("link_tx_mbps")).append(" Mbps")
                  .append("  ").append(w.getString("standard")).append('\n');
            } else {
                sb.append("WiFi 未连接\n");
            }
            sb.append("热点 ").append(h.getBoolean("on") ? "开（按网段推断）" : "关");
            if (h.getBoolean("on")) {
                sb.append("  地址 ").append(h.getJSONArray("addrs").optString(0, "--"));
            }
            sb.append('\n');
            sb.append("内存 ").append(d.getInt("ram_mb")).append("MB")
              .append("  存储 ").append(d.getDouble("storage_free_gb")).append("GB")
              .append("  ").append(d.getInt("cores")).append(" 核")
              .append("  屏幕 ").append(d.getString("screen")).append('\n');
            sb.append("地址");
            JSONArray a = d.getJSONArray("addrs");
            if (a.length() == 0) {
                sb.append(" --");
            }
            for (int i = 0; i < a.length(); i++) {
                sb.append("  ").append(a.getString(i));
            }
            String dr = d.getString("default_route");
            if (!dr.isEmpty()) {
                sb.append("  默认路由 ").append(dr);
            }
            return sb.toString();
        } catch (JSONException e) {
            return "设备信息读取失败：" + e.getMessage();
        }
    }
}
