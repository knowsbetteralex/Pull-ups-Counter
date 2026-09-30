package com.alexzab.pullupcounter;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class HistoryActivity extends Activity {
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
        renderHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderHistory();
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

            TextView date = text(dateFormat.format(new Date(day.dayStart)).toUpperCase(ru), 16, Color.WHITE, true);
            top.addView(date, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView total = text(day.total + " за день", 15, Color.rgb(55, 230, 180), true);
            top.addView(total);

            for (StatsStore.Attempt attempt : day.attempts) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(10), 0, 0);
                card.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView time = text(timeFormat.format(new Date(attempt.startedAt)), 14, Color.argb(180, 255, 255, 255), false);
                row.addView(time, new LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT));

                String repsText = attempt.reps + " " + repsWord(attempt.reps);
                TextView reps = text(repsText, 15, Color.WHITE, false);
                row.addView(reps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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
