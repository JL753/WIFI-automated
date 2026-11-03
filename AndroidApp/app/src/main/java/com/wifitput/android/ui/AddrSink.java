package com.wifitput.android.ui;

/** 任何「帮用户把对端地址填好」的模块都通过这个口子回写输入框。 */
interface AddrSink {
    void fill(String ip, int port);
}
