package com.wifitput.android.ui;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * 竖屏扫码界面。
 *
 * zxing 自带的 CaptureActivity 在它自己的 aar manifest 里硬写了 sensorLandscape，
 * 别的模块的 manifest 声明改不了，只能继承一份、在我们自己的 manifest 里声明 portrait。
 * 取景框、提示语、权限处理全部沿用父类，这里不写任何逻辑。
 */
public class QrCaptureActivity extends CaptureActivity {
}
