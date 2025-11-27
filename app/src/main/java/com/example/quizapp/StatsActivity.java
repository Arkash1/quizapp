package com.example.quizapp;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class StatsActivity extends AppCompatActivity {

    private TextView tvPlayerName, tvTotalPoints, tvSingleWins, tvPvpWins, tvEmotesOwned;
    private QuizDatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        dbHelper = QuizDatabaseHelper.getInstance(this);
        initializeUI();
        loadPlayerStats();

        QuizApplication.getInstance().startBackgroundMusic();
    }

    private void initializeUI() {
        tvPlayerName = findViewById(R.id.tv_stats_player_name);
        tvTotalPoints = findViewById(R.id.tv_stats_total_points);
        tvSingleWins = findViewById(R.id.tv_stats_single_wins);
        tvPvpWins = findViewById(R.id.tv_stats_pvp_wins);
        tvEmotesOwned = findViewById(R.id.tv_stats_emotes_owned);
    }

    private void loadPlayerStats() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // 1. Загрузка основной статистики игрока (из TABLE_PLAYER_STATS)
        Cursor statsCursor = db.query(
                QuizDatabaseHelper.TABLE_PLAYER_STATS,
                null,
                QuizDatabaseHelper.STATS_COLUMN_ID + "=1",
                null, null, null, null);

        if (statsCursor.moveToFirst()) {
            String name = statsCursor.getString(statsCursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_NAME));
            int points = statsCursor.getInt(statsCursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_POINTS));
            int singleWins = statsCursor.getInt(statsCursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_SINGLE_WINS));
            int pvpWins = statsCursor.getInt(statsCursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_PVP_WINS));

            tvPlayerName.setText(name);
            tvTotalPoints.setText(getString(R.string.stat_points_format, points));
            tvSingleWins.setText(getString(R.string.stat_single_wins_format, singleWins));
            tvPvpWins.setText(getString(R.string.stat_pvp_wins_format, pvpWins));
        } else {
            tvPlayerName.setText("Данные не найдены");
        }
        statsCursor.close();

        // 2. Подсчет купленных эмоций (из таблицы 'inventory')
        // *ВНИМАНИЕ*: Требуется, чтобы в QuizDatabaseHelper была создана таблица 'inventory'
        try (Cursor inventoryCursor = db.rawQuery("SELECT COUNT(*) FROM inventory WHERE item_id LIKE 'emote_%'", null)) {
            int emoteCount = 0;
            if (inventoryCursor.moveToFirst()) {
                emoteCount = inventoryCursor.getInt(0);
            }
            tvEmotesOwned.setText(getString(R.string.stat_emotes_format, emoteCount));
        } catch (Exception e) {
            // Ошибка, если таблица 'inventory' не существует или запрос неверен
            tvEmotesOwned.setText("Эмоций: N/A (ошибка БД)");
        }
    }
}