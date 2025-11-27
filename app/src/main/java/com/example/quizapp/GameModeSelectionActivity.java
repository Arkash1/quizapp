package com.example.quizapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class GameModeSelectionActivity extends AppCompatActivity {

    private Button btnSinglePlayer, btnPvpMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Убедитесь, что этот layout содержит кнопки для выбора режима
        setContentView(R.layout.activity_game_mode_selection);

        initializeUI();
        setListeners();
    }

    private void initializeUI() {
        btnSinglePlayer = findViewById(R.id.btn_single_player);
        btnPvpMode = findViewById(R.id.btn_pvp_player);
    }

    private void setListeners() {
        // ОДИНОЧНАЯ ИГРА (Solo)
        btnSinglePlayer.setOnClickListener(v -> {
            QuizApplication.getInstance().playClickSound();
            // Переход сразу в GameActivity с флагом isPvpMode = false
            Intent intent = new Intent(GameModeSelectionActivity.this, GameActivity.class);
            intent.putExtra("IS_PVP_MODE", false);
            startActivity(intent);
            finish();
        });

        // PVP РЕЖИМ
        btnPvpMode.setOnClickListener(v -> {
            QuizApplication.getInstance().playClickSound();
            // Переход в P2PConnectActivity для настройки соединения
            Intent intent = new Intent(GameModeSelectionActivity.this, P2PConnectActivity.class);
            startActivity(intent);
            finish();
        });
    }
}