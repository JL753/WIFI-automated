package com.wifitput.android.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wi-Fi Direct（P2P）组网封装。
 *
 * 两个 API 在 android.jar 里是 @hide，编译期不可见，只能反射：
 *   WifiP2pManager.deletePersistentGroup(channel, netId, listener)
 *   WifiP2pDevice.statusToString(int)
 * 反射不到时各有本地兜底，不影响组网主流程。
 *
 * WifiP2pManager 的异步回调可能落在 binder 线程，这里统一把 Cb 调用 post 到主线程，
 * 上层可以直接改 UI。
 */
public class WifiDirect {

    public interface Cb {
        void onLine(String tag, String msg);

        void onPeers(List<String[]> peers);

        void onGroup(boolean grouped, boolean isGo, String goIp, String ssid, String pass);
    }

    private final Context ctx;
    private final Cb cb;
    private final Handler ui;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final BroadcastReceiver rec;
    private boolean registered;
    private boolean closed;
    private boolean p2pEnabled;

    public WifiDirect(Context ctx, Cb cb) {
        Context app = ctx;
        if (ctx != null) {
            Context ac = ctx.getApplicationContext();
            app = ac != null ? ac : ctx;
        }
        this.ctx = app;
        this.cb = cb;
        this.ui = new Handler(Looper.getMainLooper());

        WifiP2pManager m = null;
        WifiP2pManager.Channel ch = null;
        if (this.ctx != null) {
            try {
                m = (WifiP2pManager) this.ctx.getSystemService(Context.WIFI_P2P_SERVICE);
            } catch (Exception ignored) {
            }
            if (m != null) {
                try {
                    ch = m.initialize(this.ctx, this.ctx.getMainLooper(), null);
                } catch (Exception e) {
                    line("!!", "初始化 P2P 失败：" + e);
                }
            }
        }
        this.manager = m;
        this.channel = ch;

        this.rec = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                handle(intent);
            }
        };
        if (this.ctx != null && this.manager != null && this.channel != null) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            try {
                this.ctx.registerReceiver(this.rec, f);
                this.registered = true;
            } catch (Exception e) {
                line("!!", "注册 P2P 广播失败：" + e);
            }
        }
    }

    public boolean available() {
        if (ctx == null || manager == null) {
            return false;
        }
        try {
            return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
        } catch (Exception e) {
            return false;
        }
    }

    public void discover() {
        if (!ready()) {
            return;
        }
        try {
            manager.discoverPeers(channel, act("搜索", null));
        } catch (Exception e) {
            line("!!", "搜索失败：" + e);
        }
    }

    public void stopDiscover() {
        if (!ready()) {
            return;
        }
        try {
            manager.stopPeerDiscovery(channel, act("停止搜索", null));
        } catch (Exception e) {
            line("!!", "停止搜索失败：" + e);
        }
    }

    public void createGroup() {
        if (!ready()) {
            return;
        }
        try {
            manager.createGroup(channel, act("创建群组", new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            }));
        } catch (Exception e) {
            line("!!", "创建群组失败：" + e);
        }
    }

    public void connect(String address) {
        if (!ready()) {
            return;
        }
        if (address == null || address.length() == 0) {
            line("!!", "连接失败：设备地址为空");
            return;
        }
        try {
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = address;
            config.wps.setup = WpsInfo.PBC;
            config.groupOwnerIntent = 0;
            manager.connect(channel, config, act("连接", new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            }));
        } catch (Exception e) {
            line("!!", "连接失败：" + e);
        }
    }

    public void removeGroup() {
        if (!ready()) {
            return;
        }
        try {
            manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    deletePersistent(group);
                    try {
                        manager.removeGroup(channel, act("移除群组", new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        }));
                    } catch (Exception e) {
                        line("!!", "移除群组失败：" + e);
                    }
                }
            });
        } catch (Exception e) {
            line("!!", "移除群组失败：" + e);
        }
    }

    public void refresh() {
        if (!ready()) {
            group(false, false, "", "", "");
            return;
        }
        boolean asked = false;
        try {
            manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    groupAndInfo(group);
                }
            });
            asked = true;
        } catch (Exception e) {
            line("!!", "读取群组信息失败：" + e);
        }
        if (!asked) {
            groupAndInfo(null);
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (registered && ctx != null) {
            try {
                ctx.unregisterReceiver(rec);
            } catch (Exception ignored) {
            }
            registered = false;
        }
    }

    private void groupAndInfo(WifiP2pGroup group) {
        final WifiP2pGroup g = group;
        if (!ready()) {
            group(false, false, "", "", "");
            return;
        }
        boolean asked = false;
        try {
            manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                @Override
                public void onConnectionInfoAvailable(WifiP2pInfo info) {
                    boolean grouped = info != null && info.groupFormed;
                    boolean isGo = grouped && info.isGroupOwner;
                    String goIp = "";
                    if (grouped && info.groupOwnerAddress != null) {
                        String s = info.groupOwnerAddress.getHostAddress();
                        goIp = s == null ? "" : s;
                    }
                    String ssid = g != null && g.getNetworkName() != null ? g.getNetworkName() : "";
                    String pass = g != null && g.getPassphrase() != null ? g.getPassphrase() : "";
                    group(grouped, isGo, goIp, ssid, pass);
                }
            });
            asked = true;
        } catch (Exception e) {
            line("!!", "读取连接信息失败：" + e);
        }
        if (!asked) {
            group(false, false, "", "", "");
        }
    }

    private void handle(Intent intent) {
        if (intent == null || intent.getAction() == null || closePending()) {
            return;
        }
        try {
            String a = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(a)) {
                p2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                line(p2pEnabled ? "OK" : "!!", p2pEnabled ? "P2P 已启用" : "P2P 未启用，请打开 WiFi");
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                requestPeers();
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                refresh();
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(a)) {
                WifiP2pDevice d = null;
                try {
                    d = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                } catch (Exception ignored) {
                }
                if (d != null && d.deviceAddress != null) {
                    line("--", "本机 P2P 地址：" + d.deviceAddress);
                }
            }
        } catch (Exception e) {
            line("!!", "处理 P2P 广播失败：" + e);
        }
    }

    private void requestPeers() {
        if (!ready()) {
            peers(new ArrayList<String[]>());
            return;
        }
        boolean asked = false;
        try {
            manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                @Override
                public void onPeersAvailable(WifiP2pDeviceList list) {
                    List<String[]> out = new ArrayList<String[]>();
                    if (list != null) {
                        Map<String, String[]> uniq = new LinkedHashMap<String, String[]>();
                        for (WifiP2pDevice d : list.getDeviceList()) {
                            if (d == null || d.deviceAddress == null) {
                                continue;
                            }
                            if (uniq.containsKey(d.deviceAddress)) {
                                continue;
                            }
                            String name = d.deviceName == null ? "" : d.deviceName.trim();
                            if (name.length() == 0) {
                                name = d.deviceAddress;
                            }
                            String st = statusText(d.status);
                            uniq.put(d.deviceAddress, new String[]{d.deviceAddress, name, st});
                        }
                        out.addAll(uniq.values());
                    }
                    peers(out);
                }
            });
            asked = true;
        } catch (Exception e) {
            line("!!", "读取设备列表失败：" + e);
        }
        if (!asked) {
            peers(new ArrayList<String[]>());
        }
    }

    private boolean closePending() {
        return closed;
    }

    /**
     * 删持久化组。deletePersistentGroup 是 @hide，android.jar 上没有，
     * 只能反射；被非 SDK 接口限制拦下时静默忽略，不影响随后的 removeGroup。
     */
    private void deletePersistent(WifiP2pGroup group) {
        if (group == null || manager == null || channel == null) {
            return;
        }
        try {
            int netId = group.getNetworkId();
            if (netId < 0) {
                return;
            }
            Method m = WifiP2pManager.class.getMethod("deletePersistentGroup",
                    WifiP2pManager.Channel.class, int.class, WifiP2pManager.ActionListener.class);
            m.invoke(manager, channel, Integer.valueOf(netId), new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onFailure(int reason) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean ready() {
        if (closed) {
            return false;
        }
        if (manager == null || channel == null) {
            line("!!", "这台设备没有 P2P 服务");
            return false;
        }
        return true;
    }

    private WifiP2pManager.ActionListener act(final String what, final Runnable after) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (after != null) {
                    ui.post(after);
                }
            }

            @Override
            public void onFailure(int reason) {
                line("!!", what + "失败：" + reasonCn(reason));
            }
        };
    }

    private static String reasonCn(int reason) {
        if (reason == WifiP2pManager.ERROR) {
            return "内部错误";
        }
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            return "这台设备不支持 P2P";
        }
        if (reason == WifiP2pManager.BUSY) {
            return "系统正忙，稍后再试";
        }
        return "未知原因(" + reason + ")";
    }

    private void line(final String tag, final String msg) {
        final Cb c = cb;
        if (c == null) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                c.onLine(tag, msg);
            }
        });
    }

    /** statusToString 也是 @hide，反射到就用系统的，否则按状态常量本地映射。 */
    private static String statusText(int status) {
        try {
            Method m = WifiP2pDevice.class.getMethod("statusToString", int.class);
            Object r = m.invoke(null, Integer.valueOf(status));
            if (r instanceof String) {
                return (String) r;
            }
        } catch (Throwable ignored) {
        }
        switch (status) {
            case WifiP2pDevice.AVAILABLE:
                return "可连接";
            case WifiP2pDevice.INVITED:
                return "已邀请";
            case WifiP2pDevice.CONNECTED:
                return "已连接";
            case WifiP2pDevice.FAILED:
                return "失败";
            case WifiP2pDevice.UNAVAILABLE:
                return "不可用";
            default:
                return "状态 " + status;
        }
    }

    private void peers(final List<String[]> list) {
        final Cb c = cb;
        if (c == null) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                c.onPeers(list);
            }
        });
    }

    private void group(final boolean grouped, final boolean isGo, final String goIp,
                       final String ssid, final String pass) {
        final Cb c = cb;
        if (c == null) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                c.onGroup(grouped, isGo, goIp, ssid, pass);
            }
        });
    }
}
