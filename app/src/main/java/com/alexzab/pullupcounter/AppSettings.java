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
    private static final String KEY_SCORE_MODE = "score_mode";
    private static final String KEY_COUNT_SPEECH = "count_speech";
    private static final String KEY_ADVICE_SPEECH = "advice_speech";
    private static final String KEY_ACTIVE_ATTEMPT_ID = "active_attempt_id";
    private static final String KEY_ACTIVE_ATTEMPT_REPS = "active_attempt_reps";
    private static final String KEY_ACTIVE_ATTEMPT_DAY = "active_attempt_day";
    private static final String KEY_PARAMETER_PROFILE_VERSION = "parameter_profile_version";

    public static final int PARAMETER_PROFILE_VERSION = 1;

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

    public int getScoreMode(int fallback) { return prefs.getInt(KEY_SCORE_MODE, fallback); }
    public void setScoreMode(int value) { prefs.edit().putInt(KEY_SCORE_MODE, value).apply(); }

    public boolean isCountSpeechEnabled() { return prefs.getBoolean(KEY_COUNT_SPEECH, false); }
    public void setCountSpeechEnabled(boolean value) { prefs.edit().putBoolean(KEY_COUNT_SPEECH, value).apply(); }

    public boolean isAdviceSpeechEnabled() { return prefs.getBoolean(KEY_ADVICE_SPEECH, false); }
    public void setAdviceSpeechEnabled(boolean value) { prefs.edit().putBoolean(KEY_ADVICE_SPEECH, value).apply(); }

    public long getActiveAttemptId() { return prefs.getLong(KEY_ACTIVE_ATTEMPT_ID, -1L); }
    public int getActiveAttemptReps() { return Math.max(0, prefs.getInt(KEY_ACTIVE_ATTEMPT_REPS, 0)); }
    public long getActiveAttemptDay() { return prefs.getLong(KEY_ACTIVE_ATTEMPT_DAY, -1L); }

    public void saveActiveAttempt(long attemptId, int reps, long dayStart) {
        prefs.edit()
                .putLong(KEY_ACTIVE_ATTEMPT_ID, attemptId)
                .putInt(KEY_ACTIVE_ATTEMPT_REPS, Math.max(0, reps))
                .putLong(KEY_ACTIVE_ATTEMPT_DAY, dayStart)
                .apply();
    }

    public void clearActiveAttempt() {
        prefs.edit()
                .remove(KEY_ACTIVE_ATTEMPT_ID)
                .remove(KEY_ACTIVE_ATTEMPT_REPS)
                .remove(KEY_ACTIVE_ATTEMPT_DAY)
                .apply();
    }

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
        o.put("scoreMode", getScoreMode(0));
        o.put("countSpeech", isCountSpeechEnabled());
        o.put("adviceSpeech", isAdviceSpeechEnabled());
        o.put("parameterProfileVersion", PARAMETER_PROFILE_VERSION);
        return o;
    }

    public void importJson(JSONObject o) {
        if (o == null) return;
        if (o.has("language")) setLanguage(o.optString("language", "system"));
        if (o.has("theme")) setTheme(o.optString("theme", "dark"));
        if (o.has("downAngle")) setDownAngle(o.optInt("downAngle", DEFAULT_DOWN_ANGLE));
        if (o.has("upAngle")) setUpAngle(o.optInt("upAngle", DEFAULT_UP_ANGLE));
        if (o.has("topHoldMs")) setTopHoldMs(o.optInt("topHoldMs", DEFAULT_TOP_HOLD_MS));
        if (o.has("scoreMode")) setScoreMode(Math.max(0, Math.min(2, o.optInt("scoreMode", 0))));
        if (o.has("countSpeech")) setCountSpeechEnabled(o.optBoolean("countSpeech", false));
        if (o.has("adviceSpeech")) setAdviceSpeechEnabled(o.optBoolean("adviceSpeech", false));

        // Parameter profile version is intentionally recorded separately from backup version.
        // When the new tuned defaults are supplied, migration can preserve user deviation
        // from the old 150° / 85° / 250 ms baseline instead of blindly copying raw values.
        if (o.has("parameterProfileVersion")) {
            prefs.edit().putInt(KEY_PARAMETER_PROFILE_VERSION,
                    Math.max(1, o.optInt("parameterProfileVersion", 1))).apply();
        }
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
