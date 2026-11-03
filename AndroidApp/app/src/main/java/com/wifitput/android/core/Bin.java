package com.wifitput.android.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 拿到可执行的 iperf3。
 *
 * 两条硬约束（都是实测踩出来的）：
 * 1) 不能把二进制当 asset 释放到 filesDir 再执行 —— Android 10 起该分区 noexec，
 *    ProcessBuilder 报 "error=13, Permission denied"。所以放成 jniLibs 里的 .so，
 *    系统安装时解压到 /data/app/.../lib/arm64，那个目录可执行。
 * 2) 二进制必须是动态链接 bionic 的。原先用的 musl 静态版在 app 进程里会被
 *    自己的 seccomp 过滤器判 SIGSYS（退出码 159 "Bad system call"）；
 *    换成本目录下这套（iperf3 + libiperf.so + libcrypto.so）后正常。
 */
public class Bin {

    /** iperf3 主程序（原名 iperf3，为打进 jniLibs 改名）。 */
    public static final String LIB_NAME = "libiperf3.so";

    private static File cached;

    /**
     * 跑 iperf3 时必须带的环境变量。
     *
     * 三个文件之间靠 SONAME 互相找：libiperf3.so 要 libiperf.so、libiperf.so 要
     * libcrypto.so。它们的 RUNPATH 已被改成 $ORIGIN（= 可执行文件所在目录），
     * 这里再显式补一份 LD_LIBRARY_PATH 兜底，避免不同 ROM 的 linker 对 $ORIGIN
     * 处理不一致。
     */
    public static Map<String, String> env(Context ctx) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("PATH", "/system/bin:/system/xbin:/vendor/bin");
        m.put("LD_LIBRARY_PATH", ctx.getApplicationInfo().nativeLibraryDir);
        return m;
    }

    public static synchronized File iperf3(Context ctx) throws IOException {
        if (cached != null && cached.canExecute()) {
            return cached;
        }
        File f = new File(ctx.getApplicationInfo().nativeLibraryDir, LIB_NAME);
        if (!f.isFile()) {
            throw new IOException("[错误] 找不到内置 iperf3\n"
                    + "原因: 安装包里的 " + LIB_NAME + " 没有被解压到 " + f.getParent() + "\n"
                    + "处理办法: 卸载后重新安装本应用（升级安装可能沿用旧的原生库布局）");
        }
        if (!f.canExecute()) {
            Shell.run(new String[]{"chmod", "755", f.getAbsolutePath()}, env(ctx), null);
        }
        if (!f.canExecute()) {
            throw new IOException("[错误] 内置 iperf3 不可执行：" + f
                    + "\n处理办法: 卸载后重新安装本应用");
        }
        cached = f;
        return f;
    }

    /** 版本号，例如 "iperf 3.22 (cJSON 1.7.15)"；失败返回空串。 */
    public static String version(Context ctx) {
        try {
            String t = Shell.capture(new String[]{iperf3(ctx).getAbsolutePath(), "-v"}, env(ctx));
            for (String line : t.split("\n")) {
                if (line.startsWith("iperf 3")) {
                    return line.trim();
                }
            }
            return "";
        } catch (IOException e) {
            return "";
        }
    }
}
