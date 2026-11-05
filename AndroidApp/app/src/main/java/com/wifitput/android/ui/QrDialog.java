package com.wifitput.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.wifitput.android.core.Qr;

/** 投屏码的展示与扫码。展示走横屏的 QrShowActivity，扫码走 zxing 的 CaptureActivity。 */
public final class QrDialog {

    /** 请求码，宿主 Activity 用它识别扫码结果。 */
    public static final int REQ_SCAN = 1102;

    /** zxing-android-embedded 回传扫描文本用的 extra 名（Intents.Scan.RESULT 的字面值）。 */
    private static final String EXTRA_SCAN_RESULT = "SCAN_RESULT";

    private QrDialog() {
    }

    /** 用横屏的 QrShowActivity 展示投屏码。tip 可为空。 */
    public static void showCast(Context c, String text, String tip) {
        if (c == null) {
            return;
        }
        try {
            Intent it = new Intent(c, QrShowActivity.class);
            it.putExtra(QrShowActivity.EXTRA_QR, text == null ? "" : text);
            it.putExtra(QrShowActivity.EXTRA_TIP, tip == null ? "" : tip);
            // 从非 Activity 的 Context 起页面必须带这个标志，否则系统直接抛异常
            if (!(c instanceof Activity)) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            c.startActivity(it);
        } catch (Exception e) {
            // 页面起不来只是没法扫码，不能把宿主界面弄崩
            try {
                Toast.makeText(c, "打不开投屏码页面", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
                // Toast 在极端情况下也会失败，忽略
            }
        }
    }

    /** 拉起扫码界面。a 必须是 Activity。 */
    public static void scan(Activity a) {
        try {
            IntentIntegrator ii = new IntentIntegrator(a);
            ii.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            ii.setPrompt("把对端的投屏码放进框里");
            ii.setBeepEnabled(false);
            // 竖屏：zxing 自带的 CaptureActivity 在它自己的 aar manifest 里写死了 sensorLandscape，
            // 覆盖不了，改用我们继承出来的 QrCaptureActivity（我们 manifest 里声明 portrait）。
            ii.setCaptureActivity(QrCaptureActivity.class);
            ii.setOrientationLocked(true);
            ii.setRequestCode(REQ_SCAN);
            ii.initiateScan();
        } catch (Exception e) {
            // 相机或扫码界面不可用时静默失败，不崩溃
        }
    }

    /** 在宿主 Activity 的 onActivityResult 里调用；拿到投屏码返回 Peer，否则 null。 */
    public static Qr.Peer result(int requestCode, int resultCode, Intent data) {
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
            return Qr.parsePeer(s);
        } catch (Exception e) {
            return null;
        }
    }
}
