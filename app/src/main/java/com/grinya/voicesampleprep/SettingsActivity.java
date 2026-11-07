package com.grinya.voicesampleprep;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.ImageButton;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS = "vsp_prefs";
    public static final String KEY_STEREO_WAVES = "stereo_waves";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        CheckBox cbStereo = findViewById(R.id.cbStereoWaves);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        
        // Загружаем текущее состояние (по умолчанию - выключено, 1 волна)
        cbStereo.setChecked(prefs.getBoolean(KEY_STEREO_WAVES, false));

        // Сохраняем при переключении
        cbStereo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_STEREO_WAVES, isChecked).apply();
        });

        findViewById(R.id.btnAbout).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("О программе")
                    .setMessage("VoiceSamplePrep v0.1\n\nПриложение для подготовки голосовых сэмплов.\n\nЗапись, нарезка, маркеры, экспорт по маркерам в WAV (16/24-bit).\n\n© grinya")
                    .setPositiveButton("OK", null)
                    .show();
        });
    }
}