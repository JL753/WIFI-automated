package com.wifitput.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.wifitput.android.core.Compare;
import com.wifitput.android.core.History;
import com.wifitput.android.core.Stats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 历史记录：列表 + 单条详情。数据全部来自 core.History 落盘的目录，这里只负责展示和删除。 */
public class HistoryActivity extends Activity {

    private static final String TITLE_LIST = "历史记录";
    private static final String NO_LOG = "（这次运行没有日志）";
    private static final String CARD_SAVE = "数据保存在手机上哪里";
    private static final String SYNC_FAIL = "同步失败，外部存储可能不可用";

    private TextView barTitle;
    private TextView barRight;
    private FrameLayout content;
    /** 为 true 时返回键回到列表，而不是退出页面。 */
    private boolean detailShowing;
    /** 对比模式：在列表里选两条记录。 */
    private boolean compareMode;
    private final List<String> picked = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle b) {
        Ui.applyTheme(this);
        super.onCreate(b);
        Ui.init(this);
        Ui.applyBars(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.PAGE_BG);
        root.addView(buildBar());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (compareMode) {
            exitCompare();
            return;
        }
        if (detailShowing) {
            showList();
            return;
        }
        super.onBackPressed();
    }

    private void goBack() {
        if (compareMode) {
            exitCompare();
        } else if (detailShowing) {
            showList();
        } else {
            finish();
        }
    }

    private LinearLayout buildBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.headerBg(this));
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        TextView back = Ui.text(this, "‹ 返回", 15f, Color.WHITE, false);
        back.setPadding(0, 0, Ui.dp(this, 8), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goBack();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        barTitle = Ui.text(this, TITLE_LIST, 17f, Color.WHITE, true);
        barTitle.setGravity(Gravity.CENTER);
        barTitle.setSingleLine(true);
        barTitle.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(barTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧「对比」/「取消」；和左侧返回一起把标题夹在中间
        barRight = Ui.text(this, "对比", 14f, Color.WHITE, true);
        barRight.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), 0, Ui.dp(this, 2));
        barRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (compareMode) {
                    exitCompare();
                } else {
                    enterCompare();
                }
            }
        });
        bar.addView(barRight, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    // ---------------------------------------------------------------- 列表

    private void showList() {
        compareMode = false;
        picked.clear();
        rebuildList();
    }

    private void enterCompare() {
        compareMode = true;
        picked.clear();
        rebuildList();
    }

    private void exitCompare() {
        compareMode = false;
        picked.clear();
        rebuildList();
    }

    private void rebuildList() {
        detailShowing = false;
        barTitle.setText(compareMode
                ? "选两条对比（" + picked.size() + "/2）" : TITLE_LIST);
        barRight.setText(compareMode ? "取消" : "对比");
        barRight.setVisibility(View.VISIBLE);
        content.removeAllViews();

        List<History.Item> items;
        try {
            items = History.list(this);
        } catch (Exception e) {
            content.addView(centeredText("读取历史记录失败：" + e.getMessage()));
            return;
        }
        // 少于两条没有可对比的对象；对比模式下始终保留「取消」
        if (!compareMode) {
            barRight.setVisibility(items != null && items.size() >= 2 ? View.VISIBLE : View.GONE);
        }

        // 打流记录和「数据保存在哪里」这张卡放进同一条滚动路径：
        // 空列表时先给提示再挂卡片，有记录时卡片排在最上面。
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 2));

        if (items == null || items.isEmpty()) {
            body.addView(emptyHint(), Ui.fill());
            body.addView(syncCard());
        } else {
            body.addView(syncCard());
            for (int i = 0; i < items.size(); i++) {
                History.Item it = items.get(i);
                if (it != null) {
                    body.addView(listCard(it));
                }
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 空列表时的居中提示，外面还得挂着同步卡片，所以不再铺满整个 content。 */
    private View emptyHint() {
        LinearLayout box = Ui.column(this);
        TextView t = Ui.text(this,
                "还没有历史记录。跑一次打流或当一次服务端，这里就会出现。", 13f, Ui.GREY, false);
        t.setGravity(Gravity.CENTER);
        box.addView(t, Ui.fill());
        box.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 16));
        return box;
    }

    /** 「数据保存在手机上哪里」卡片：两条真实路径 + USB 导出的走法 + 手动同步按钮。 */
    private View syncCard() {
        LinearLayout card = Ui.card(this, CARD_SAVE);

        // 第一行说明：私有目录是什么、USB 连上能做什么
        card.addView(Ui.hint(this,
                "历史记录保存在手机的应用私有目录里，电脑用 USB 连上就能导出。"), Ui.fill());

        // 私有目录：复用 History.dir，保证和真正落盘的位置是同一个
        final String localPath = dirPath();
        card.addView(pathBlock("手机上的位置", Ui.INK, localPath, localPath.isEmpty(),
                "无法读取私有目录"), Ui.fill());

        // USB 可见目录：externalPath 返回空串就是外部存储不可用
        final String usbPath = History.externalPath(this);
        final boolean usbOk = !usbPath.isEmpty();
        card.addView(pathBlock("USB 可见位置", Ui.GREEN, usbPath, !usbOk,
                "外部存储不可用"), Ui.fill());

        // 电脑端的操作路径，手机这端不用点任何东西
        card.addView(Ui.hint(this,
                "电脑端在「收结果」页点「从手机导出（USB）」就能一键导出，手机这端不用操作。"),
                Ui.fill());

        // 同步结果提示，按钮点完就地更新
        final TextView result = Ui.text(this, "", 11.5f, Ui.GREY, false);
        result.setVisibility(View.GONE);
        result.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        card.addView(result, Ui.fill());

        Button sync = Ui.primary(this, "立即同步到 USB 可见目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSync(result);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        card.addView(sync, blp);

        return card;
    }

    /** 「标签 + 路径」一小块；路径可长按选中复制，等宽字体，异常值走红色提示。 */
    private View pathBlock(String name, int normalColor, String path, boolean bad, String badText) {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 6), 0, 0);

        TextView label = Ui.text(this, name, 11.5f, Ui.GREY, false);
        label.setPadding(0, 0, 0, Ui.dp(this, 3));
        box.addView(label, Ui.fill());

        String show = bad ? badText : path;
        TextView value = Ui.text(this, show, 11.5f, bad ? Ui.RED : normalColor, false);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextIsSelectable(!bad);
        value.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        value.setBackgroundColor(Ui.LOG_BG);
        box.addView(value, Ui.fill());
        return box;
    }

    /** History.dir 会顺手建目录，异常时不该把整个列表页带崩。 */
    private String dirPath() {
        try {
            File d = History.dir(this);
            return d == null ? "" : d.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    /** 后台线程镜像到 USB 可见目录，回主线程只改提示文字。 */
    private void startSync(final TextView result) {
        result.setVisibility(View.VISIBLE);
        result.setTextColor(Ui.GREY);
        result.setText("正在同步…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                try {
                    n = History.syncToExternal(HistoryActivity.this);
                } catch (Exception e) {
                    // 同步是附加能力，失败只影响提示文案，不该崩页面
                    n = 0;
                }
                final int count = n;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 页面可能已经退出，这时候碰界面会炸
                        if (isFinishing()) {
                            return;
                        }
                        result.setVisibility(View.VISIBLE);
                        if (count > 0) {
                            result.setTextColor(Ui.GREEN);
                            result.setText("已同步 " + count + " 条到 USB 可见目录");
                        } else {
                            result.setTextColor(Ui.RED);
                            result.setText(SYNC_FAIL);
                        }
                    }
                });
            }
        }).start();
    }

    private View listCard(final History.Item it) {
        final boolean on = compareMode && picked.contains(it.id);
        LinearLayout card = Ui.column(this);
        card.setBackground(on
                ? Ui.round(this, Ui.PILL_BLUE_BG, Ui.BLUE, 14f)
                : Ui.round(this, Ui.CARD, Ui.CARD_LINE, 14f));
        card.setPadding(Ui.dp(this, 13), Ui.dp(this, 11), Ui.dp(this, 13), Ui.dp(this, 11));
        card.setElevation(Ui.dp(this, 1f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(this, 9);
        card.setLayoutParams(clp);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (compareMode) {
                    togglePick(it);
                } else {
                    showDetail(it);
                }
            }
        });

        // 第一行：标签（没有就用原标题）+ 删除。删除自己有点击监听，点它不会冒泡到卡片进详情。
        LinearLayout top = Ui.row(this);
        String name = safe(it.label).trim();
        TextView title = Ui.text(this, name.isEmpty() ? safe(it.title) : name, 14f, Ui.INK, true);
        title.setSingleLine(false);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (compareMode) {
            TextView sel = Ui.text(this, on ? "✓ 已选" : "选择", 11.5f, on ? Ui.BLUE : Ui.GREY, true);
            sel.setPadding(Ui.dp(this, 10), 0, 0, 0);
            top.addView(sel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            TextView del = Ui.text(this, "删除", 11.5f, Ui.RED, false);
            del.setPadding(Ui.dp(this, 10), 0, 0, 0);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(it);
                }
            });
            top.addView(del, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(top, Ui.fill());

        // 第二行：有时间就带上时间/模式，再跟概要首行（新记录里是速率）
        LinearLayout mid = Ui.row(this);
        mid.setPadding(0, Ui.dp(this, 4), 0, 0);
        String first = firstLine(it.summary);
        String second = name.isEmpty() ? first
                : safe(it.title) + (first.isEmpty() ? "" : " · " + first);
        TextView sum = Ui.text(this, second, 11.5f, Ui.GREY, false);
        sum.setSingleLine(false);
        mid.addView(sum, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tag = Ui.text(this, "server".equals(it.role) ? "接收" : "发送", 12f, Ui.BLUE, false);
        tag.setPadding(Ui.dp(this, 10), 0, 0, 0);
        mid.addView(tag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(mid, Ui.fill());

        return card;
    }

    private void confirmDelete(final History.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("删除历史")
                .setMessage("删掉这条历史记录？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            History.delete(HistoryActivity.this, it.id);
                        } catch (Exception e) {
                            // 删失败也不该崩，刷新后那条还在，用户能看见
                        }
                        showList();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 对比

    private void togglePick(final History.Item it) {
        if (!"client".equals(it.role) || it.bundle == null || it.bundle.trim().isEmpty()) {
            Toast.makeText(this, "这条记录没有结果包，不能参与对比", Toast.LENGTH_SHORT).show();
            return;
        }
        if (picked.contains(it.id)) {
            picked.remove(it.id);
        } else {
            if (picked.size() >= 2) {
                Toast.makeText(this, "最多选两条", Toast.LENGTH_SHORT).show();
                return;
            }
            picked.add(it.id);
        }
        rebuildList();
        if (picked.size() == 2) {
            final History.Item a = History.load(this, picked.get(0));
            final History.Item b = History.load(this, picked.get(1));
            if (a != null && b != null) {
                content.post(new Runnable() {
                    @Override
                    public void run() {
                        showCompare(a, b);
                    }
                });
            }
        }
    }

    private void showCompare(final History.Item a, final History.Item b) {
        final Stats sa = Stats.ofBundle(a.bundle);
        final Stats sb = Stats.ofBundle(b.bundle);
        if (sa == null || sb == null) {
            new AlertDialog.Builder(this)
                    .setTitle("无法对比")
                    .setMessage("其中一条记录没有可解析的结果包。")
                    .setPositiveButton("关闭", null)
                    .show();
            exitCompare();
            return;
        }
        boolean same = sa.sameParams(sb) && safe(a.mode).equals(safe(b.mode));
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
        if (!same) {
            TextView warn = Ui.text(this,
                    "参数或模式不同：下面只做数值对照，不能当作严格的前后对比。",
                    11.5f, Ui.AMBER, true);
            warn.setPadding(0, 0, 0, Ui.dp(this, 6));
            box.addView(warn, Ui.fill());
        }
        LinearLayout head = Ui.row(this);
        head.addView(cmpCell("项目", 0.75f, Ui.GREY, true));
        head.addView(cmpCell(shortLabel(a), 1f, Ui.INK, true));
        head.addView(cmpCell(shortLabel(b), 1f, Ui.INK, true));
        box.addView(head, Ui.fill());
        cmpRow(box, "时间", shortTime(a.startedAt), shortTime(b.startedAt), false);
        cmpRow(box, "模式", modeName(a.mode), modeName(b.mode), !safe(a.mode).equals(safe(b.mode)));
        cmpRow(box, "参数", paramText(sa), paramText(sb), !sa.sameParams(sb));
        cmpRow(box, "接收端", mbText(sa.receiverMb), mbText(sb.receiverMb), false);
        cmpRow(box, "发送端", mbText(sa.senderMb), mbText(sb.senderMb), false);
        cmpRow(box, "平均", mbText(sa.avgMb), mbText(sb.avgMb), false);
        cmpRow(box, "最低", mbText(sa.minMb), mbText(sb.minMb), false);
        cmpRow(box, "最高", mbText(sa.maxMb), mbText(sb.maxMb), false);
        cmpRow(box, "重传", intText(sa.retr), intText(sb.retr), false);
        cmpRow(box, "丢包", pctText(sa.lostPct), pctText(sb.lostPct), false);
        cmpRow(box, "抖动", sa.jitter >= 0 ? Stats.fmtPct(sa.jitter) + "ms" : "--",
                sb.jitter >= 0 ? Stats.fmtPct(sb.jitter) + "ms" : "--", false);

        double x = sa.mainMb();
        double y = sb.mainMb();
        if (x > 0 && y > 0) {
            double d = (y - x) * 100.0 / x;
            TextView delta = Ui.text(this,
                    "速率变化：" + Stats.fmtMb(x) + " → " + Stats.fmtMb(y) + " Mb/s（"
                            + (d >= 0 ? "+" : "")
                            + String.format(java.util.Locale.US, "%.1f", d) + "%）",
                    12.5f, Ui.BLUE, true);
            delta.setPadding(0, Ui.dp(this, 12), 0, 0);
            box.addView(delta, Ui.fill());
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("两条记录对比")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                exitCompare();
            }
        });
        d.show();
    }

    private void cmpRow(LinearLayout parent, String label, String a, String b, boolean warn) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        row.addView(cmpCell(label, 0.75f, Ui.GREY, false));
        row.addView(cmpCell(a, 1f, warn ? Ui.RED : Ui.INK, false));
        row.addView(cmpCell(b, 1f, warn ? Ui.RED : Ui.INK, false));
        parent.addView(row, Ui.fill());
    }

    private TextView cmpCell(String s, float weight, int color, boolean bold) {
        TextView t = Ui.text(this, s == null ? "--" : s, 11.5f, color, bold);
        t.setLineSpacing(Ui.dp(this, 1), 1f);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return t;
    }

    private String shortLabel(History.Item it) {
        String label = safe(it.label).trim();
        if (!label.isEmpty()) {
            return label;
        }
        return shortTime(it.startedAt);
    }

    private static String shortTime(String stamp) {
        String s = safe(stamp);
        return s.length() >= 16 ? s.substring(5, 16) : (s.isEmpty() ? "--" : s);
    }

    private static String paramText(Stats s) {
        return s.duration + "s · " + s.streams + " 流 · 采样 " + s.interval + "s · "
                + s.kind + (s.reverse ? " 反向" : " 正向");
    }

    private static String mbText(double v) {
        return v >= 0 ? Stats.fmtMb(v) + " Mb/s" : "--";
    }

    private static String pctText(double v) {
        return v >= 0 ? Stats.fmtPct(v) + "%" : "--";
    }

    private static String intText(int v) {
        return v >= 0 ? String.valueOf(v) : "--";
    }

    // ---------------------------------------------------------------- 详情

    private void showDetail(final History.Item it) {
        detailShowing = true;
        barRight.setVisibility(View.GONE);
        final String label = safe(it.label).trim();
        final String headTitle = label.isEmpty() ? safe(it.title) : label;
        barTitle.setText(detailTitle(headTitle));
        content.removeAllViews();

        LinearLayout page = Ui.column(this);
        page.setBackgroundColor(Ui.PAGE_BG);

        // 上面卡片区、下面日志卡各占一半，各自滚动。
        // 不把日志卡塞进外层 ScrollView：那样日志框要么被撑成整段长文本、要么内外两层都能滚，
        // 手指落在日志上时手势会被外层抢走。分成两个互不重叠的滚动区最省事。
        ScrollView outer = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), 0);

        LinearLayout head = Ui.card(this, headTitle);
        addLabelRow(head, it);
        addRow(head, "开始", safe(it.startedAt));
        addRow(head, "结束", safe(it.finishedAt));
        addRow(head, "角色", "server".equals(it.role) ? "接收" : "发送");
        addRow(head, "模式", modeName(it.mode));
        body.addView(head);

        // 结果卡只对客户端记录有意义（服务端历史没有结果包）
        final Stats st = Stats.ofBundle(it.bundle);
        final String compareText;
        if (st != null && st.hasData()) {
            Compare.Hit hit = Compare.previous(this, it.mode, st, it.id, it.startedAt);
            compareText = Compare.line(hit, st);
            body.addView(resultCard(st, compareText));
            View dev = deviceCard(it.bundle);
            if (dev != null) {
                body.addView(dev);
            }
        } else {
            compareText = "";
        }

        LinearLayout sumCard = Ui.card(this, "概要");
        String summary = it.summary == null ? "" : it.summary;
        if (summary.trim().isEmpty()) {
            sumCard.addView(Ui.text(this, "（没有概要）", 12.5f, Ui.GREY, false));
        } else {
            String[] lines = summary.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.trim().isEmpty()) {
                    continue;
                }
                TextView t = Ui.text(this, line, 12.5f, Ui.INK, false);
                t.setPadding(0, Ui.dp(this, 2), 0, 0);
                sumCard.addView(t, Ui.fill());
            }
        }
        body.addView(sumCard);

        if (it.bundle != null && it.bundle.length() > 0) {
            LinearLayout bundleCard = Ui.card(this, "结果包");
            bundleCard.addView(Ui.text(this,
                    "结果包 JSON：" + it.bundle.length() + " 字节（已随历史一起保存）",
                    12.5f, Ui.INK, false));
            body.addView(bundleCard);
        }

        outer.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(outer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout logCard = Ui.card(this, "完整日志");
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logLp.leftMargin = Ui.dp(this, 12);
        logLp.rightMargin = Ui.dp(this, 12);
        logLp.bottomMargin = Ui.dp(this, 10);
        logCard.setLayoutParams(logLp);

        final String log = it.log == null ? "" : it.log;
        TextView logView = Ui.text(this, log.length() == 0 ? NO_LOG : log, 11.5f, Ui.INK, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        logView.setBackgroundColor(Ui.LOG_BG);

        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logCard.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout btns = Ui.row(this);
        btns.setPadding(0, Ui.dp(this, 8), 0, 0);
        Button copy = Ui.ghost(this, "复制日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogDialog.copy(HistoryActivity.this, log);
            }
        });
        btns.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button full = Ui.ghost(this, "打开全屏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogDialog.show(HistoryActivity.this, it.title, log);
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp.leftMargin = Ui.dp(this, 8);
        btns.addView(full, flp);
        logCard.addView(btns, Ui.fill());

        LinearLayout btns2 = Ui.row(this);
        LinearLayout.LayoutParams b2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b2lp.topMargin = Ui.dp(this, 7);
        Button share = Ui.ghost(this, "分享摘要", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null || !st.hasData()) {
                    Toast.makeText(HistoryActivity.this,
                            "这条记录没有结果数据，只能复制或全屏看日志",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String sub = safe(it.startedAt) + " · " + modeName(it.mode) + " · "
                        + ("server".equals(it.role) ? "接收" : "发送");
                Share.send(HistoryActivity.this,
                        Share.text(safe(it.label), sub, st, compareText));
            }
        });
        btns2.addView(share, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button csv = Ui.ghost(this, "导出 CSV", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CsvExport.shareJson(HistoryActivity.this, safe(it.label),
                        safe(it.startedAt), it.bundle);
            }
        });
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp2.leftMargin = Ui.dp(this, 8);
        btns2.addView(csv, clp2);
        logCard.addView(btns2, b2lp);

        page.addView(logCard);
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 详情页的标签行：显示当前标签，右侧可直接加/改。 */
    private void addLabelRow(LinearLayout parent, final History.Item it) {
        String name = safe(it.label).trim();
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 2), 0, 0);
        TextView value = Ui.text(this, "标签 " + (name.isEmpty() ? "（未命名）" : name),
                12.5f, name.isEmpty() ? Ui.GREY : Ui.INK, !name.isEmpty());
        row.addView(value, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView edit = Ui.text(this, name.isEmpty() ? "加标签" : "改标签", 11.5f, Ui.BLUE, true);
        edit.setPadding(Ui.dp(this, 8), 0, 0, 0);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLabelDialog(it);
            }
        });
        row.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row, Ui.fill());
    }

    private void showLabelDialog(final History.Item it) {
        final EditText input = Ui.input(this, safe(it.label), 0);
        // Ui.input 带的是 weight 参数，直接放进对话框会宽度为 0
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        box.addView(input, Ui.fill());
        new AlertDialog.Builder(this)
                .setTitle("给这条记录加标签")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String label = input.getText().toString().trim();
                        if (!History.updateLabel(HistoryActivity.this, it.id, label)) {
                            Toast.makeText(HistoryActivity.this, "保存失败",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        History.Item fresh = History.load(HistoryActivity.this, it.id);
                        if (fresh != null) {
                            showDetail(fresh);
                        } else {
                            showList();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 详情页里的结果卡：主数字 + 关键统计 + 结论 + 与上次同参数对比。 */
    private View resultCard(Stats st, String compareText) {
        LinearLayout card = Ui.card(this, "结果");
        LinearLayout hero = Ui.row(this);
        LinearLayout left = Ui.column(this);
        left.addView(Ui.text(this, st.receiverMb >= 0 ? "接收端" : "平均速率",
                11f, Ui.GREY, false), Ui.fill());
        LinearLayout valueRow = Ui.row(this);
        valueRow.addView(Ui.text(this, Stats.fmtMb(st.mainMb()), 28f, Ui.INK, true));
        TextView unit = Ui.text(this, "Mb/s", 11f, Ui.GREY, false);
        unit.setPadding(Ui.dp(this, 5), 0, 0, Ui.dp(this, 2));
        unit.setGravity(Gravity.BOTTOM);
        valueRow.addView(unit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(valueRow, Ui.fill());
        hero.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = Ui.column(this);
        right.setGravity(Gravity.END);
        right.addView(Ui.text(this, "发送端 "
                + (st.senderMb >= 0 ? Stats.fmtMb(st.senderMb) : "--"), 11.5f, Ui.INK, false));
        right.addView(Ui.text(this, "最低 " + Stats.fmtMb(st.minMb)
                + " · 最高 " + Stats.fmtMb(st.maxMb), 11.5f, Ui.INK, false));
        hero.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(hero, Ui.fill());

        StringBuilder line = new StringBuilder("平均 ").append(Stats.fmtMb(st.avgMb));
        if (st.retr >= 0) {
            line.append(" · 重传 ").append(st.retr);
        }
        if (st.lostPct >= 0) {
            line.append(" · 丢包 ").append(Stats.fmtPct(st.lostPct)).append("%");
        }
        if (st.jitter >= 0) {
            line.append(" · 抖动 ").append(Stats.fmtPct(st.jitter)).append("ms");
        }
        TextView detail = Ui.text(this, line.toString(), 11.5f, Ui.GREY, false);
        detail.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(detail, Ui.fill());

        int level = st.level(st.linkMbps);
        int bg = level == 2 ? Ui.DANGER_BG : level == 1 ? Ui.WARN_BG : Ui.SUCCESS_BG;
        int fg = level == 2 ? Ui.RED : level == 1 ? Ui.AMBER : Ui.GREEN;
        TextView verdict = Ui.text(this, st.verdict(st.linkMbps), 12f, fg, false);
        verdict.setLineSpacing(Ui.dp(this, 1), 1f);
        verdict.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        verdict.setBackground(Ui.round(this, bg, 0, 9f));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = Ui.dp(this, 10);
        card.addView(verdict, vlp);

        if (compareText != null && !compareText.isEmpty()) {
            TextView cmp = Ui.text(this, "对比：" + compareText, 11.5f, Ui.BLUE, false);
            cmp.setPadding(0, Ui.dp(this, 9), 0, 0);
            card.addView(cmp, Ui.fill());
        }
        return card;
    }

    /** 结果包里带的发起端设备信息，能解析多少显示多少。 */
    private View deviceCard(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray devices = o.optJSONArray("devices");
            if (devices == null || devices.length() == 0) {
                return null;
            }
            JSONObject d = devices.optJSONObject(0);
            if (d == null) {
                return null;
            }
            LinearLayout card = Ui.card(this, "设备信息（发起端）");
            String model = safe(d.optString("model"));
            String android = safe(d.optString("android"));
            if (!model.isEmpty()) {
                addRow(card, "型号", model
                        + (android.isEmpty() ? "" : " · Android " + android));
            }
            JSONObject w = d.optJSONObject("wifi");
            if (w != null) {
                String ssid = safe(w.optString("ssid"));
                String band = safe(w.optString("band"));
                int ch = w.optInt("channel", 0);
                String wifi = ssid + (band.isEmpty() ? "" : (ssid.isEmpty() ? "" : " · ") + band)
                        + (ch > 0 ? " ch" + ch : "");
                if (!wifi.trim().isEmpty()) {
                    addRow(card, "WiFi", wifi);
                }
                int rssi = w.optInt("rssi", 0);
                double link = w.optDouble("link_tx_mbps", -1);
                if (rssi != 0 || link > 0) {
                    StringBuilder s = new StringBuilder();
                    if (rssi != 0) {
                        s.append(rssi).append(" dBm");
                    }
                    if (link > 0) {
                        s.append(s.length() == 0 ? "" : " · ")
                                .append("协商 ").append(Stats.fmtMb(link)).append(" Mbps");
                    }
                    addRow(card, "信号", s.toString());
                }
            }
            return card;
        } catch (Exception e) {
            return null;
        }
    }

    /** 详情卡里的一行「标签 值」。 */
    private void addRow(LinearLayout parent, String name, String value) {
        TextView t = Ui.text(this, name + " " + value, 12.5f, Ui.INK, false);
        t.setPadding(0, Ui.dp(this, 2), 0, 0);
        parent.addView(t, Ui.fill());
    }

    // ---------------------------------------------------------------- 小工具

    private TextView centeredText(String s) {
        TextView t = Ui.text(this, s, 13f, Ui.GREY, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        t.setLayoutParams(lp);
        return t;
    }

    /** History 的字段全是 String，但文件被手工改过时可能是 null。 */
    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 概要的首行，列表卡片第二行用。 */
    private static String firstLine(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** 标题栏放不下整条标题，取前一段，取空了就退回「历史详情」。 */
    private static String detailTitle(String title) {
        String s = safe(title).trim();
        if (s.isEmpty()) {
            return "历史详情";
        }
        return s.length() <= 12 ? s : s.substring(0, 12) + "…";
    }

    /** 模式串转界面上的名字。 */
    private static String modeName(String mode) {
        if ("wifi".equals(mode)) {
            return "WiFi";
        }
        if ("hotspot".equals(mode)) {
            return "热点";
        }
        if ("p2p".equals(mode)) {
            return "P2P";
        }
        // 老记录或被手工改过的文件可能没有 mode，别在界面上留一行空白
        return mode == null || mode.trim().isEmpty() ? "未知" : mode;
    }
}
