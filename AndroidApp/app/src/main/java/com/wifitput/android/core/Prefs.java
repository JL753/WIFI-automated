package com.wifitput.android.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 参数记忆。用户不想每次重填时长、流数、PC 地址。 */
public final class Prefs {

    private static final String NAME = "wifitput";

    private Prefs() {
    }

    static SharedPreferences of(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int getInt(Context c, String key, int def) {
        return of(c).getInt(key, def);
    }

    public static void putInt(Context c, String key, int v) {
        of(c).edit().putInt(key, v).apply();
    }

    public static String getStr(Context c, String key, String def) {
        String v = of(c).getString(key, def);
        return v == null ? def : v;
    }

    public static void putStr(Context c, String key, String v) {
        of(c).edit().putString(key, v == null ? "" : v).apply();
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return of(c).getBoolean(key, def);
    }

    public static void putBool(Context c, String key, boolean v) {
        of(c).edit().putBoolean(key, v).apply();
    }
}
