package com.alexzab.pullupcounter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

public final class AppSettings {
    public static final int DEFAULT_DOWN_ANGLE = 150;
    public static final int DEFAULT_UP_ANGLE = 85;
    public static final int DEFAULT_TOP_HOLD_MS = 250;

    public static final int MIN_DOWN_ANGLE = 140;
    public static final int MAX_DOWN_ANGLE = 170;
    public static final int MIN_UP_ANGLE = 65;
    public static final int MAX_UP_ANGLE = 100;
    public static final int MIN_TOP_HOLD_MS = 100;
    public static final int MAX_TOP_HOLD_MS = 1000;

    private static final String PREFS = "app_settings";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_THEME = "theme";
    private static final String KEY_DOWN_ANGLE = "down_angle";
    private static final String KEY_UP_ANGLE = "up_angle";
    private static final String KEY_TOP_HOLD_MS = "top_hold_ms";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getLanguage() { return prefs.getString(KEY_LANGUAGE, "system"); }
    public void setLanguage(String value) { prefs.edit().putString(KEY_LANGUAGE, value).apply(); }

    public String getTheme() { return prefs.getString(KEY_THEME, "dark"); }
    public void setTheme(String value) { prefs.edit().putString(KEY_THEME, value).apply(); }

    public int getDownAngle() { return clamp(prefs.getInt(KEY_DOWN_ANGLE, DEFAULT_DOWN_ANGLE), MIN_DOWN_ANGLE, MAX_DOWN_ANGLE); }
    public void setDownAngle(int value) { prefs.edit().putInt(KEY_DOWN_ANGLE, clamp(value, MIN_DOWN_ANGLE, MAX_DOWN_ANGLE)).apply(); }

    public int getUpAngle() { return clamp(prefs.getInt(KEY_UP_ANGLE, DEFAULT_UP_ANGLE), MIN_UP_ANGLE, MAX_UP_ANGLE); }
    public void setUpAngle(int value) { prefs.edit().putInt(KEY_UP_ANGLE, clamp(value, MIN_UP_ANGLE, MAX_UP_ANGLE)).apply(); }

    public int getTopHoldMs() { return clamp(prefs.getInt(KEY_TOP_HOLD_MS, DEFAULT_TOP_HOLD_MS), MIN_TOP_HOLD_MS, MAX_TOP_HOLD_MS); }
    public void setTopHoldMs(int value) { prefs.edit().putInt(KEY_TOP_HOLD_MS, clamp(value, MIN_TOP_HOLD_MS, MAX_TOP_HOLD_MS)).apply(); }

    public boolean isDownAngleDefault() { return getDownAngle() == DEFAULT_DOWN_ANGLE; }
    public boolean isUpAngleDefault() { return getUpAngle() == DEFAULT_UP_ANGLE; }
    public boolean isTopHoldDefault() { return getTopHoldMs() == DEFAULT_TOP_HOLD_MS; }

    public void resetDownAngle() { prefs.edit().remove(KEY_DOWN_ANGLE).apply(); }
    public void resetUpAngle() { prefs.edit().remove(KEY_UP_ANGLE).apply(); }
    public void resetTopHold() { prefs.edit().remove(KEY_TOP_HOLD_MS).apply(); }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("language", getLanguage());
        o.put("theme", getTheme());
        o.put("downAngle", getDownAngle());
        o.put("upAngle", getUpAngle());
        o.put("topHoldMs", getTopHoldMs());
        return o;
    }

    public void importJson(JSONObject o) {
        if (o == null) return;
        if (o.has("language")) setLanguage(o.optString("language", "system"));
        if (o.has("theme")) setTheme(o.optString("theme", "dark"));
        if (o.has("downAngle")) setDownAngle(o.optInt("downAngle", DEFAULT_DOWN_ANGLE));
        if (o.has("upAngle")) setUpAngle(o.optInt("upAngle", DEFAULT_UP_ANGLE));
        if (o.has("topHoldMs")) setTopHoldMs(o.optInt("topHoldMs", DEFAULT_TOP_HOLD_MS));
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
