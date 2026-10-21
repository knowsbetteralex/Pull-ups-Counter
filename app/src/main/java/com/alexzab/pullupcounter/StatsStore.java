package com.alexzab.pullupcounter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public final class StatsStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "pullup_history.db";
    private static final int DB_VERSION = 1;

    private static final String BACKUP_FORMAT = "pullup-counter-backup";
    private static final int BACKUP_VERSION = 1;

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

    public static final class ImportResult {
        public final int added;
        public final int updated;
        public final int skipped;

        ImportResult(int added, int updated, int skipped) {
            this.added = added;
            this.updated = updated;
            this.skipped = skipped;
        }

        public int changed() {
            return added + updated;
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
        int updatedRows = db.update("attempts", values, "id = ?",
                new String[]{Long.toString(attemptId)});
        if (updatedRows > 0) return attemptId;

        // The row can disappear after a full history restore while MainActivity is paused.
        ContentValues recreated = new ContentValues();
        recreated.put("started_at", now);
        recreated.put("updated_at", now);
        recreated.put("reps", reps);
        return db.insertOrThrow("attempts", null, recreated);
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

    public String exportBackupJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", BACKUP_FORMAT);
        root.put("version", BACKUP_VERSION);
        root.put("exportedAt", System.currentTimeMillis());

        JSONArray attempts = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT started_at, reps FROM attempts WHERE reps > 0 ORDER BY started_at ASC", null)) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("startedAt", cursor.getLong(0));
                item.put("reps", cursor.getInt(1));
                attempts.put(item);
            }
        }

        root.put("attempts", attempts);
        return root.toString(2);
    }

    public ImportResult importBackupJson(String json, boolean replaceExisting) throws JSONException {
        JSONObject root = new JSONObject(json);
        String format = root.optString("format", "");
        int version = root.optInt("version", -1);

        if (!BACKUP_FORMAT.equals(format)) {
            throw new JSONException("Это не резервная копия Pull-Up Counter");
        }
        if (version < 1 || version > BACKUP_VERSION) {
            throw new JSONException("Неподдерживаемая версия резервной копии: " + version);
        }

        JSONArray attempts = root.optJSONArray("attempts");
        if (attempts == null) {
            throw new JSONException("В резервной копии нет списка попыток");
        }

        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        int updated = 0;
        int skipped = 0;
        Set<Long> seenInFile = new HashSet<>();

        db.beginTransaction();
        try {
            if (replaceExisting) {
                db.delete("attempts", null, null);
            }

            for (int i = 0; i < attempts.length(); i++) {
                JSONObject item = attempts.getJSONObject(i);
                long startedAt = item.getLong("startedAt");
                int reps = item.getInt("reps");

                if (startedAt <= 0 || reps <= 0) {
                    skipped++;
                    continue;
                }

                if (!seenInFile.add(startedAt)) {
                    skipped++;
                    continue;
                }

                ExistingAttempt existing = findByStartedAt(db, startedAt);
                if (existing == null) {
                    ContentValues values = new ContentValues();
                    values.put("started_at", startedAt);
                    values.put("updated_at", System.currentTimeMillis());
                    values.put("reps", reps);
                    db.insertOrThrow("attempts", null, values);
                    added++;
                } else if (reps > existing.reps) {
                    ContentValues values = new ContentValues();
                    values.put("updated_at", System.currentTimeMillis());
                    values.put("reps", reps);
                    db.update("attempts", values, "id = ?",
                            new String[]{Long.toString(existing.id)});
                    updated++;
                } else {
                    skipped++;
                }
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        return new ImportResult(added, updated, skipped);
    }

    private ExistingAttempt findByStartedAt(SQLiteDatabase db, long startedAt) {
        try (Cursor cursor = db.rawQuery(
                "SELECT id, reps FROM attempts WHERE started_at = ? ORDER BY id LIMIT 1",
                new String[]{Long.toString(startedAt)})) {
            if (!cursor.moveToFirst()) return null;
            return new ExistingAttempt(cursor.getLong(0), cursor.getInt(1));
        }
    }

    private static final class ExistingAttempt {
        final long id;
        final int reps;

        ExistingAttempt(long id, int reps) {
            this.id = id;
            this.reps = reps;
        }
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
