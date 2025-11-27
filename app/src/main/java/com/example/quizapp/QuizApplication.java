package com.example.quizapp;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;


public class QuizApplication extends Application {

    private static final String TAG = "QuizApplication";
    private static QuizApplication instance;

    // Звуки и музыка
    private MediaPlayer backgroundMusicPlayer;
    private SoundPool soundPool;

    // ID для загруженных звуков
    private int clickSoundId, correctSoundId, incorrectSoundId, victorySoundId, defeatSoundId;

    // Настройки
    private boolean musicEnabled = true;
    private boolean sfxEnabled = true;

    // Названия настроек должны совпадать с SettingsActivity.java
    public static final String PREFS_NAME = "QuizAppPrefs";
    public static final String MUSIC_ENABLED_KEY = "music_enabled";
    public static final String SFX_ENABLED_KEY = "sfx_enabled";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Загрузка настроек пользователя
        loadSettings();

        // Инициализация медиаплееров и звуковых эффектов
        initializeMusicPlayer();
        initializeSoundPool();
    }

    public static synchronized QuizApplication getInstance() {
        return instance;
    }

    // --- Настройки и инициализация ---

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        musicEnabled = prefs.getBoolean(MUSIC_ENABLED_KEY, true);
        sfxEnabled = prefs.getBoolean(SFX_ENABLED_KEY, true);
    }

    public void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(MUSIC_ENABLED_KEY, musicEnabled);
        editor.putBoolean(SFX_ENABLED_KEY, sfxEnabled);
        editor.apply();
    }

    // --- Управление фоновой музыкой ---

    private void initializeMusicPlayer() {
        try {
            // Файл background_music.mp3 должен находиться в res/raw/
            backgroundMusicPlayer = MediaPlayer.create(this, R.raw.background_music);
            if (backgroundMusicPlayer != null) {
                backgroundMusicPlayer.setLooping(true); // Зацикливание
                if (musicEnabled) {
                    backgroundMusicPlayer.start();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации MediaPlayer: " + e.getMessage());
            Toast.makeText(this, "Ошибка загрузки музыки", Toast.LENGTH_LONG).show();
        }
    }

    public void startBackgroundMusic() {
        if (musicEnabled && backgroundMusicPlayer != null && !backgroundMusicPlayer.isPlaying()) {
            backgroundMusicPlayer.start();
        }
    }

    public void stopBackgroundMusic() {
        if (backgroundMusicPlayer != null && backgroundMusicPlayer.isPlaying()) {
            backgroundMusicPlayer.pause(); // Лучше пауза, чем stop, для быстрого возобновления
        }
    }

    // --- Управление звуковыми эффектами (SFX) ---

    private void initializeSoundPool() {
        // Использование AudioAttributes для современных API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(5)
                    .setAudioAttributes(audioAttributes)
                    .build();
        } else {
            // Для устаревших API
            soundPool = new SoundPool(5, android.media.AudioManager.STREAM_MUSIC, 0);
        }

        // Загрузка звуков. Файлы (click.mp3, correct.mp3, etc.) должны быть в res/raw/
        clickSoundId = soundPool.load(this, R.raw.click, 1);
        correctSoundId = soundPool.load(this, R.raw.correct, 1);
        incorrectSoundId = soundPool.load(this, R.raw.incorrect, 1);
        victorySoundId = soundPool.load(this, R.raw.victory, 1);
        defeatSoundId = soundPool.load(this, R.raw.defeat, 1);
    }

    // Универсальный метод для проигрывания SFX
    public void playSound(int resId) {
        if (!sfxEnabled || soundPool == null) return;

        int soundId;

        // Определение ID загруженного звука по ID ресурса
        if (resId == R.raw.correct) soundId = correctSoundId;
        else if (resId == R.raw.incorrect) soundId = incorrectSoundId;
        else if (resId == R.raw.victory) soundId = victorySoundId;
        else if (resId == R.raw.defeat) soundId = defeatSoundId;
        else if (resId == R.raw.click) soundId = clickSoundId;
        else {
            Log.w(TAG, "Неизвестный ID ресурса звука: " + resId);
            return;
        }

        if (soundId != 0) {
            soundPool.play(soundId, 1, 1, 0, 0, 1);
        }
    }

    // Специальный метод для клика (используется чаще всего)
    public void playClickSound() {
        playSound(R.raw.click);
    }

    // --- Геттеры и Сеттеры для настроек ---

    public boolean isMusicEnabled() {
        return musicEnabled;
    }

    public void setMusicEnabled(boolean enabled) {
        this.musicEnabled = enabled;
        if (enabled) {
            startBackgroundMusic();
        } else {
            stopBackgroundMusic();
        }
        saveSettings();
    }

    public boolean isSfxEnabled() {
        return sfxEnabled;
    }

    public void setSfxEnabled(boolean enabled) {
        this.sfxEnabled = enabled;
        saveSettings();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // Освобождение ресурсов при завершении приложения
        if (backgroundMusicPlayer != null) {
            backgroundMusicPlayer.release();
            backgroundMusicPlayer = null;
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}