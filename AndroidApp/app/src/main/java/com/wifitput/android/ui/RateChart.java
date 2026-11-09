package com.wifitput.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.wifitput.android.core.Record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 打流时的实时速率曲线：横轴是本次测试的秒数，纵轴是 Mb/s，像股票分时图一样边跑边往右画。
 *
 * 一秒一个点：多流时把各流加起来，有 [SUM] 就用 [SUM]；
 * 结尾那条从 0 开始、跨度大于一个采样间隔的汇总行会被丢掉，否则曲线末端会多出一个「整段平均值」。
 *
 * 只接收 {@link #add(Record)} 的回调，线程安全靠调用方保证（界面里统一 post 到主线程）。
 */
class RateChart extends View {

    private static final int SERIES = 0xFF1565C0;
    /** 曲线下方的面积只是淡淡一层，盖住网格就太吵了。 */
    private static final int SERIES_WASH = 0x1A1565C0;
    private static final int GRID = 0xFFEDF0F4;
    private static final int AXIS = 0xFFD9DEE5;
    private static final int INK = 0xFF212121;
    private static final int MUTED = 0xFF6B7480;
    private static final int UP = 0xFFC62828;
    private static final int DOWN = 0xFF2E7D32;

    /** 1 秒采样跑满一小时也就 3600 个点，再长就丢掉最早的，内存和绘制都稳。 */
    private static final int MAX_POINTS = 3600;

    private final List<Float> xs = new ArrayList<Float>();
    private final List<Float> ys = new ArrayList<Float>();
    /** 当前这一秒里各流的速率，跨到下一秒就汇总成一个点。 */
    private final Map<String, Float> bucket = new LinkedHashMap<String, Float>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path area = new Path();

    private double bucketEnd = Double.NaN;
    private boolean bucketHasSum;
    /** 正常的逐秒采样跨度，拿来识别结尾的整段汇总行。 */
    private double typicalSpan;
    /** 本次测试的总时长，已知时横轴固定成 0..总时长，曲线从左往右长。 */
    private float windowSec;
    private float axisMaxSec = 10f;
    /** 服务端看到新连接时先挂个标记，等真有数据再把旧曲线换掉。 */
    private boolean pendingRestart;
    /** 已经点了开始、还在等第一条数据，空状态文案要跟着变。 */
    private boolean armed;

    RateChart(Context c) {
        super(c);
        setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 142)));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("实时速率曲线，暂无数据");
    }

    /** 新一轮测试开始前清空。durationSeconds <= 0 表示不知道总时长（当服务端时）。 */
    void reset(int durationSeconds) {
        xs.clear();
        ys.clear();
        bucket.clear();
        bucketEnd = Double.NaN;
        bucketHasSum = false;
        typicalSpan = 0;
        windowSec = Math.max(0, durationSeconds);
        axisMaxSec = windowSec > 0 ? windowSec : 10f;
        pendingRestart = false;
        armed = true;
        setContentDescription("实时速率曲线，暂无数据");
        invalidate();
    }

    /**
     * 当服务端时，log 里出现「Accepted connection」说明对方开了新的一轮。
     * 先记下、等真的收到逐秒数据才清空——探活那种连上就没下文的连接不该把图清掉。
     */
    void restartOnNextSample() {
        pendingRestart = true;
    }

    /** 喂一条 iperf3 逐秒记录；同一秒的多流记录会自动合并。 */
    void add(Record r) {
        if (r == null || r.end <= r.start) {
            return;
        }
        // 时间从 0 重来（对方接了新的一轮），或者上面挂了 restart 标记：换一条新曲线。
        // 这两步必须在下面对跨度的判断之前，否则新一轮换了采样间隔会被旧间隔误杀。
        double newest = xs.isEmpty() ? 0 : xs.get(xs.size() - 1);
        if (!Double.isNaN(bucketEnd)) {
            newest = Math.max(newest, bucketEnd);
        }
        if (pendingRestart || (newest > 0 && r.end + 0.5 < newest)) {
            pendingRestart = false;
            xs.clear();
            ys.clear();
            bucket.clear();
            bucketHasSum = false;
            bucketEnd = Double.NaN;
            typicalSpan = 0;
            axisMaxSec = windowSec > 0 ? windowSec : 10f;
        }

        double span = r.end - r.start;
        if (typicalSpan <= 0) {
            typicalSpan = span;
        } else if (r.start <= 0.001 && span > typicalSpan + 0.05) {
            // 收尾汇总行（0.00-10.00 sec）从 0 起、跨度比一个采样间隔长，不是逐秒采样；
            // 最后那个不足一个间隔的尾点（如 9-10s）跨度更短，照常留下
            return;
        }
        float mbps = toMbps(r.rate, r.rateUnit);

        if (!Double.isNaN(bucketEnd) && Math.abs(r.end - bucketEnd) > 0.001) {
            flush();
        }
        if (Double.isNaN(bucketEnd)) {
            bucketEnd = r.end;
        }
        if ("SUM".equalsIgnoreCase(r.stream)) {
            bucketHasSum = true;
            bucket.clear();
            bucket.put("SUM", mbps);
        } else if (!bucketHasSum) {
            bucket.put(r.stream, mbps);
        }
        invalidate();
    }

    /** 测试结束时把最后一个还没满一秒的采样落下来。 */
    void finish() {
        flush();
        armed = false;
        invalidate();
    }

    private void flush() {
        if (Double.isNaN(bucketEnd)) {
            return;
        }
        float sum = 0f;
        for (Float v : bucket.values()) {
            sum += v;
        }
        bucket.clear();
        bucketHasSum = false;
        double end = bucketEnd;
        bucketEnd = Double.NaN;
        if (sum < 0f) {
            sum = 0f;
        }
        if (xs.size() >= MAX_POINTS) {
            xs.remove(0);
            ys.remove(0);
        }
        xs.add((float) end);
        ys.add(sum);
        axisMaxSec = Math.max(axisMaxSec, (float) end);
        if (windowSec > 0) {
            axisMaxSec = Math.max(axisMaxSec, windowSec);
        }
        setContentDescription("实时速率曲线，当前 " + fmt(sum) + " Mb/s，共 "
                + ys.size() + " 个采样点");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float pad = Ui.dp(getContext(), 10);
        float plotL = pad + Ui.dp(getContext(), 36);
        float plotR = w - pad;
        float plotT = pad + Ui.dp(getContext(), 50);
        float plotB = h - pad - Ui.dp(getContext(), 16);
        if (plotR <= plotL + Ui.dp(getContext(), 20) || plotB <= plotT + Ui.dp(getContext(), 10)) {
            return;
        }

        boolean has = !ys.isEmpty();
        float latest = has ? ys.get(ys.size() - 1) : 0f;
        float prev = ys.size() >= 2 ? ys.get(ys.size() - 2) : latest;
        float peak = 0f;
        for (int i = 0; i < ys.size(); i++) {
            if (ys.get(i) > peak) {
                peak = ys.get(i);
            }
        }
        float maxV = niceMax(peak);

        drawHeader(canvas, pad, plotR, has, latest, prev, peak);
        drawGrid(canvas, plotL, plotR, plotT, plotB, maxV);
        if (has) {
            drawSeries(canvas, plotL, plotR, plotT, plotB, maxV);
        } else {
            drawEmpty(canvas, plotL, plotR, plotT, plotB);
        }
        drawAxisLabels(canvas, plotL, plotR, plotB);
    }

    private void drawHeader(Canvas c, float left, float right, boolean has,
                            float latest, float prev, float peak) {
        float pad = Ui.dp(getContext(), 10);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(sp(10.5f));
        text.setColor(MUTED);
        c.drawText("实时速率", left, pad + sp(10.5f), text);

        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(sp(21f));
        text.setColor(has ? INK : MUTED);
        String value = has ? fmt(latest) : "--";
        float valueBase = pad + Ui.dp(getContext(), 38);
        c.drawText(value, left, valueBase, text);
        float valueW = text.measureText(value);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(sp(10.5f));
        text.setColor(MUTED);
        c.drawText("Mb/s", left + valueW + Ui.dp(getContext(), 6), valueBase - Ui.dp(getContext(), 1), text);

        if (!has) {
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        // 右侧：相对上一采样的涨跌（红涨绿跌，和股票一致）+ 峰值
        text.setTextAlign(Paint.Align.RIGHT);
        float delta = latest - prev;
        boolean up = delta > 0.05f;
        boolean down = delta < -0.05f;
        String arrow = up ? "▲" : down ? "▼" : "—";
        String sign = up ? "+" : down ? "-" : "";
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(sp(12f));
        text.setColor(up ? UP : down ? DOWN : MUTED);
        c.drawText(arrow + " " + sign + fmtDelta(Math.abs(delta)),
                right, pad + sp(12f), text);

        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(sp(10.5f));
        text.setColor(MUTED);
        c.drawText("峰值 " + fmt(peak) + " Mb/s", right, pad + Ui.dp(getContext(), 31), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** 横向三条实心细网格（0 / 50% / 100%），颜色只比底色深一点点，不抢曲线。 */
    private void drawGrid(Canvas c, float l, float r, float t, float b, float maxV) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 1) * 0.75f));
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(sp(9.5f));
        for (int i = 0; i <= 2; i++) {
            float y = b - (b - t) * i / 2f;
            paint.setColor(i == 0 ? AXIS : GRID);
            c.drawLine(l, y, r, y, paint);
            text.setColor(MUTED);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(fmtTick(maxV * i / 2f), l - Ui.dp(getContext(), 5), y + sp(3.2f), text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSeries(Canvas c, float l, float r, float t, float b, float maxV) {
        float w = r - l;
        float h = b - t;
        float span = Math.max(axisMaxSec, 0.001f);
        line.reset();
        area.reset();
        float lastX = l;
        float lastY = b;
        for (int i = 0; i < xs.size(); i++) {
            float x = l + Math.min(w, Math.max(0f, xs.get(i) / span * w));
            float y = b - Math.min(1f, Math.max(0f, ys.get(i) / maxV)) * h;
            if (i == 0) {
                line.moveTo(x, y);
                area.moveTo(x, b);
            } else {
                line.lineTo(x, y);
            }
            area.lineTo(x, y);
            lastX = x;
            lastY = y;
        }
        area.lineTo(lastX, b);
        area.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SERIES_WASH);
        c.drawPath(area, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, Ui.dp(getContext(), 2)));
        paint.setColor(SERIES);
        c.drawPath(line, paint);

        // 末端点：白圈垫底，压在曲线上也看得清「现在在哪」
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(lastX, lastY, Ui.dp(getContext(), 6), paint);
        paint.setColor(SERIES);
        c.drawCircle(lastX, lastY, Ui.dp(getContext(), 4), paint);
    }

    private void drawAxisLabels(Canvas c, float l, float r, float b) {
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(sp(9.5f));
        text.setColor(MUTED);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("0s", l, b + Ui.dp(getContext(), 12), text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmtSeconds(axisMaxSec), r, b + Ui.dp(getContext(), 12), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawEmpty(Canvas c, float l, float r, float t, float b) {
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(sp(10.5f));
        text.setColor(MUTED);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(armed ? "正在等待第一秒数据…" : "开始打流后，这里按秒画实时速率",
                (l + r) / 2f, (t + b) / 2f + sp(4f), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    /** iperf3 的速率单位换算成 Mb/s；只认 bit 系列，Mbits/sec 原样用。 */
    static float toMbps(double rate, String unit) {
        String u = unit == null ? "" : unit;
        if (u.startsWith("G")) {
            return (float) (rate * 1000);
        }
        if (u.startsWith("T")) {
            return (float) (rate * 1000000);
        }
        if (u.startsWith("K")) {
            return (float) (rate / 1000);
        }
        if (u.startsWith("bits")) {
            return (float) (rate / 1e6);
        }
        return (float) rate;
    }

    /**
     * 纵轴取整到 1 / 2 / 2.5 / 5 × 10^n，避免出现 187.3 这种上限。
     * 步子要细：212 Mb/s 取 250 而不是 500，否则曲线只占半张图。
     */
    static float niceMax(float v) {
        if (v < 1f) {
            return 1f;
        }
        double exp = Math.pow(10, Math.floor(Math.log10(v)));
        double f = v / exp;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
        return (float) (nice * exp);
    }

    private static String fmt(float v) {
        if (v >= 100f) {
            return String.format(Locale.US, "%.0f", v);
        }
        if (v >= 10f) {
            return String.format(Locale.US, "%.1f", v);
        }
        return String.format(Locale.US, "%.2f", v);
    }

    private static String fmtDelta(float v) {
        return String.format(Locale.US, v >= 100f ? "%.0f" : "%.1f", v);
    }

    private static String fmtTick(float v) {
        if (Math.abs(v) < 0.001f) {
            return "0";
        }
        String s = String.format(Locale.US, v >= 10f ? "%.0f" : "%.2f", v);
        if (s.indexOf('.') >= 0) {
            while (s.endsWith("0")) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    private static String fmtSeconds(float v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) {
            return String.valueOf((long) Math.rint(v)) + "s";
        }
        return String.format(Locale.US, "%.1fs", v);
    }
}
