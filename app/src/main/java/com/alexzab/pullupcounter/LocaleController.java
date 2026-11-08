package com.alexzab.pullupcounter;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

public final class LocaleController {
    private LocaleController() {}

    public static Context wrap(Context base) {
        AppSettings settings = new AppSettings(base);
        String code = settings.getLanguage();
        if ("system".equals(code)) return base;

        Locale locale = Locale.forLanguageTag(code);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    public static Locale ttsLocale(Context context) {
        String code = new AppSettings(context).getLanguage();
        if ("ru".equals(code)) return new Locale("ru", "RU");
        if ("lv".equals(code)) return new Locale("lv", "LV");
        if ("en".equals(code)) return Locale.ENGLISH;
        return Locale.getDefault();
    }
}
