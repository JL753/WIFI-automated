package com.wifitput.android.ui;

/** 主界面持有的面板公共能力：四个页签（三模式 + 连PC）都实现它。 */
interface PanelHost {

    interface StatusSink {
        void onStatus(PanelHost p, boolean active, boolean clientRun);
    }

    void setStatusSink(StatusSink s);

    boolean isBusy();

    boolean isClientRun();

    long runStartMs();

    String modeTitle();

    void stopAll();

    void refreshHotspot();

    /** 换外观/重建页面前把未开始的设置存下来。 */
    void persistSettings();
}
