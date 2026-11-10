package com.alexzab.pullupcounter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class HelpActivity extends Activity {
    private LinearLayout root;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleController.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeController.resolve(this));
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        root.setBackgroundColor(isLight() ? Color.rgb(244,247,248) : Color.rgb(7,16,22));
        scroll.addView(root);
        setContentView(scroll);

        addTopBar();
        addContent();
    }

    private void addTopBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("←");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(44)));

        TextView title = text(getString(R.string.help_title), 24, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(12), 0, 0, 0);
        row.addView(title, lp);
        root.addView(row);
    }

    private void addContent() {
        String[] blocks = getString(R.string.help_content).split("\\n\\n");
        for (String block : blocks) {
            if (block.startsWith("## ")) {
                TextView h = text(block.substring(3), 18, true);
                h.setTextColor(Color.rgb(55, 230, 180));
                h.setPadding(0, dp(20), 0, dp(6));
                root.addView(h);
            } else {
                TextView body = text(block, 14, false);
                body.setLineSpacing(0f, 1.18f);
                body.setAlpha(0.92f);
                root.addView(body);
            }
        }
    }

    private TextView text(String value, float sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(isLight() ? Color.rgb(20,28,32) : Color.WHITE);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private boolean isLight() {
        String theme = new AppSettings(this).getTheme();
        if ("light".equals(theme)) return true;
        if ("dark".equals(theme)) return false;
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
