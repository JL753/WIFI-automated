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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wifitput.android.core.History;

import java.io.File;
import java.util.List;

/** 历史记录：列表 + 单条详情。数据全部来自 core.History 落盘的目录，这里只负责展示和删除。 */
public class HistoryActivity extends Activity {

    private static final String TITLE_LIST = "历史记录";
    private static final String NO_LOG = "（这次运行没有日志）";
    private static final String CARD_SAVE = "数据保存在手机上哪里";
    private static final String SYNC_FAIL = "同步失败，外部存储可能不可用";

    private TextView barTitle;
    private FrameLayout content;
    /** 为 true 时返回键回到列表，而不是退出页面。 */
    private boolean detailShowing;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.WHITE);
        root.addView(buildBar());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (detailShowing) {
            showList();
            return;
        }
        super.onBackPressed();
    }

    private LinearLayout buildBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.BLUE);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        TextView back = Ui.text(this, "‹ 返回", 15f, Color.WHITE, false);
        back.setPadding(0, 0, Ui.dp(this, 8), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
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

        // 右侧放一个和「返回」一模一样的隐形占位，两边等宽标题才会真的居中
        TextView spacer = Ui.text(this, "‹ 返回", 15f, Color.WHITE, false);
        spacer.setPadding(0, 0, Ui.dp(this, 8), 0);
        spacer.setVisibility(View.INVISIBLE);
        bar.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    // ---------------------------------------------------------------- 列表

    private void showList() {
        detailShowing = false;
        barTitle.setText(TITLE_LIST);
        content.removeAllViews();

        List<History.Item> items;
        try {
            items = History.list(this);
        } catch (Exception e) {
            content.addView(centeredText("读取历史记录失败：" + e.getMessage()));
            return;
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
        card.addView(pathBlock("手机上的位置", Color.BLACK, localPath, localPath.isEmpty(),
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
        value.setBackgroundColor(0xFFF5F5F5);
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
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.round(this, Color.WHITE, Ui.CARD_LINE, 12f));
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(this, 9);
        card.setLayoutParams(clp);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(it);
            }
        });

        // 第一行：标题 + 删除。删除自己有点击监听，点它不会冒泡到卡片进详情。
        LinearLayout top = Ui.row(this);
        TextView title = Ui.text(this, safe(it.title), 14f, Ui.INK, true);
        title.setSingleLine(false);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
        card.addView(top, Ui.fill());

        // 第二行：概要首行 + 角色标签
        LinearLayout mid = Ui.row(this);
        mid.setPadding(0, Ui.dp(this, 4), 0, 0);
        TextView sum = Ui.text(this, firstLine(it.summary), 11.5f, Ui.GREY, false);
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

    // ---------------------------------------------------------------- 详情

    private void showDetail(final History.Item it) {
        detailShowing = true;
        barTitle.setText(detailTitle(it.title));
        content.removeAllViews();

        LinearLayout page = Ui.column(this);
        page.setBackgroundColor(Ui.PAGE_BG);

        // 上面卡片区、下面日志卡各占一半，各自滚动。
        // 不把日志卡塞进外层 ScrollView：那样日志框要么被撑成整段长文本、要么内外两层都能滚，
        // 手指落在日志上时手势会被外层抢走。分成两个互不重叠的滚动区最省事。
        ScrollView outer = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), 0);

        LinearLayout head = Ui.card(this, safe(it.title));
        addRow(head, "开始", safe(it.startedAt));
        addRow(head, "结束", safe(it.finishedAt));
        addRow(head, "角色", "server".equals(it.role) ? "接收" : "发送");
        addRow(head, "模式", modeName(it.mode));
        body.addView(head);

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
        logView.setBackgroundColor(0xFFF5F5F5);

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

        page.addView(logCard);
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
