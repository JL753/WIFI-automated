package com.wifitput.android.core;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 每次运行的本地历史：一条记录一个 JSON 文件，最多留 KEEP 条。
 * 服务端和客户端共用，界面只按 id 读。
 *
 * <p>除了私有目录，每次保存还镜像一份到 externalDir（getExternalFilesDir(null)/history）。
 * 原因：release 包不是 debuggable，adb 的 run-as 被直接拒绝（package not debuggable），
 * adb pull 拿不到 /data/data 下的私有目录；而 adb 能读能 pull 应用自己的外部目录。
 * 所以私有目录继续作为唯一权威副本（真机删 App 才会没），外部那份只是给 USB 导出用的镜像。
 */
public class History {

    public static final int KEEP = 50;

    private static final String DIR = "history";
    private static final String EXT = ".json";

    /** 一条历史。字段都是 String，读出来直接给界面用。 */
    public static class Item {
        public String id = "";        // 文件名去掉 .json
        public String title = "";     // 形如「WiFi · 2026-11-03 10:24:05」/「热点 · 接收 · 2026-11-03 10:24:05」
        public String label = "";     // 用户起的标签，如「客厅」「换信道后」；老记录为空
        public String mode = "";      // wifi / hotspot / p2p
        public String role = "client"; // client / server
        public String startedAt = "";
        public String finishedAt = "";
        public String summary = "";   // 多行文本，用 '\n' 分隔
        public String log = "";       // 完整日志文本
        public String bundle = "";    // 结果包 JSON 字符串；服务端记录为空串
    }

    private History() {
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /** USB 可见的历史目录：getExternalFilesDir(null)/history。外部存储不可用时返回 null。 */
    public static File externalDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            // 存储已卸载时这里是 null，没有可镜像的地方
            return null;
        }
        File d = new File(base, DIR);
        if (!d.exists()) {
            d.mkdirs();
        }
        // mkdirs 失败（只读挂载等）时目录仍不存在，按不可用处理
        return d.exists() ? d : null;
    }

    /** 给界面显示的 USB 可见目录路径；不可用时返回空串。 */
    public static String externalPath(Context ctx) {
        File d = externalDir(ctx);
        return d == null ? "" : d.getAbsolutePath();
    }

    /**
     * 把私有目录里的历史全量镜像到 externalDir：缺的补上、多的删掉。返回镜像后外侧存在的条数。
     * 外部存储的任何异常都吞掉返回 0 —— 导出是附加能力，不能让保存历史跟着失败。
     */
    public static int syncToExternal(Context ctx) {
        try {
            File ext = externalDir(ctx);
            if (ext == null) {
                return 0;
            }
            List<String> keep = new ArrayList<String>();
            File[] srcs = dir(ctx).listFiles();
            if (srcs != null) {
                for (File f : srcs) {
                    if (!f.isFile() || !f.getName().endsWith(EXT)) {
                        continue;
                    }
                    // 私有那份是空的说明这条记录本身坏了，外侧不留残壳
                    if (f.length() <= 0) {
                        continue;
                    }
                    if (copy(f, new File(ext, f.getName()))) {
                        keep.add(f.getName());
                    }
                    // 复制失败的半截文件不进 keep，下面的清理会把它删掉，下次保存再补
                }
            }
            File[] olds = ext.listFiles();
            if (olds != null) {
                for (File f : olds) {
                    if (!f.isFile() || !f.getName().endsWith(EXT) || keep.contains(f.getName())) {
                        continue;
                    }
                    if (!f.delete()) {
                        // 删不掉就留着，下次同步再删
                    }
                }
            }
            return countJson(ext);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 存一条，返回 id；超过 KEEP 条自动删最旧的。
     * 写盘失败抛 RuntimeException —— 历史存不下要让用户看见，不能悄悄吞掉。
     */
    public static String save(Context ctx, String mode, String role, String startedAt,
                              String finishedAt, String summary, String log, String bundleJson,
                              String label) {
        String m = mode == null || mode.trim().isEmpty() ? "wifi" : mode.trim();
        String r = "server".equals(role) ? "server" : "client";
        String start = startedAt == null ? "" : startedAt.trim();
        String stamp = digits(start);
        if (stamp.isEmpty()) {
            // 没给时间就用当前时间兜底，否则 id 变成 _wifi_client，两条记录会互相覆盖
            stamp = digits(Bundle.stamp());
        }
        String id = stamp + "_" + m + "_" + r;

        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title(m, r, start));
            o.put("label", label == null ? "" : label.trim());
            o.put("mode", m);
            o.put("role", r);
            o.put("started_at", start);
            o.put("finished_at", finishedAt == null ? "" : finishedAt);
            o.put("summary", summary == null ? "" : summary);
            o.put("log", log == null ? "" : log);
            o.put("bundle", bundleJson == null ? "" : bundleJson);
        } catch (JSONException e) {
            // key 全是常量，正常走不到这里
            throw new RuntimeException("历史记录字段构造失败：" + e.getMessage());
        }
        write(new File(dir(ctx), id + EXT), o.toString());
        trim(ctx);
        try {
            // 必须在 trim 之后同步，外侧才能跟着丢掉被裁掉的老记录
            syncToExternal(ctx);
        } catch (Exception e) {
            // 镜像失败不影响这次保存：私有目录那份才是权威的
        }
        return id;
    }

    /** 新的在前。单个文件解析失败就跳过，一条坏记录不能拖垮整个列表。 */
    public static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<Item>();
        File[] fs = dir(ctx).listFiles();
        if (fs == null) {
            return out;
        }
        // id 以时间数字开头，文件名倒序即时间倒序
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(EXT)) {
                continue;
            }
            Item it = parse(f);
            if (it != null) {
                out.add(it);
            }
        }
        return out;
    }

    /** 读不到或坏文件返回 null。 */
    public static Item load(Context ctx, String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        File f = new File(dir(ctx), id.trim() + EXT);
        if (!f.exists()) {
            return null;
        }
        return parse(f);
    }

    /** 给一条历史补/改标签；记录不存在或写失败返回 false。 */
    public static boolean updateLabel(Context ctx, String id, String label) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        File f = new File(dir(ctx), id.trim() + EXT);
        if (!f.exists()) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(read(f));
            o.put("label", label == null ? "" : label.trim());
            write(f, o.toString());
            try {
                syncToExternal(ctx);
            } catch (Exception ignored) {
                // 镜像失败不影响私有目录那份
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void delete(Context ctx, String id) {
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        File f = new File(dir(ctx), id.trim() + EXT);
        if (f.exists() && !f.delete()) {
            // 删不掉就留着，trim 下次还会再试一次
        }
        try {
            // 删完立刻同步，否则电脑那边还能 pull 到已删掉的记录
            syncToExternal(ctx);
        } catch (Exception e) {
            // 同步失败只影响导出，不动私有目录
        }
    }

    /**
     * 只留最新的 KEEP 条。只看文件名不解析内容 —— id 以 14 位时间数字开头，
     * 文件名倒序即时间倒序，省掉每次保存都整份解析 50 个文件的 IO。
     */
    static void trim(Context ctx) {
        File[] fs = dir(ctx).listFiles();
        if (fs == null) {
            return;
        }
        List<File> files = new ArrayList<File>();
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(EXT)) {
                files.add(f);
            }
        }
        if (files.size() <= KEEP) {
            return;
        }
        java.util.Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        for (int i = KEEP; i < files.size(); i++) {
            if (!files.get(i).delete()) {
                // 删不掉不影响本次保存
            }
        }
    }

    /** 整份解析；id 以文件名为准，不信文件里存的（文件可能被手工改过）。 */
    static Item parse(File f) {
        String name = f.getName();
        try {
            JSONObject o = new JSONObject(read(f));
            Item it = new Item();
            it.id = name.substring(0, name.length() - EXT.length());
            it.title = o.optString("title", it.id);
            it.label = o.optString("label", "");
            it.mode = o.optString("mode", "");
            it.role = o.optString("role", "client");
            it.startedAt = o.optString("started_at", "");
            it.finishedAt = o.optString("finished_at", "");
            it.summary = o.optString("summary", "");
            it.log = o.optString("log", "");
            it.bundle = o.optString("bundle", "");
            return it;
        } catch (Exception e) {
            return null;
        }
    }

    static String title(String mode, String role, String startedAt) {
        StringBuilder sb = new StringBuilder(modeName(mode));
        if ("server".equals(role)) {
            sb.append(" · 接收");
        }
        if (startedAt != null && !startedAt.isEmpty()) {
            sb.append(" · ").append(startedAt);
        }
        return sb.toString();
    }

    static String modeName(String mode) {
        if ("hotspot".equals(mode)) {
            return "热点";
        }
        if ("p2p".equals(mode)) {
            return "P2P";
        }
        return "WiFi";
    }

    /** id 的时间前缀：2026-11-03 10:24:05 → 20261103102405。 */
    static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        if (s == null) {
            return "";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 数外部目录里实际存在的 .json，同步结果和界面都用这个数。 */
    static int countJson(File d) {
        File[] fs = d.listFiles();
        if (fs == null) {
            return 0;
        }
        int n = 0;
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(EXT)) {
                n++;
            }
        }
        return n;
    }

    /** 逐字节复制；失败返回 false，由调用方把半截文件清掉。 */
    static boolean copy(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException e) {
            // 关不掉不影响已经写进去的内容
        }
    }

    /** 读整份文本；读失败返回空串，交给 parse 当坏记录处理。 */
    static String read(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n <= 0) {
                        break;
                    }
                    off += n;
                }
            } finally {
                in.close();
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 写整份文本；失败抛 RuntimeException，由调用方决定怎么提示用户。 */
    static void write(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("写入历史记录失败：" + e.getMessage());
        }
    }
}
