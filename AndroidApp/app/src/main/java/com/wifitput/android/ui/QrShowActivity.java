package com.wifitput.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wifitput.android.core.Qr;

/**
 * 竖屏展示投屏码。
 *
 * 单独开一个 Activity 而不是弹窗：码要被另一台手机的相机隔着几十厘米扫到，
 * 屏幕越大越好；而且只有 Activity 才能靠 manifest 锁方向，弹窗做不到。
 */
public class QrShowActivity extends Activity {

    /** 要编码的投屏码文本。 */
    public static final String EXTRA_QR = "qr_text";

    /** 二维码下方的说明文字，可为空。 */
    public static final String EXTRA_TIP = "qr_tip";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.applyBars(this);

        String text = "";
        String tip = "";
        Intent it = getIntent();
        if (it != null) {
            String q = it.getStringExtra(EXTRA_QR);
            String t = it.getStringExtra(EXTRA_TIP);
            text = q == null ? "" : q;
            tip = t == null ? "" : t;
        }

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.PAGE_BG);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));

        root.addView(Ui.text(this, "投屏码", 19f, Ui.BLUE, true));

        // 二维码吃掉除标题和按钮外的全部高度，竖屏时才够大
        LinearLayout mid = Ui.column(this);
        mid.setGravity(Gravity.CENTER);
        mid.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Bitmap bm = Qr.bitmap(text, 720);
        if (bm != null) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(bm);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setAdjustViewBounds(true);
            mid.addView(img, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // 生成失败时给一句能照做的替代方案，而不是留一片空白
            TextView bad = Ui.text(this, "二维码生成失败，请改用「扫码连接」或手动填地址",
                    14f, Ui.RED, false);
            bad.setGravity(Gravity.CENTER);
            mid.addView(bad, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        root.addView(mid);

        if (tip.length() > 0) {
            TextView t = Ui.text(this, tip, 12.5f, Ui.GREY, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            root.addView(t);
        }

        root.addView(Ui.primary(this, "关闭", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), Ui.fill());

        setContentView(root);
    }
}
