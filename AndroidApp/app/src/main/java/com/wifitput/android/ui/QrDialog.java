package com.wifitput.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.wifitput.android.core.Qr;

/** 我的地址二维码的展示与扫码。全部基于纯 android.app + zxing。 */
public final class QrDialog {

    /** 请求码，宿主 Activity 用它识别扫码结果。 */
    public static final int REQ_SCAN = 1102;

    /** zxing-android-embedded 回传扫描文本用的 extra 名（Intents.Scan.RESULT 的字面值）。 */
    private static final String EXTRA_SCAN_RESULT = "SCAN_RESULT";

    private QrDialog() {
    }

    /** 弹出「我的地址」二维码。ip 为空时只提示先连上网络。 */
    public static void showMine(Context c, String ip, int port) {
        try {
            if (ip == null || ip.trim().isEmpty()) {
                new AlertDialog.Builder(c)
                        .setTitle("我的地址")
                        .setMessage("本机还没有可用的 WiFi 地址，先连上 WiFi 或开启热点再来生成二维码。")
                        .setPositiveButton("确定", null)
                        .show();
                return;
            }

            String text = Qr.encode(ip, port);
            Bitmap bm = Qr.bitmap(text, 640);
            if (bm == null) {
                new AlertDialog.Builder(c)
                        .setTitle("我的地址")
                        .setMessage("二维码生成失败。")
                        .setPositiveButton("确定", null)
                        .show();
                return;
            }

            LinearLayout box = Ui.column(c);
            int pad = Ui.dp(c, 20);
            box.setPadding(pad, pad, pad, pad);

            ImageView img = new ImageView(c);
            img.setImageBitmap(bm);
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(img, Ui.fill());

            Ui.gap(c, box, 10);

            TextView addr = Ui.text(c, text, 11.5f, Ui.GREY, false);
            addr.setTextIsSelectable(true);
            box.addView(addr, Ui.fill());

            Ui.gap(c, box, 8);

            TextView tip = Ui.text(c,
                    "让另一台手机在「扫描对端」旁边点「扫一扫」，对着这个码扫一下就能自动填好地址。",
                    12.5f, Ui.GREY, false);
            box.addView(tip, Ui.fill());

            new AlertDialog.Builder(c)
                    .setTitle("我的二维码")
                    .setView(box)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Exception e) {
            // 二维码只是辅助手段，弹窗环节出任何问题都不该把界面弄崩
        }
    }

    /** 拉起扫码界面。a 必须是 Activity。 */
    public static void scan(Activity a) {
        try {
            IntentIntegrator ii = new IntentIntegrator(a);
            ii.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            ii.setPrompt("把对端的二维码放进框里");
            ii.setBeepEnabled(false);
            ii.setOrientationLocked(false);
            ii.setRequestCode(REQ_SCAN);
            ii.initiateScan();
        } catch (Exception e) {
            // 相机或扫码界面不可用时静默失败，不崩溃
        }
    }

    /** 在宿主 Activity 的 onActivityResult 里调用；拿到地址返回 {ip,port}，否则 null。 */
    public static String[] result(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_SCAN) {
            return null;
        }
        try {
            String s = null;
            try {
                IntentResult r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
                if (r != null) {
                    s = r.getContents();
                }
            } catch (Throwable ignored) {
                s = null;
            }
            if (s == null && data != null) {
                s = data.getStringExtra(EXTRA_SCAN_RESULT);
            }
            if (s == null || s.trim().isEmpty()) {
                return null;
            }
            return Qr.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
