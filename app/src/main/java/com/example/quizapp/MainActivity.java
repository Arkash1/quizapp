package com.example.quizapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.quizapp.GameModeSelectionActivity;
import com.example.quizapp.QuizApplication;
import com.example.quizapp.R;
import com.example.quizapp.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity { // Используем MainActivity как главное меню

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String TAG = "QuizMainActivity";

    private Button btnPlay, btnShop, btnSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();
        setListeners();

        // --- КРИТИЧЕСКОЕ ИЗМЕНЕНИЕ: Запрос разрешений P2P при старте ---
        requestP2PPermissions();

        // Возобновляем музыку меню (если она была остановлена)
        QuizApplication.getInstance().startBackgroundMusic();
    }

    private void initializeUI() {
        btnPlay = (Button) findViewById(R.id.btn_play);
        btnShop = (Button) findViewById(R.id.btn_shop);
        btnSettings = (Button) findViewById(R.id.btn_settings);
    }

    private void setListeners() {
        btnPlay.setOnClickListener(v -> {
            QuizApplication.getInstance().playClickSound();
            Intent intent = new Intent(MainActivity.this, GameModeSelectionActivity.class);
            startActivity(intent);
        });

        btnShop.setOnClickListener(v -> {
            QuizApplication.getInstance().playClickSound();
            Intent intent = new Intent(MainActivity.this, ShopActivity.class);
            startActivity(intent);
        });

        btnSettings.setOnClickListener(v -> {
            QuizApplication.getInstance().playClickSound();
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    // ----------------------------------------------------------------------
    // --- НОВЫЕ МЕТОДЫ: Запрос и обработка разрешений P2P ---
    // ----------------------------------------------------------------------

    private void requestP2PPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        // 1. ACCESS_FINE_LOCATION (Для старых API и Wi-Fi Direct)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // 2. NEARBY_WIFI_DEVICES (Критически важен для Android 12+ / API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }

            // 3. BLUETOOTH_CONNECT (Если используется Bluetooth)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Запрос недостающих разрешений: " + permissionsToRequest);
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        } else {
            Log.d(TAG, "Все необходимые P2P разрешения уже предоставлены.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                // Если все дали, можно продолжать
                Toast.makeText(this, "Разрешения для P2P успешно получены.", Toast.LENGTH_SHORT).show();
            } else {
                // Если не дали, предупреждаем
                Toast.makeText(this, "Внимание: Некоторые функции игры (Wi-Fi Direct) могут не работать без всех разрешений.", Toast.LENGTH_LONG).show();
            }
        }
    }
}