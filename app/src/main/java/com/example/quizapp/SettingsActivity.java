package com.example.quizapp;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.quizapp.QuizDatabaseHelper;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    // Ключи для SharedPreferences
    public static final String PREFS_NAME = "QuizAppPrefs";
    public static final String KEY_MUSIC_ENABLED = "musicEnabled";
    public static final String KEY_SFX_ENABLED = "sfxEnabled";

    private EditText etPlayerName;
    private Switch switchMusic, switchSfx;
    private Button btnSaveSettings;

    private SharedPreferences sharedPrefs;
    private QuizDatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etPlayerName = findViewById(R.id.et_player_name);
        switchMusic = findViewById(R.id.switch_music);
        switchSfx = findViewById(R.id.switch_sfx);
        btnSaveSettings = findViewById(R.id.btn_save_settings);

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        dbHelper = QuizDatabaseHelper.getInstance(this);

        loadCurrentSettings();

        // 1. Обработка переключателя музыки
        switchMusic.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                QuizApplication.getInstance().startBackgroundMusic();
            } else {
                QuizApplication.getInstance().stopBackgroundMusic();
            }
        });

        // 2. Обработка кнопки сохранения
        btnSaveSettings.setOnClickListener(v -> saveSettings());

        QuizApplication.getInstance().startBackgroundMusic();
    }

    // =================================================================
    // ЗАГРУЗКА И СОХРАНЕНИЕ
    // =================================================================

    private void loadCurrentSettings() {
        // Загрузка имени игрока из БД
        String currentName = dbHelper.getPlayerName();
        if (!TextUtils.isEmpty(currentName)) {
            etPlayerName.setText(currentName);
        } else {
            etPlayerName.setText("Игрок 1"); // Значение по умолчанию
        }

        // Загрузка настроек звука из SharedPreferences
        boolean musicEnabled = sharedPrefs.getBoolean(KEY_MUSIC_ENABLED, true);
        boolean sfxEnabled = sharedPrefs.getBoolean(KEY_SFX_ENABLED, true);

        switchMusic.setChecked(musicEnabled);
        switchSfx.setChecked(sfxEnabled);

        // Если музыка была отключена в прошлый раз, но активности не было, останавливаем ее
        if (!musicEnabled) {
            QuizApplication.getInstance().stopBackgroundMusic();
        }
    }

    private void saveSettings() {
        // 1. Сохранение имени игрока (в БД)
        String newName = etPlayerName.getText().toString().trim();
        if (newName.length() < 3) {
            Toast.makeText(this, "Имя должно содержать не менее 3 символов.", Toast.LENGTH_SHORT).show();
            return;
        }

        updatePlayerNameInDatabase(newName);

        // 2. Сохранение настроек звука (в SharedPreferences)
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putBoolean(KEY_MUSIC_ENABLED, switchMusic.isChecked());
        editor.putBoolean(KEY_SFX_ENABLED, switchSfx.isChecked());
        editor.apply();

        Toast.makeText(this, "Настройки сохранены.", Toast.LENGTH_SHORT).show();
    }

    private void updatePlayerNameInDatabase(String newName) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(QuizDatabaseHelper.STATS_COLUMN_NAME, newName);

        // Обновляем статистику для игрока с ID=1 (единственный игрок)
        int rowsAffected = db.update(QuizDatabaseHelper.TABLE_PLAYER_STATS,
                values,
                QuizDatabaseHelper.STATS_COLUMN_ID + "=1",
                null);
        if (rowsAffected > 0) {
            Log.d(TAG, "Имя игрока успешно обновлено на: " + newName);
        } else {
            // Если игрок не существует, вставляем новую запись (должно быть сделано при первом запуске)
            Log.e(TAG, "Не удалось обновить имя. Проверьте, существует ли запись с ID=1.");
        }
    }
}