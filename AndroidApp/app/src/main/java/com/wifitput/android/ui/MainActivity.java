package com.wifitput.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.os.Build;
import android.os.Bundle;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Dev;
import com.wifitput.android.core.Upload;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 1101;

    private final List<TabPanel> panels = new ArrayList<TabPanel>();
    private final List<TextView> tabViews = new ArrayList<TextView>();
    private TextView status;
    private int current;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.WHITE);

        root.addView(buildHeader());

        final android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        for (String mode : new String[]{"wifi", "hotspot", "p2p"}) {
            TabPanel p = new TabPanel(this, mode);
            p.setVisibility(mode.equals("wifi") ? android.view.View.VISIBLE : android.view.View.GONE);
            panels.add(p);
            body.addView(p, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        }
        root.addView(body);
        setContentView(root);

        requestNeeded();
    }

    private LinearLayout buildHeader() {
        LinearLayout head = Ui.column(this);
        head.setBackgroundColor(Ui.BLUE);
        head.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 0));

        // 标题行右侧放「⋯」：历史和 PC 回传设置都是低频操作，塞进菜单里不占 Tab 位置
        LinearLayout titleRow = Ui.row(this);
        TextView title = Ui.text(this, "WiFiTput", 19f, Color.WHITE, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);
        TextView more = Ui.text(this, "⋯", 20f, Color.WHITE, true);
        more.setGravity(Gravity.CENTER);
        more.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        more.setBackground(Ui.pressed(this, 0, 0x33FFFFFF, 0, 8f));
        more.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                showMenu(v);
            }
        });
        titleRow.addView(more);
        head.addView(titleRow);

        status = Ui.text(this, "", 11.5f, 0xFFD6E6FA, false);
        status.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 8));
        head.addView(status);

        LinearLayout tabs = Ui.row(this);
        String[] names = {"WiFi", "热点", "P2P"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, names[i], 14f, Color.WHITE, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            t.setLayoutParams(lp);
            t.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    select(idx);
                }
            });
            tabs.addView(t);
            tabViews.add(t);
        }
        head.addView(tabs);
        paintTabs(0);
        return head;
    }

    /** 右上角「⋯」菜单。只有两项：翻历史记录、改结果回传的 PC 地址。 */
    private void showMenu(android.view.View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "查看历史记录");
        m.getMenu().add(0, 2, 1, "设置结果回传 PC");
        m.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                if (item.getItemId() == 1) {
                    startActivity(new android.content.Intent(MainActivity.this, HistoryActivity.class));
                    return true;
                }
                PcDialog.show(MainActivity.this);
                return true;
            }
        });
        m.show();
    }

    /** 选中的 Tab 白底蓝字 + 顶部圆角，未选中半透明，一眼能看出在哪一页。 */
    private void paintTabs(int active) {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == active;
            t.setTextColor(on ? Ui.BLUE : 0xFFDCE9F8);
            t.setBackground(on ? Ui.round(this, Color.WHITE, 0, 10f) : null);
            t.setAlpha(on ? 1f : 0.85f);
        }
    }

    private void select(int idx) {
        current = idx;
        for (int i = 0; i < panels.size(); i++) {
            panels.get(i).setVisibility(i == idx ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        paintTabs(idx);
    }

    private void requestNeeded() {
        if (Build.VERSION.SDK_INT < 23) {
            refreshStatus();
            return;
        }
        List<String> want = new ArrayList<String>();
        add(want, android.Manifest.permission.ACCESS_FINE_LOCATION);
        // 扫码配对要用相机；不授权也能用「扫描对端」和手工填地址
        add(want, android.Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33) {
            add(want, "android.permission.NEARBY_WIFI_DEVICES");
            add(want, "android.permission.POST_NOTIFICATIONS");
        }
        if (want.isEmpty()) {
            refreshStatus();
            return;
        }
        requestPermissions(want.toArray(new String[0]), REQ_PERM);
    }

    private void add(List<String> want, String p) {
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            want.add(p);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        String[] addr = QrDialog.result(req, res, data);
        if (addr != null && current >= 0 && current < panels.size()) {
            panels.get(current).onQrResult(addr);
        }
    }

    private void refreshStatus() {
        final StringBuilder sb = new StringBuilder();
        sb.append("本机地址 ");
        try {
            List<String> ips = Upload.localIps();
            sb.append(ips.isEmpty() ? "无 " : android.text.TextUtils.join(" / ", ips));
        } catch (Exception e) {
            sb.append("读取失败 ");
        }
        try {
            JSONObject d = Dev.collect(getApplicationContext());
            JSONObject w = d.getJSONObject("wifi");
            if (w.getBoolean("connected")) {
                sb.append("   ").append(w.getString("ssid"))
                  .append(" ").append(w.getString("band"))
                  .append(" ch").append(w.getInt("channel"))
                  .append("  ").append(w.getInt("rssi")).append("dBm");
            } else {
                sb.append("   WiFi 未连接");
            }
            if (d.getJSONObject("hotspot").getBoolean("on")) {
                sb.append("   热点开");
            }
        } catch (Exception ignored) {
        }
        status.post(new Runnable() {
            @Override
            public void run() {
                status.setText(sb.toString());
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 灭屏 / 切后台都会走这里。以前在这里 stopAll()，结果一灭屏热点和 P2P 群组就被拆了，
        // 灭屏续跑根本测不下去。现在只刷新一下顶栏状态，真正的拆解留到 onDestroy。
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        for (TabPanel p : panels) {
            if (!p.isBusy()) {
                p.stopAll();
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        boolean busy = false;
        for (TabPanel p : panels) {
            if (p.isBusy()) {
                busy = true;
            }
        }
        if (!busy) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("还在测试")
                .setMessage("现在退出会中断正在跑的 iperf3，结果可能不完整。确定退出吗？")
                .setNegativeButton("继续测", null)
                .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        for (TabPanel p : panels) {
                            p.stopAll();
                        }
                        finish();
                    }
                })
                .show();
    }
}