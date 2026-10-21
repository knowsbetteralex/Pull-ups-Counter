package com.alexzab.pullupcounter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class HistoryActivity extends Activity {
    private static final int REQUEST_EXPORT_BACKUP = 201;
    private static final int REQUEST_IMPORT_BACKUP = 202;

    private StatsStore statsStore;
    private LinearLayout historyContainer;
    private TextView emptyText;
    private final Locale ru = new Locale("ru", "RU");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        statsStore = new StatsStore(this);
        historyContainer = findViewById(R.id.history_container);
        emptyText = findViewById(R.id.empty_text);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.export_button).setOnClickListener(v -> chooseBackupDestination());
        findViewById(R.id.import_button).setOnClickListener(v -> chooseBackupFile());

        renderHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderHistory();
    }

    private void chooseBackupDestination() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
                .format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "PullUpCounter_backup_" + stamp + ".json");
        startActivityForResult(intent, REQUEST_EXPORT_BACKUP);
    }

    private void chooseBackupFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_BACKUP) {
            exportBackup(uri);
        } else if (requestCode == REQUEST_IMPORT_BACKUP) {
            readBackupForImport(uri);
        }
    }

    private void exportBackup(Uri uri) {
        new Thread(() -> {
            try {
                String json = statsStore.exportBackupJson();
                try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new IllegalStateException("Не удалось открыть файл для записи");
                    try (OutputStreamWriter writer =
                                 new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                        writer.write(json);
                    }
                }

                runOnUiThread(() -> Toast.makeText(
                        this, "Резервная копия сохранена", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> showError("Не удалось сохранить копию: " + safeMessage(e)));
            }
        }, "backup-export").start();
    }

    private void readBackupForImport(Uri uri) {
        new Thread(() -> {
            try {
                StringBuilder json = new StringBuilder();
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("Не удалось открыть файл");
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            json.append(line).append('\n');
                        }
                    }
                }

                String backup = json.toString();
                // Parse/validate before showing a destructive option.
                new org.json.JSONObject(backup);
                runOnUiThread(() -> showImportChoice(backup));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Не удалось прочитать копию: " + safeMessage(e)));
            }
        }, "backup-import-read").start();
    }

    private void showImportChoice(String backupJson) {
        new AlertDialog.Builder(this)
                .setTitle("Восстановить историю")
                .setMessage("Объединить копию с текущей историей или полностью заменить текущую историю данными из файла?")
                .setPositiveButton("Объединить", (dialog, which) ->
                        applyImport(backupJson, false))
                .setNegativeButton("Заменить", (dialog, which) ->
                        confirmReplace(backupJson))
                .setNeutralButton("Отмена", null)
                .show();
    }

    private void confirmReplace(String backupJson) {
        new AlertDialog.Builder(this)
                .setTitle("Заменить всю историю?")
                .setMessage("Текущая история будет удалена и заменена данными из резервной копии.")
                .setPositiveButton("Заменить", (dialog, which) ->
                        applyImport(backupJson, true))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void applyImport(String backupJson, boolean replaceExisting) {
        new Thread(() -> {
            try {
                StatsStore.ImportResult result =
                        statsStore.importBackupJson(backupJson, replaceExisting);

                runOnUiThread(() -> {
                    renderHistory();
                    String message;
                    if (replaceExisting) {
                        message = "История восстановлена: " + result.added + " попыток";
                    } else if (result.changed() == 0) {
                        message = "Новых данных нет — история уже содержит эту копию";
                    } else {
                        message = "Добавлено: " + result.added
                                + ", обновлено: " + result.updated;
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("Не удалось восстановить историю: "
                        + safeMessage(e)));
            }
        }, "backup-import-apply").start();
    }

    private void renderHistory() {
        historyContainer.removeAllViews();
        List<StatsStore.DayHistory> history = statsStore.loadHistory();
        emptyText.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);

        SimpleDateFormat dateFormat = new SimpleDateFormat("d MMMM, EEE", ru);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        for (StatsStore.DayHistory day : history) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(16), dp(18), dp(14));
            card.setBackgroundResource(R.drawable.bg_history_card);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, dp(12));
            historyContainer.addView(card, cardParams);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(top, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView date = text(dateFormat.format(new Date(day.dayStart)).toUpperCase(ru),
                    16, Color.WHITE, true);
            top.addView(date, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView total = text(day.total + " за день", 15,
                    Color.rgb(55, 230, 180), true);
            top.addView(total);

            for (StatsStore.Attempt attempt : day.attempts) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(10), 0, 0);
                card.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView time = text(timeFormat.format(new Date(attempt.startedAt)),
                        14, Color.argb(180, 255, 255, 255), false);
                row.addView(time, new LinearLayout.LayoutParams(
                        dp(62), LinearLayout.LayoutParams.WRAP_CONTENT));

                String repsText = attempt.reps + " " + repsWord(attempt.reps);
                TextView reps = text(repsText, 15, Color.WHITE, false);
                row.addView(reps, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Резервная копия")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    private static String repsWord(int n) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "повторений";
        if (mod10 == 1) return "повторение";
        if (mod10 >= 2 && mod10 <= 4) return "повторения";
        return "повторений";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (statsStore != null) statsStore.close();
        super.onDestroy();
    }
}
