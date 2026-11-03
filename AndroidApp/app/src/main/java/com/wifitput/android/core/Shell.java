package com.wifitput.android.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 调外部命令（iperf3）的唯一入口：统一环境变量、统一超时、逐行回调。 */
public class Shell {

    public interface LineSink {
        void onLine(String line);
    }

    /** 逐行读 stdout+stderr，返回退出码。sink 可为 null。 */
    public static int run(List<String> argv, Map<String, String> env, LineSink sink) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        if (env != null) {
            pb.environment().putAll(env);
        }
        final Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            if (sink != null) {
                sink.onLine(line);
            }
        }
        r.close();
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            return -1;
        }
    }

    public static int run(String[] argv, Map<String, String> env, LineSink sink) throws IOException {
        List<String> l = new ArrayList<String>();
        for (String a : argv) {
            l.add(a);
        }
        return run(l, env, sink);
    }

    /** 跑一条命令，把全部输出拼成字符串。 */
    public static String capture(String[] argv, Map<String, String> env) {
        final StringBuilder sb = new StringBuilder();
        try {
            run(argv, env, new LineSink() {
                @Override
                public void onLine(String line) {
                    sb.append(line).append('\n');
                }
            });
        } catch (IOException e) {
            sb.append("执行失败：").append(e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 起一个不需要等它的进程（后台服务端）。
     *
     * 不能用 run()：它读完 stdout 直到 EOF 才返回，而 sh 派生的后台子进程会一直
     * 攥着那条管道，readLine() 永远等不到 EOF —— 表现就是 iperf3 已经在监听了，
     * 界面却卡在「正在起服务端」。这里把三个标准流全部丢掉，start 完就走。
     */
    public static void launch(String[] argv, Map<String, String> env) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        if (env != null) {
            pb.environment().putAll(env);
        }
        // 故意不读它的输出也不 waitFor：sh 自己已经把 iperf3 的输出重定向进日志文件了，
        // 这条管道上没有数据，丢掉 Process 引用即可。
        pb.start();
    }

    public static void kill(String pattern) {
        try {
            run(new String[]{"sh", "-c", "pkill -9 -f '" + pattern + "' 2>/dev/null; true"}, null, null);
        } catch (IOException ignored) {
        }
    }

    public static boolean isRunning(String pattern) {
        try {
            int rc = run(new String[]{"sh", "-c", "pidof " + pattern + " >/dev/null 2>&1"}, null, null);
            return rc == 0;
        } catch (IOException e) {
            return false;
        }
    }
}
