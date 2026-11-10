package com.wifitput.android.core;

import android.content.Context;

import java.util.List;
import java.util.Locale;

/**
 * 历史对比：在本地历史里找「上一次同模式、同参数、由本机发起」的测试。
 * 手机端结果卡和历史详情都用它，避免两处各写一套匹配逻辑。
 */
public class Compare {

    public static class Hit {
        public Stats stats;
        public History.Item item;
    }

    /**
     * 找当前这次之前、参数完全一致的上一次客户端测试。
     * excludeId 是当前记录自身；beforeStamp 形如 2026-11-03 12:30:45，
     * 用来保证在历史详情里看老记录时不会匹配到比它更新的记录。
     */
    public static Hit previous(Context ctx, String mode, Stats cur,
                               String excludeId, String beforeStamp) {
        if (ctx == null || cur == null) {
            return null;
        }
        List<History.Item> items;
        try {
            items = History.list(ctx);
        } catch (Exception e) {
            return null;
        }
        for (History.Item it : items) {
            if (it == null || !"client".equals(it.role)) {
                continue;
            }
            if (excludeId != null && !excludeId.isEmpty() && excludeId.equals(it.id)) {
                continue;
            }
            if (mode != null && !mode.isEmpty() && !mode.equals(it.mode)) {
                continue;
            }
            if (beforeStamp != null && !beforeStamp.isEmpty()
                    && it.startedAt != null && it.startedAt.compareTo(beforeStamp) >= 0) {
                continue;
            }
            Stats s = Stats.ofBundle(it.bundle);
            if (s == null || !cur.sameParams(s)) {
                continue;
            }
            Hit h = new Hit();
            h.stats = s;
            h.item = it;
            return h;
        }
        return null;
    }

    /** 一条可读的对比句，例如：上次「客厅」710 → 本次 728 Mb/s（+2.5%）。 */
    public static String line(Hit h, Stats cur) {
        if (h == null || h.stats == null || cur == null) {
            return "";
        }
        double a = h.stats.mainMb();
        double b = cur.mainMb();
        if (a <= 0 || b <= 0) {
            return "";
        }
        double d = (b - a) * 100.0 / a;
        String who = "上次";
        if (h.item != null && h.item.label != null && !h.item.label.trim().isEmpty()) {
            who = "上次「" + h.item.label.trim() + "」";
        } else if (h.item != null && h.item.startedAt != null && !h.item.startedAt.isEmpty()) {
            who = "上次 " + shortTime(h.item.startedAt);
        }
        return who + " " + Stats.fmtMb(a) + " → 本次 " + Stats.fmtMb(b) + " Mb/s（"
                + (d >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", d) + "%）";
    }

    private static String shortTime(String stamp) {
        // 2026-11-03 12:30:45 → 11-03 12:30
        if (stamp.length() >= 16) {
            return stamp.substring(5, 16);
        }
        return stamp;
    }
}
