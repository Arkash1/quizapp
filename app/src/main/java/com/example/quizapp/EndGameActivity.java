package com.example.quizapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class EndGameActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_end_game);

        Intent intent = getIntent();
        String message = intent.getStringExtra("MESSAGE");
        boolean isWinner = intent.getBooleanExtra("IS_WINNER", false);
        int localScore = intent.getIntExtra("LOCAL_SCORE", 0);
        int opponentScore = intent.getIntExtra("OPPONENT_SCORE", -1); // -1 если одиночный режим

        TextView tvTitle = findViewById(R.id.tv_result_title);
        TextView tvScoreLocal = findViewById(R.id.tv_score_local);
        TextView tvScoreOpponent = findViewById(R.id.tv_score_opponent);
        TextView tvMessage = findViewById(R.id.tv_result_message);
        Button btnContinue = findViewById(R.id.btn_continue);

        // 1. Установка заголовка
        tvTitle.setText(message);
        tvTitle.setTextColor(getColor(isWinner ? R.color.colorIndicatorGreen : R.color.colorIndicatorRed));

        // 2. Отображение счета
        tvScoreLocal.setText(getString(R.string.your_score, localScore));

        if (opponentScore != -1) {
            // PVP Режим
            tvScoreOpponent.setText(getString(R.string.opponent_score, opponentScore));
            tvMessage.setText(isWinner ?
                    "Отличная победа! Ваш противник был повержен." :
                    "К сожалению, в этот раз победа досталась сопернику. Попробуйте снова!");
        } else {
            // Одиночный режим
            tvScoreOpponent.setVisibility(View.GONE);
            tvMessage.setText(localScore > 0 ?
                    "Вы успешно завершили игру! На вашем счету " + localScore + " очков." :
                    "Вы не набрали очков. Попробуйте еще раз!");
        }

        // 3. Обработка кнопки "Продолжить"
        btnContinue.setOnClickListener(v -> {
            QuizApplication.getInstance().playClickSound();
            // Возвращаемся на главный экран (или Main Menu Activity)
            Intent homeIntent = new Intent(EndGameActivity.this, MainActivity.class);
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
            finish();
        });

        // Опционально: запуск фоновой музыки
        QuizApplication.getInstance().startBackgroundMusic();
    }

    @Override
    public void onBackPressed() {
        // Принудительный возврат в главное меню
        Intent homeIntent = new Intent(EndGameActivity.this, MainActivity.class);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        finish();
    }
}