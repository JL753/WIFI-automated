package com.wifitput.android.ui;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.wifitput.android.core.Bundle;
import com.wifitput.android.core.Record;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 把逐秒记录导出成 CSV，用系统分享面板发出去。
 * 列顺序和 PC 端 report.py 的「逐秒明细.csv」完全一致，Excel 能直接打开（带 BOM）。
 */
final class CsvExport {

    private static final String HEADER =
            "时间,测试,类型,线程,起,止,传输量,单位,速率,速率单位,重传或数据报,抖动ms,丢包,总包,丢包率,方向";

    private CsvExport() {
    }

    static void shareBundle(Context c, String label, String startedAt, Bundle bundle) {
        if (c == null || bundle == null || bundle.records.isEmpty()) {
            toast(c, "这次没有逐秒数据，无法导出 CSV");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF').append(HEADER).append("\r\n");
        for (Record r : bundle.records) {
            sb.append(row(r.t, r.test, r.kind, r.stream,
                    fmt(r.start), fmt(r.end), fmt(r.transfer), r.unit,
                    fmt(r.rate), r.rateUnit,
                    r.retr == null || r.retr.isEmpty() ? r.total : r.retr,
                    emptyDash(r.jitter), emptyDash(r.lost), emptyDash(r.total),
                    emptyDash(r.lostPct), r.role)).append("\r\n");
        }
        share(c, label, startedAt, sb.toString());
    }

    /** 历史详情里只有结果包 JSON 字符串，直接从 JSON 里取逐秒行。 */
    static void shareJson(Context c, String label, String startedAt, String bundleJson) {
        if (c == null || bundleJson == null || bundleJson.trim().isEmpty()) {
            toast(c, "这条记录没有结果包，无法导出 CSV");
            return;
        }
        try {
            JSONObject o = new JSONObject(bundleJson);
            JSONArray results = o.optJSONArray("results");
            JSONObject r0 = results == null ? null : results.optJSONObject(0);
            JSONArray arr = r0 == null ? null : r0.optJSONArray("records");
            if (arr == null || arr.length() == 0) {
                toast(c, "这条记录没有逐秒数据，无法导出 CSV");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append('\uFEFF').append(HEADER).append("\r\n");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                String retr = r.optString("retr", "");
                String total = r.optString("total", "");
                sb.append(row(
                        r.optString("t", ""), r.optString("test", ""),
                        r.optString("kind", ""), r.optString("stream", ""),
                        fmt(r.optDouble("start", 0)), fmt(r.optDouble("elapsed", r.optDouble("end", 0))),
                        fmt(r.optDouble("transfer", 0)), r.optString("unit", ""),
                        fmt(r.optDouble("rate", 0)), r.optString("rate_unit", ""),
                        retr.isEmpty() ? total : retr,
                        emptyDash(r.optString("jitter", "")), emptyDash(r.optString("lost", "")),
                        emptyDash(r.optString("total", "")), emptyDash(r.optString("lost_pct", "")),
                        r.optString("role", ""))).append("\r\n");
            }
            share(c, label, startedAt, sb.toString());
        } catch (Exception e) {
            toast(c, "导出失败：" + e.getMessage());
        }
    }

    private static void share(Context c, String label, String startedAt, String csv) {
        try {
            File dir = new File(c.getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) {
                toast(c, "创建导出目录失败");
                return;
            }
            String name = "WiFiTput_" + safe(label, "result") + "_" + safe(startedAt, "run") + ".csv";
            File f = new File(dir, name);
            FileOutputStream out = new FileOutputStream(f);
            try {
                OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                w.write(csv);
                w.flush();
            } finally {
                out.close();
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    c, c.getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/csv");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "WiFiTput 逐秒明细");
            send.putExtra(Intent.EXTRA_TEXT, "WiFiTput 逐秒明细（" + safe(label, "") + "）");
            send.setClipData(ClipData.newRawUri("csv", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "导出逐秒明细 CSV");
            if (!(c instanceof android.app.Activity)) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            c.startActivity(chooser);
        } catch (Exception e) {
            toast(c, "导出失败：" + e.getMessage());
        }
    }

    private static String row(String... v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(v[i]));
        }
        return sb.toString();
    }

    private static String cell(String s) {
        String x = s == null ? "" : s;
        if (x.indexOf(',') >= 0 || x.indexOf('"') >= 0 || x.indexOf('\n') >= 0) {
            return "\"" + x.replace("\"", "\"\"") + "\"";
        }
        return x;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(Locale.US, "%.3f", v);
    }

    private static String emptyDash(String s) {
        return s == null || s.isEmpty() ? "--" : s;
    }

    private static String safe(String s, String def) {
        String x = s == null ? "" : s.trim();
        if (x.isEmpty()) {
            return def;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < x.length() && sb.length() < 40; i++) {
            char ch = x.charAt(i);
            sb.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' ? ch : '_');
        }
        return sb.length() == 0 ? def : sb.toString();
    }

    private static void toast(Context c, String s) {
        if (c != null) {
            Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
        }
    }
}
