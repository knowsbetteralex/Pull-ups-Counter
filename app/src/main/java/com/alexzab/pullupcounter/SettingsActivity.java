package com.alexzab.pullupcounter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
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
import java.util.Locale;

public final class SettingsActivity extends Activity {
    private static final int REQUEST_EXPORT = 301;
    private static final int REQUEST_IMPORT = 302;

    private AppSettings settings;
    private StatsStore statsStore;
    private LinearLayout root;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleController.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeController.resolve(this));
        super.onCreate(savedInstanceState);

        settings = new AppSettings(this);
        statsStore = new StatsStore(this);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        root.setBackgroundColor(isLight() ? Color.rgb(244,247,248) : Color.rgb(7,16,22));
        scroll.addView(root);
        setContentView(scroll);

        addTopBar();
        addGeneral();
        addParameters();
        addBackup();
        addInfo();
    }

    private void addTopBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←");
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(44)));

        TextView title = label(getString(R.string.settings), 24, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(12),0,0,0);
        row.addView(title, lp);
        root.addView(row);
    }

    private void addGeneral() {
        section(getString(R.string.general));

        TextView langLabel = label(getString(R.string.language), 15, false);
        root.addView(langLabel);
        Spinner language = new Spinner(this);
        String[] labels = {getString(R.string.language_system), getString(R.string.language_ru),
                getString(R.string.language_en), getString(R.string.language_lv)};
        String[] values = {"system","ru","en","lv"};
        language.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        language.setSelection(indexOf(values, settings.getLanguage()));
        final boolean[] languageReady={false};
        language.setOnItemSelectedListener(new SimpleItemSelectedListener(pos -> {
            if (!languageReady[0]) { languageReady[0]=true; return; }
            settings.setLanguage(values[pos]);
            recreate();
        }));
        root.addView(language, match());

        TextView themeLabel = label(getString(R.string.theme), 15, false);
        themeLabel.setPadding(0,dp(12),0,0);
        root.addView(themeLabel);
        Spinner theme = new Spinner(this);
        String[] themeLabels = {getString(R.string.theme_dark), getString(R.string.theme_light), getString(R.string.theme_system)};
        String[] themeValues = {"dark","light","system"};
        theme.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, themeLabels));
        theme.setSelection(indexOf(themeValues, settings.getTheme()));
        final boolean[] themeReady={false};
        theme.setOnItemSelectedListener(new SimpleItemSelectedListener(pos -> {
            if (!themeReady[0]) { themeReady[0]=true; return; }
            settings.setTheme(themeValues[pos]);
            recreate();
        }));
        root.addView(theme, match());

        TextView screen = label(getString(R.string.keep_screen_on), 14, false);
        screen.setPadding(0,dp(12),0,0);
        root.addView(screen);
        TextView screenHint = muted(getString(R.string.screen_on_always));
        root.addView(screenHint);
    }

    private void addParameters() {
        section(getString(R.string.pullup_parameters));
        addIntSetting(getString(R.string.down_angle), "°",
                AppSettings.MIN_DOWN_ANGLE, AppSettings.MAX_DOWN_ANGLE,
                settings.getDownAngle(), AppSettings.DEFAULT_DOWN_ANGLE,
                settings::setDownAngle, settings::resetDownAngle);
        addIntSetting(getString(R.string.up_angle), "°",
                AppSettings.MIN_UP_ANGLE, AppSettings.MAX_UP_ANGLE,
                settings.getUpAngle(), AppSettings.DEFAULT_UP_ANGLE,
                settings::setUpAngle, settings::resetUpAngle);
        addIntSetting(getString(R.string.top_hold), " ms",
                AppSettings.MIN_TOP_HOLD_MS, AppSettings.MAX_TOP_HOLD_MS,
                settings.getTopHoldMs(), AppSettings.DEFAULT_TOP_HOLD_MS,
                settings::setTopHoldMs, settings::resetTopHold);
    }

    private void addBackup() {
        section(getString(R.string.backup));
        TextView hint = muted(getString(R.string.backup_hint));
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0,dp(8),0,0);
        Button save = button(getString(R.string.save_backup));
        Button restore = button(getString(R.string.restore_backup));
        save.setOnClickListener(v -> chooseExport());
        restore.setOnClickListener(v -> chooseImport());
        row.addView(save, new LinearLayout.LayoutParams(0,dp(48),1f));
        LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(0,dp(48),1f); rlp.setMargins(dp(8),0,0,0);
        row.addView(restore, rlp);
        root.addView(row);
    }

    private void addInfo() {
        section(getString(R.string.about));
        String versionName = "?";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        root.addView(muted("Pull-Up Counter v" + versionName));
    }

    private void addIntSetting(String title, String suffix, int min, int max, int current, int def,
                               IntSetter setter, Runnable resetter) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(title, 14, false);
        TextView value = label(current + suffix, 14, true);
        TextView reset = label("↺", 24, true);
        reset.setGravity(Gravity.CENTER);
        reset.setVisibility(current == def ? View.INVISIBLE : View.VISIBLE);
        header.addView(name, new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        header.addView(value);
        header.addView(reset, new LinearLayout.LayoutParams(dp(44),dp(40)));
        root.addView(header);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max-min);
        seek.setProgress(current-min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if (!fromUser) return;
                int v=min+progress;
                setter.set(v);
                value.setText(v+suffix);
                reset.setVisibility(v==def?View.INVISIBLE:View.VISIBLE);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        reset.setOnClickListener(v -> {
            resetter.run();
            seek.setProgress(def-min);
            value.setText(def+suffix);
            reset.setVisibility(View.INVISIBLE);
        });
        root.addView(seek, match());
    }

    private void chooseExport() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE,"PullUpCounter_backup_"+stamp+".json");
        startActivityForResult(i,REQUEST_EXPORT);
    }

    private void chooseImport() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json");
        startActivityForResult(i,REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        if(requestCode==REQUEST_EXPORT) exportBackup(data.getData());
        else if(requestCode==REQUEST_IMPORT) readBackup(data.getData());
    }

    private void exportBackup(Uri uri) {
        new Thread(() -> {
            try {
                String json=statsStore.exportBackupJson();
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w");
                    OutputStreamWriter writer=new OutputStreamWriter(out, StandardCharsets.UTF_8)) { writer.write(json); }
                runOnUiThread(() -> Toast.makeText(this,R.string.backup_saved,Toast.LENGTH_LONG).show());
            } catch(Exception e) {
                runOnUiThread(() -> Toast.makeText(this,getString(R.string.backup_error_save,safe(e)),Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void readBackup(Uri uri) {
        new Thread(() -> {
            try {
                StringBuilder b=new StringBuilder();
                try(InputStream in=getContentResolver().openInputStream(uri);
                    BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
                    String line; while((line=r.readLine())!=null)b.append(line).append('\n');
                }
                String json=b.toString();
                new org.json.JSONObject(json);
                runOnUiThread(() -> showImport(json));
            } catch(Exception e) {
                runOnUiThread(() -> Toast.makeText(this,getString(R.string.backup_error_read,safe(e)),Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showImport(String json) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_title)
                .setMessage(R.string.restore_message)
                .setPositiveButton(R.string.merge,(d,w)->applyImport(json,false))
                .setNegativeButton(R.string.replace,(d,w)->applyImport(json,true))
                .setNeutralButton(R.string.cancel,null).show();
    }

    private void applyImport(String json, boolean replace) {
        new Thread(() -> {
            try {
                statsStore.importBackupJson(json,replace);
                runOnUiThread(() -> recreate());
            } catch(Exception e) {
                runOnUiThread(() -> Toast.makeText(this,getString(R.string.backup_error_read,safe(e)),Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private boolean isLight() {
        String t=settings.getTheme();
        if("light".equals(t))return true;
        if("dark".equals(t))return false;
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void section(String title) {
        TextView t=label(title,18,true);
        t.setTextColor(Color.rgb(55,230,180));
        t.setPadding(0,dp(22),0,dp(8));
        root.addView(t);
    }

    private TextView label(String text,float sp,boolean bold) {
        TextView v=new TextView(this); v.setText(text); v.setTextSize(sp);
        v.setTextColor(isLight()?Color.rgb(20,28,32):Color.WHITE);
        if(bold)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);
        return v;
    }
    private TextView muted(String text) { TextView v=label(text,13,false); v.setAlpha(.62f); return v; }
    private Button button(String text) { Button b=new Button(this); b.setText(text); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams match(){ return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static int indexOf(String[] a,String v){for(int i=0;i<a.length;i++)if(a[i].equals(v))return i;return 0;}
    private static String safe(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}

    private interface IntSetter { void set(int value); }

    private static class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final java.util.function.IntConsumer consumer;
        SimpleItemSelectedListener(java.util.function.IntConsumer consumer){this.consumer=consumer;}
        public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){consumer.accept(pos);}
        public void onNothingSelected(android.widget.AdapterView<?> p){}
    }

    @Override protected void onDestroy(){if(statsStore!=null)statsStore.close();super.onDestroy();}
}
