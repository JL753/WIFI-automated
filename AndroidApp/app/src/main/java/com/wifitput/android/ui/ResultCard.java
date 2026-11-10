package com.wifitput.android.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Stats;

/**
 * 一次打流结束后的结果卡：主数字 + 关键统计 + 一句人话结论 + 复测/分享/加标签。
 * 数据全部来自 {@link Stats}，口径与 PC 端报告一致。
 */
class ResultCard extends LinearLayout {

    interface Actions {
        void onAgain();

        void onRepeat();

        void onShare();

        void onCsv();

        void onLabel();
    }

    private final TextView title;
    private final TextView labelBtn;
    private final TextView subtitle;
    private final TextView role;
    private final TextView value;
    private final TextView unit;
    private final TextView sender;
    private final TextView min;
    private final TextView max;
    private final TextView retr;
    private final TextView loss;
    private final TextView jitter;
    private final TextView ratio;
    private final TextView verdict;
    private final TextView compare;
    private final TextView aggregate;
    private final LinearLayout topRow;
    private final LinearLayout bottomRow;
    private final Button again;
    private final Button repeat;
    private final Button share;
    private final Button csv;

    private Actions actions;

    ResultCard(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(Ui.round(c, Ui.RESULT_BG, Ui.CARD_LINE, 12f));
        setPadding(Ui.dp(c, 12), Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
        setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);

        LinearLayout head = Ui.row(c);
        title = Ui.text(c, "本次结果", 14f, Ui.INK, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelBtn = Ui.text(c, "加标签", 12f, Ui.BLUE, true);
        labelBtn.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), 0, Ui.dp(c, 2));
        labelBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onLabel();
                }
            }
        });
        head.addView(labelBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(head, Ui.fill());

        subtitle = Ui.text(c, "", 11f, Ui.MUTED, false);
        subtitle.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 8));
        addView(subtitle, Ui.fill());

        LinearLayout hero = Ui.row(c);
        LinearLayout left = Ui.column(c);
        role = Ui.text(c, "接收端", 11f, Ui.GREY, false);
        left.addView(role, Ui.fill());
        LinearLayout valueRow = Ui.row(c);
        value = Ui.text(c, "--", 30f, Ui.INK, true);
        valueRow.addView(value);
        unit = Ui.text(c, "Mb/s", 11f, Ui.MUTED, false);
        unit.setPadding(Ui.dp(c, 5), 0, 0, Ui.dp(c, 2));
        unit.setGravity(Gravity.BOTTOM);
        valueRow.addView(unit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(valueRow, Ui.fill());
        hero.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = Ui.column(c);
        right.setGravity(Gravity.END);
        sender = mini(c, right, "发送端", "--");
        min = mini(c, right, "最低", "--");
        max = mini(c, right, "最高", "--");
        hero.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(hero, Ui.fill());

        verdict = Ui.text(c, "", 12f, Ui.INK, false);
        verdict.setLineSpacing(Ui.dp(c, 1), 1f);
        verdict.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = Ui.dp(c, 10);
        addView(verdict, vlp);

        LinearLayout metrics = Ui.row(c);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(c, 10);
        retr = metric(c, metrics, "重传", "--");
        loss = metric(c, metrics, "丢包", "--");
        jitter = metric(c, metrics, "抖动", "--");
        ratio = metric(c, metrics, "协商占比", "--");
        addView(metrics, mlp);

        compare = Ui.text(c, "", 11.5f, Ui.BLUE, false);
        compare.setPadding(0, Ui.dp(c, 9), 0, 0);
        compare.setVisibility(GONE);
        addView(compare, Ui.fill());

        aggregate = Ui.text(c, "", 11.5f, Ui.INK, true);
        aggregate.setPadding(0, Ui.dp(c, 6), 0, 0);
        aggregate.setVisibility(GONE);
        addView(aggregate, Ui.fill());

        LinearLayout actionsBox = Ui.column(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(c, 11);

        topRow = Ui.row(c);
        again = Ui.primary(c, "再测一次", new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onAgain();
                }
            }
        });
        repeat = Ui.ghost(c, "连测 3 次", new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onRepeat();
                }
            }
        });
        topRow.addView(again, Ui.w(1.25f));
        Ui.margin(c, again, 0, 0, 7, 0);
        topRow.addView(repeat, Ui.w(1.15f));
        Ui.margin(c, repeat, 7, 0, 0, 0);
        actionsBox.addView(topRow, Ui.fill());

        bottomRow = Ui.row(c);
        share = Ui.ghost(c, "分享摘要", new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onShare();
                }
            }
        });
        csv = Ui.ghost(c, "导出 CSV", new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actions != null) {
                    actions.onCsv();
                }
            }
        });
        bottomRow.addView(share, Ui.w(1f));
        Ui.margin(c, share, 0, 0, 7, 0);
        bottomRow.addView(csv, Ui.w(1f));
        Ui.margin(c, csv, 7, 0, 0, 0);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp2.topMargin = Ui.dp(c, 7);
        actionsBox.addView(bottomRow, blp2);

        addView(actionsBox, blp);
    }

    void setActions(Actions a) {
        actions = a;
    }

    /**
     * 展示一次结果。label 可为空；subtitle 是时间 + 参数；compare/aggregate 为空则不显示。
     * canRepeat=false 时隐藏复测按钮（服务端没有「再打一次」的概念）。
     */
    void show(Stats st, String label, String subtitle, String compareText,
              String aggregateText, boolean canRepeat, boolean canExport) {
        if (st == null) {
            hide();
            return;
        }
        String name = label == null ? "" : label.trim();
        title.setText(name.isEmpty() ? "本次结果" : name);
        labelBtn.setText(name.isEmpty() ? "加标签" : "改标签");
        this.subtitle.setText(subtitle == null ? "" : subtitle);

        boolean hasReceiver = st.receiverMb >= 0;
        role.setText(hasReceiver ? "接收端" : "平均速率");
        value.setText(Stats.fmtMb(st.mainMb()));
        sender.setText("发送端 " + (st.senderMb >= 0 ? Stats.fmtMb(st.senderMb) : "--"));
        min.setText("最低 " + Stats.fmtMb(st.minMb));
        max.setText("最高 " + Stats.fmtMb(st.maxMb));
        retr.setText(st.retr >= 0 ? String.valueOf(st.retr) : "--");
        loss.setText(st.lostPct >= 0 ? Stats.fmtPct(st.lostPct) + "%" : "--");
        jitter.setText(st.jitter >= 0 ? Stats.fmtPct(st.jitter) + "ms" : "--");
        if (st.linkMbps > 0 && st.mainMb() > 0) {
            ratio.setText(Math.round(st.mainMb() * 100.0 / st.linkMbps) + "%");
        } else {
            ratio.setText("--");
        }

        int level = st.level(st.linkMbps);
        verdict.setText(st.verdict(st.linkMbps));
        verdict.setBackground(Ui.round(getContext(),
                level == 2 ? Ui.DANGER_BG : level == 1 ? Ui.WARN_BG : Ui.SUCCESS_BG, 0, 9f));
        verdict.setTextColor(level == 2 ? Ui.RED : level == 1 ? Ui.AMBER : Ui.GREEN);

        compare.setText(compareText == null ? "" : compareText);
        compare.setVisibility(compareText == null || compareText.isEmpty() ? GONE : VISIBLE);
        aggregate.setText(aggregateText == null ? "" : aggregateText);
        aggregate.setVisibility(aggregateText == null || aggregateText.isEmpty() ? GONE : VISIBLE);

        topRow.setVisibility(canRepeat ? VISIBLE : GONE);
        csv.setVisibility(canExport ? VISIBLE : GONE);

        setVisibility(VISIBLE);
        requestLayout();
        // 跑完后把结果卡滚进视野：用户可能正停在日志底部
        post(new Runnable() {
            @Override
            public void run() {
                requestRectangleOnScreen(new android.graphics.Rect(
                        0, 0, getWidth(), getHeight()), true);
            }
        });
    }

    /** 用户补/改标签后，只更新卡片标题。 */
    void setLabel(String label) {
        String name = label == null ? "" : label.trim();
        title.setText(name.isEmpty() ? "本次结果" : name);
        labelBtn.setText(name.isEmpty() ? "加标签" : "改标签");
    }

    void hide() {
        setVisibility(GONE);
    }

    /** 一行「标签 值」，返回数值控件，方便后续更新。 */
    private static TextView mini(Context c, LinearLayout parent, String name, String initial) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.END);
        row.setPadding(0, Ui.dp(c, 1), 0, Ui.dp(c, 1));
        TextView label = Ui.text(c, name, 10.5f, Ui.MUTED, false);
        row.addView(label);
        TextView v = Ui.text(c, initial, 11.5f, Ui.INK, false);
        v.setPadding(Ui.dp(c, 5), 0, 0, 0);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(v);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    /** 四等分的指标格，返回数值控件。 */
    private static TextView metric(Context c, LinearLayout parent, String name, String initial) {
        LinearLayout col = Ui.column(c);
        TextView label = Ui.text(c, name, 9.5f, Ui.MUTED, false);
        col.addView(label, Ui.fill());
        TextView v = Ui.text(c, initial, 13f, Ui.INK, true);
        v.setPadding(0, Ui.dp(c, 2), 0, 0);
        col.addView(v, Ui.fill());
        parent.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }
}
