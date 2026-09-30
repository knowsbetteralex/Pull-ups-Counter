package com.alexzab.pullupcounter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StatsStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "pullup_history.db";
    private static final int DB_VERSION = 1;

    public static final class Attempt {
        public final long startedAt;
        public final int reps;

        Attempt(long startedAt, int reps) {
            this.startedAt = startedAt;
            this.reps = reps;
        }
    }

    public static final class DayHistory {
        public final long dayStart;
        public int total;
        public final List<Attempt> attempts = new ArrayList<>();

        DayHistory(long dayStart) {
            this.dayStart = dayStart;
        }
    }

    public StatsStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE attempts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "started_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "reps INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_attempts_started_at ON attempts(started_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1: no migration yet.
    }

    public long saveAttemptProgress(long attemptId, int reps) {
        if (reps <= 0) return attemptId;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();

        if (attemptId <= 0) {
            ContentValues values = new ContentValues();
            values.put("started_at", now);
            values.put("updated_at", now);
            values.put("reps", reps);
            return db.insertOrThrow("attempts", null, values);
        }

        ContentValues values = new ContentValues();
        values.put("updated_at", now);
        values.put("reps", reps);
        db.update("attempts", values, "id = ?", new String[]{Long.toString(attemptId)});
        return attemptId;
    }

    public int getTodayTotal() {
        long start = startOfDay(System.currentTimeMillis());
        long end = nextDay(start);
        return getTotalBetween(start, end);
    }

    public int getTotalBetween(long startInclusive, long endExclusive) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT COALESCE(SUM(reps), 0) FROM attempts WHERE started_at >= ? AND started_at < ?",
                new String[]{Long.toString(startInclusive), Long.toString(endExclusive)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public List<DayHistory> loadHistory() {
        LinkedHashMap<Long, DayHistory> days = new LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT started_at, reps FROM attempts WHERE reps > 0 ORDER BY started_at DESC", null)) {
            while (cursor.moveToNext()) {
                long startedAt = cursor.getLong(0);
                int reps = cursor.getInt(1);
                long dayStart = startOfDay(startedAt);
                DayHistory day = days.get(dayStart);
                if (day == null) {
                    day = new DayHistory(dayStart);
                    days.put(dayStart, day);
                }
                day.total += reps;
                day.attempts.add(new Attempt(startedAt, reps));
            }
        }
        return new ArrayList<>(days.values());
    }

    public static long startOfDay(long timeMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timeMillis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long nextDay(long dayStart) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart);
        c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }
}
