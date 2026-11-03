package com.wifitput.android.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.wifitput.android.core.Prefs;

/**
 * 结果回传 PC 的地址设置。原来这三个 Tab 各有一张「结果回传」卡片，
 * 三个页填同一份 Prefs 其实是重复的，现在收进顶栏「⋯」菜单里只留一个入口。
 */
public final class PcDialog {

    private PcDialog() {
    }

    public static void show(Context c) {
        final Context ctx = c;
        LinearLayout box = Ui.column(ctx);
        box.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 8), Ui.dp(ctx, 18), 0);

        final EditText hostEdit = Ui.input(ctx, Prefs.getStr(ctx, "pc_host", ""), 0);
        final EditText portEdit = Ui.number(ctx, String.valueOf(Prefs.getInt(ctx, "pc_port", 8737)), 78);
        LinearLayout row = Ui.row(ctx);
        LinearLayout hostField = Ui.field(ctx, "PC 端地址", hostEdit, 1f);
        Ui.margin(ctx, hostField, 0, 0, 10, 0);
        row.addView(hostField);
        row.addView(Ui.narrowField(ctx, "端口", portEdit, 78));
        box.addView(row);
        box.addView(Ui.hint(ctx, "填 PC 端接收器窗口上显示的地址；不填也能测，结果只留在手机上"));

        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("设置结果回传 PC");
        b.setView(box);
        b.setNegativeButton("取消", null);
        b.setPositiveButton("保存", null);
        final AlertDialog dialog = b.show();
        // 默认的 positiveButton 点完就关窗，端口越界时要能拦下来，所以自己接管点击
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        int port = Ui.num(portEdit, 8737);
                        if (port < 1 || port > 65535) {
                            Toast.makeText(ctx, "端口要在 1 到 65535 之间", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Prefs.putStr(ctx, "pc_host", hostEdit.getText().toString().trim());
                        Prefs.putInt(ctx, "pc_port", port);
                        Toast.makeText(ctx, "已保存，下次打流会回传到这个地址", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                });
    }
}
