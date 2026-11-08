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
        if ("de".equals(code)) return Locale.GERMAN;
        if ("fr".equals(code)) return Locale.FRENCH;
        if ("ja".equals(code)) return Locale.JAPANESE;
        if ("ar".equals(code)) return Locale.forLanguageTag("ar");
        if ("hi".equals(code)) return Locale.forLanguageTag("hi-IN");
        if ("es".equals(code)) return Locale.forLanguageTag("es");
        if ("it".equals(code)) return Locale.ITALIAN;
        if ("pt".equals(code)) return Locale.forLanguageTag("pt");
        if ("zh-CN".equals(code)) return Locale.SIMPLIFIED_CHINESE;
        if ("ko".equals(code)) return Locale.KOREAN;
        return Locale.getDefault();
    }
}
