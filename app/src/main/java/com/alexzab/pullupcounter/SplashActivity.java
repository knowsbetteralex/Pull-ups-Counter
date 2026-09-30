package com.alexzab.pullupcounter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

public final class SplashActivity extends Activity {
    private static final long SPLASH_MS = 720L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logo = findViewById(R.id.splash_logo);
        View title = findViewById(R.id.splash_title);
        View subtitle = findViewById(R.id.splash_subtitle);

        logo.setAlpha(0f);
        logo.setScaleX(0.82f);
        logo.setScaleY(0.82f);
        title.setAlpha(0f);
        title.setTranslationY(10f);
        subtitle.setAlpha(0f);

        logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .start();

        title.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(90L)
                .setDuration(260L)
                .start();

        subtitle.animate()
                .alpha(1f)
                .setStartDelay(180L)
                .setDuration(260L)
                .start();

        findViewById(R.id.splash_root).postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, SPLASH_MS);
    }
}
