package com.alexzab.pullupcounter;

import android.content.Context;
import android.content.res.Configuration;

public final class ThemeController {
    private ThemeController() {}

    public static int resolve(Context context) {
        String theme = new AppSettings(context).getTheme();
        if ("light".equals(theme)) return R.style.AppThemeLight;
        if ("system".equals(theme)) {
            int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES ? R.style.AppTheme : R.style.AppThemeLight;
        }
        return R.style.AppTheme;
    }
}
