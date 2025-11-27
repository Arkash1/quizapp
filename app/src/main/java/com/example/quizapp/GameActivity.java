package com.example.quizapp;

import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.quizapp.QuizApplication;
import com.example.quizapp.p2p.ConnectionType;
import com.example.quizapp.p2p.EmoteAction;
import com.example.quizapp.p2p.GameDataModel;
import com.example.quizapp.p2p.P2PConnectionSingleton;
import com.example.quizapp.p2p.P2PManager;
import com.example.quizapp.p2p.PlayerAnswer;
import com.example.quizapp.QuizDatabaseHelper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class GameActivity extends AppCompatActivity implements P2PManager.ConnectionListener {

    private static final String TAG = "GameActivity";
    private static final int TOTAL_QUESTIONS = 5;
    private static final long TIMER_DURATION_MS = 15000;

    // UI элементы
    private TextView tvQuestion, tvTimer, tvPlayerName, tvOpponentName;
    private TextView tvPlayerStats, tvOpponentStats;
    private Button[] answerButtons = new Button[4];
    private LinearLayout llPlayerIndicators, llOpponentIndicators;
    private View vWaitingScreen, vGameContent;
    private VideoView vvEmoteDisplay, vvOpponentEmoteDisplay; // Для видео-эмоций

    // P2P и состояние игры
    private P2PManager p2pManager;
    private boolean isPvpMode;
    private String localPlayerName;
    private String opponentName = "AI Opponent"; // Значение по умолчанию
    private boolean isMyTurn = false; // Актуально только для PvP
    private int currentQuestionIndex = 0;
    private int localPlayerScore = 0;
    private int opponentScore = 0;

    // Данные вопросов
    private List<Question> currentQuestions = new ArrayList<>();
    private CountDownTimer gameTimer;
    private boolean gameInProgress = false;

    // Класс для хранения структуры вопроса (Serializable для P2P)
    private static class Question implements Serializable {
        private static final long serialVersionUID = 1L;
        public int id;
        public String question;
        public String[] options;
        public int answerNum; // 1-4
    }

// --- ON CREATE И ИНИЦИАЛИЗАЦИЯ ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);

        initializeUI();
        loadLocalPlayerInfo();

        isPvpMode = getIntent().getBooleanExtra("IS_PVP_MODE", false);

        // Добавление индикаторов вопросов (кружочков)
        setupQuestionIndicators(llPlayerIndicators);
        if (isPvpMode) {
            setupQuestionIndicators(llOpponentIndicators);
        }

        if (isPvpMode) {
            setupPvpMode();
        } else {
            setupSinglePlayerMode();
        }

        QuizApplication.getInstance().stopBackgroundMusic(); // Останавливаем музыку меню
    }

    private void initializeUI() {
        tvQuestion = findViewById(R.id.tv_question);
        tvTimer = findViewById(R.id.tv_timer);
        tvPlayerName = findViewById(R.id.tv_player_name);
        tvOpponentName = findViewById(R.id.tv_opponent_name);
        tvPlayerStats = findViewById(R.id.tv_player_stats);
        tvOpponentStats = findViewById(R.id.tv_opponent_stats);
        llPlayerIndicators = findViewById(R.id.ll_player_indicators);
        llOpponentIndicators = findViewById(R.id.ll_opponent_indicators);
        vWaitingScreen = findViewById(R.id.game_waiting_video_container);
        vGameContent = findViewById(R.id.game_content_layout);

        // VideoView для эмоций (заменили ImageView)
        vvEmoteDisplay = findViewById(R.id.vv_emote_display);
        vvOpponentEmoteDisplay = findViewById(R.id.vv_opponent_emote_display);

        // Инициализация кнопок ответов
        answerButtons[0] = findViewById(R.id.btn_option_1);
        answerButtons[1] = findViewById(R.id.btn_option_2);
        answerButtons[2] = findViewById(R.id.btn_option_3);
        answerButtons[3] = findViewById(R.id.btn_option_4);

        for (int i = 0; i < 4; i++) {
            final int optionIndex = i + 1;
            answerButtons[i].setOnClickListener(v -> handleAnswerSubmission(optionIndex));
        }

        // Кнопка эмоций
        findViewById(R.id.btn_emote_chat).setOnClickListener(v -> showEmoteSelectionDialog());
    }

    // Динамическое добавление кружочков-индикаторов
    private void setupQuestionIndicators(LinearLayout layout) {
        layout.removeAllViews(); // Очистка заглушек из XML
        for (int i = 0; i < TOTAL_QUESTIONS; i++) {
            ImageView indicator = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    (int) getResources().getDimension(R.dimen.indicator_size),
                    (int) getResources().getDimension(R.dimen.indicator_size));
            params.setMargins(8, 0, 8, 0);
            indicator.setLayoutParams(params);
            indicator.setImageResource(R.drawable.ic_circle);
            // Применяем стандартный цвет из styles.xml
            indicator.setColorFilter(getColor(R.color.colorLightGray));
            layout.addView(indicator);
        }
    }

    private void loadLocalPlayerInfo() {
        QuizDatabaseHelper dbHelper = QuizDatabaseHelper.getInstance(this);
        Cursor cursor = dbHelper.getReadableDatabase().query(
                QuizDatabaseHelper.TABLE_PLAYER_STATS,
                null, QuizDatabaseHelper.STATS_COLUMN_ID + "=1", null, null, null, null);

        if (cursor.moveToFirst()) {
            localPlayerName = cursor.getString(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_NAME));
            int singleWins = cursor.getInt(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_SINGLE_WINS));
            int pvpWins = cursor.getInt(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_PVP_WINS));

            tvPlayerName.setText(localPlayerName);

            if (!isPvpMode) {
                tvPlayerStats.setText(getString(R.string.wins_format, singleWins));
            } else {
                tvPlayerStats.setText(getString(R.string.wins_format, pvpWins));
            }
        } else {
            localPlayerName = "noname";
        }
        cursor.close();
    }

// --- НАСТРОЙКА РЕЖИМОВ ИГРЫ ---

    private void setupSinglePlayerMode() {
        opponentName = "AI Opponent";
        tvOpponentName.setVisibility(View.GONE);
        tvOpponentStats.setVisibility(View.GONE);
        llOpponentIndicators.setVisibility(View.GONE);
        findViewById(R.id.btn_emote_chat).setVisibility(View.GONE); // Скрыть эмоции в соло

        vWaitingScreen.setVisibility(View.GONE);
        vGameContent.setVisibility(View.VISIBLE);

        loadQuestionsFromDB();
        if (!currentQuestions.isEmpty()) {
            gameInProgress = true;
            showQuestion(currentQuestionIndex);
        } else {
            Toast.makeText(this, "Вопросы не загружены.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setupPvpMode() {
        p2pManager = P2PConnectionSingleton.getInstance().getActiveManager();
        opponentName = getIntent().getStringExtra("OPPONENT_NAME");

        if (p2pManager == null || opponentName == null) {
            Toast.makeText(this, "P2P соединение не активно.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        p2pManager.initialize(this, this);

        tvOpponentName.setText(opponentName);
        tvOpponentStats.setText(getString(R.string.wins_format, 0)); // Обновится позже

        showVsScreen();

        // Определяем, кто начинает (например, тот, кто инициировал соединение, отправляет данные)
        // Для простоты, мы будем использовать флаг isMyTurn как признак, что этот игрок отвечает за рассылку вопросов.
        isMyTurn = getIntent().getBooleanExtra("IS_HOST", false);

        if (isMyTurn) {
            sendInitialData();
        }
    }

    private void sendInitialData() {
        loadQuestionsFromDB();

        if (!currentQuestions.isEmpty()) {
            GameDataModel startGameModel = new GameDataModel(GameDataModel.DataType.START_GAME, (Serializable) currentQuestions);
            p2pManager.sendMessage(startGameModel);
            gameInProgress = true;
        } else {
            Toast.makeText(this, "Не удалось загрузить вопросы для PVP.", Toast.LENGTH_LONG).show();
            endGame();
        }
    }

    private void showVsScreen() {
        vGameContent.setVisibility(View.GONE);
        vWaitingScreen.setVisibility(View.VISIBLE);

        TextView vsText = findViewById(R.id.tv_vs_screen);
        vsText.setText(String.format("%s VS %s", localPlayerName, opponentName));

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            vWaitingScreen.setVisibility(View.GONE);
            vGameContent.setVisibility(View.VISIBLE);

            // Если мы хост и уже отправили вопросы, начинаем игру
            if (isPvpMode && isMyTurn && !currentQuestions.isEmpty()) {
                showQuestion(currentQuestionIndex);
            } else if (isPvpMode) {
                showWaitingScreen();
            }
        }, 3000); // 3 секунды VS-экран
    }

// --- УПРАВЛЕНИЕ ВОПРОСАМИ И ЛОГИКА ---

    private void loadQuestionsFromDB() {
        QuizDatabaseHelper dbHelper = QuizDatabaseHelper.getInstance(this);
        // Выбираем 5 случайных вопросов
        Cursor cursor = dbHelper.getReadableDatabase().rawQuery(
                "SELECT * FROM " + QuizDatabaseHelper.TABLE_QUESTIONS + " ORDER BY RANDOM() LIMIT " + TOTAL_QUESTIONS,
                null);

        if (cursor.moveToFirst()) {
            do {
                Question q = new Question();
                q.id = cursor.getInt(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.COLUMN_ID));
                q.question = cursor.getString(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.COLUMN_QUESTION));
                q.options = new String[]{
                        cursor.getString(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.COLUMN_OPTION1)),
                        cursor.getString(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.COLUMN_OPTION2)),
                        cursor.getString(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.COLUMN_OPTION3)),
                        cursor.getString(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.COLUMN_OPTION4))
                };
                q.answerNum = cursor.getInt(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.COLUMN_ANSWER_NUM));
                currentQuestions.add(q);
            } while (cursor.moveToNext());
        }
        cursor.close();
    }

    private void showQuestion(int index) {
        if (index >= currentQuestions.size()) {
            endGame();
            return;
        }

        currentQuestionIndex = index;
        Question q = currentQuestions.get(index);

        tvQuestion.setText(q.question);
        for (int i = 0; i < 4; i++) {
            answerButtons[i].setText(q.options[i]);
            answerButtons[i].setEnabled(true);
            answerButtons[i].setBackgroundTintList(null); // Сброс цвета
            answerButtons[i].setBackgroundColor(getColor(R.color.colorDefaultButton));
        }

        vWaitingScreen.setVisibility(View.GONE);
        vGameContent.setVisibility(View.VISIBLE);

        startTimer();
    }

    private void showWaitingScreen() {
        vGameContent.setVisibility(View.GONE);
        vWaitingScreen.setVisibility(View.VISIBLE);

        TextView vsText = findViewById(R.id.tv_vs_screen);
        vsText.setText(R.string.waiting_for_opponent);

        // TODO: Здесь проигрывание waiting_screen.mp4 в VideoView

        // Пример анимации
        Animation anim = new AlphaAnimation(0.0f, 1.0f);
        anim.setDuration(500);
        anim.setStartOffset(20);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(Animation.INFINITE);
        vWaitingScreen.startAnimation(anim);

        stopTimer();
    }

// --- ТАЙМЕР И ОБРАБОТКА ОТВЕТА ---

    private void startTimer() {
        if (gameTimer != null) {
            gameTimer.cancel();
        }

        gameTimer = new CountDownTimer(TIMER_DURATION_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long seconds = millisUntilFinished / 1000;
                tvTimer.setText(getString(R.string.timer_format, seconds));
            }

            @Override
            public void onFinish() {
                // Время вышло: 0 - означает, что время вышло/нет ответа
                handleAnswerSubmission(0);
            }
        }.start();
    }

    private void stopTimer() {
        if (gameTimer != null) {
            gameTimer.cancel();
            gameTimer = null;
        }
        tvTimer.setText(getString(R.string.timer_format, 0L));
    }

    private void handleAnswerSubmission(int selectedOption) {
        if (!gameInProgress) return;

        stopTimer();

        Question q = currentQuestions.get(currentQuestionIndex);
        boolean isCorrect = selectedOption > 0 && selectedOption == q.answerNum;

        // 1. Обновление UI
        updateAnswerUI(selectedOption, q.answerNum);

        // 2. Начисление очков
        if (isCorrect) {
            localPlayerScore += isPvpMode ? 25 : 20;
            QuizApplication.getInstance().playSound(R.raw.correct);
        } else if (selectedOption > 0) {
            QuizApplication.getInstance().playSound(R.raw.incorrect);
        }

        // 3. Создание объекта ответа для синхронизации
        PlayerAnswer answer = new PlayerAnswer(currentQuestionIndex, selectedOption, isCorrect, true);

        if (isPvpMode) {
            // 4. Отправка ответа противнику
            p2pManager.sendMessage(new GameDataModel(GameDataModel.DataType.ANSWER_SUBMITTED, answer));

            // 5. Передача хода (Ожидание ответа противника)
            isMyTurn = false;

        } else {
            // 4. Одиночная игра: Просто ждем и переходим к следующему вопросу
            new Handler(Looper.getMainLooper()).postDelayed(this::moveToNextQuestion, 1500);
        }
    }

    private void updateAnswerUI(int selectedOption, int correctOption) {
        // Подсветка кнопок
        for (int i = 0; i < 4; i++) {
            if (i + 1 == correctOption) {
                answerButtons[i].setBackgroundColor(getColor(R.color.colorCorrectAnswer));
            } else if (i + 1 == selectedOption) {
                answerButtons[i].setBackgroundColor(getColor(R.color.colorWrongAnswer));
            }
            answerButtons[i].setEnabled(false);
        }

        // Обновление индикатора
        ImageView indicator = (ImageView) llPlayerIndicators.getChildAt(currentQuestionIndex);
        indicator.setColorFilter(getColor(selectedOption > 0 && selectedOption == correctOption ?
                R.color.colorIndicatorGreen : R.color.colorIndicatorRed));
    }

    private void moveToNextQuestion() {
        currentQuestionIndex++;
        if (currentQuestionIndex < TOTAL_QUESTIONS) {
            if (isPvpMode) {
                // В PVP, когда ход возвращается, показываем следующий вопрос
                isMyTurn = true;
                showQuestion(currentQuestionIndex);
            } else {
                // Одиночная игра: сразу показываем следующий вопрос
                showQuestion(currentQuestionIndex);
            }
        } else {
            endGame();
        }
    }

// --- P2P СИНХРОНИЗАЦИЯ И ОБРАБОТКА ДАННЫХ ---

    @Override
    public void onDataReceived(Serializable data) {
        runOnUiThread(() -> {
            if (!(data instanceof GameDataModel)) return;

            GameDataModel model = (GameDataModel) data;

            switch (model.type) {
                case START_GAME:
                    if (model.data instanceof List) {
                        currentQuestions.clear();
                        try {
                            currentQuestions.addAll((List<Question>) model.data);
                            gameInProgress = true;
                            // Начинаем игру, если мы не хост (не отправляли вопросы)
                            if (!isMyTurn) showWaitingScreen();
                        } catch (ClassCastException e) {
                            Log.e(TAG, "Ошибка приведения типов в START_GAME", e);
                            endGame();
                        }
                    }
                    break;
                case ANSWER_SUBMITTED:
                    if (model.data instanceof PlayerAnswer) {
                        handleOpponentAnswer((PlayerAnswer) model.data);
                    }
                    break;
                case EMOTE_USED:
                    if (model.data instanceof EmoteAction) {
                        showOpponentEmote(((EmoteAction) model.data).emoteName);
                    }
                    break;
                case GAME_OVER:
                    // Противник завершил игру, если у нас еще нет результатов
                    if (gameInProgress) endGame();
                    break;
            }
        });
    }

    private void handleOpponentAnswer(PlayerAnswer opponentAnswer) {
        // 1. Обновление счета противника
        if (opponentAnswer.isCorrect) {
            opponentScore += 25;
            // QuizApplication.getInstance().playSound(R.raw.correct); // Играет локальный звук противника
        } else if (opponentAnswer.selectedOption > 0) {
            // QuizApplication.getInstance().playSound(R.raw.incorrect);
        }

        // 2. Обновление кружочков противника
        ImageView indicator = (ImageView) llOpponentIndicators.getChildAt(opponentAnswer.questionIndex);
        indicator.setColorFilter(getColor(opponentAnswer.isCorrect ?
                R.color.colorIndicatorGreen : R.color.colorIndicatorRed));

        // 3. Передача хода обратно (только если мы уже ответили на свой вопрос)
        if (opponentAnswer.questionIndex == currentQuestionIndex) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                moveToNextQuestion(); // Вызовет showQuestion и isMyTurn = true
            }, 1500);
        } else if (opponentAnswer.questionIndex < currentQuestionIndex) {
            // Если противник ответил раньше, чем мы, просто ждем нашего ответа.
            // В идеале, ходы должны строго чередоваться.
        }
    }

// --- КОНЕЦ ИГРЫ И ЭМОЦИИ ---

    private void endGame() {
        if (!gameInProgress) return;
        gameInProgress = false;
        stopTimer();

        boolean isWinner = localPlayerScore > opponentScore;
        String message;

        if (isPvpMode) {
            message = isWinner ? "Победа!" : (localPlayerScore == opponentScore ? "Ничья" : "Поражение!");
            QuizApplication.getInstance().playSound(isWinner ? R.raw.victory : R.raw.defeat);
        } else {
            message = localPlayerScore > 0 ? "Одиночная игра завершена" : "Игра провалена.";
            QuizApplication.getInstance().playSound(localPlayerScore > 0 ? R.raw.victory : R.raw.defeat);
        }

        saveGameResults(isWinner);

        if (isPvpMode) {
            // Отправляем противнику, что игра окончена
            p2pManager.sendMessage(new GameDataModel(GameDataModel.DataType.GAME_OVER, null));
            P2PConnectionSingleton.getInstance().clear();
        }

        Intent resultsIntent = new Intent(this, EndGameActivity.class);
        resultsIntent.putExtra("MESSAGE", message);
        resultsIntent.putExtra("IS_WINNER", isWinner);
        resultsIntent.putExtra("LOCAL_SCORE", localPlayerScore);
        resultsIntent.putExtra("OPPONENT_SCORE", opponentScore);
        startActivity(resultsIntent);

        finish();
    }

    private void saveGameResults(boolean isWinner) {
        QuizDatabaseHelper dbHelper = QuizDatabaseHelper.getInstance(this);
        dbHelper.updatePlayerStats(localPlayerScore, isPvpMode, isWinner);
    }

    private void showEmoteSelectionDialog() {
        // TODO: Загрузка купленных эмоций из БД (например, "laugh", "cry", "angry")
        String[] ownedEmotes = {"laugh", "cry", "angry"}; // Имена, соответствующие raw-файлам
        String[] displayNames = {"Смех 😂", "Плач 😭", "Злость 😡"}; // Отображаемые имена

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Выберите эмоцию");
        builder.setItems(displayNames, (dialog, which) -> {
            String selectedEmoteId = ownedEmotes[which];
            showLocalEmote(selectedEmoteId);

            if (isPvpMode && p2pManager != null) {
                p2pManager.sendMessage(new GameDataModel(GameDataModel.DataType.EMOTE_USED, new EmoteAction(selectedEmoteId)));
            }
        });
        builder.show();
    }

    private void playEmoteVideo(VideoView videoView, String emoteName) {
        int resourceId = getResources().getIdentifier(emoteName, "raw", getPackageName());

        if (resourceId != 0) {
            videoView.setVisibility(View.VISIBLE);

            // Создаем URI из ресурса
            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + resourceId);
            videoView.setVideoURI(uri);

            // Начинаем проигрывание
            videoView.start();

            // Скрываем VideoView после завершения видео
            videoView.setOnCompletionListener(mp -> videoView.setVisibility(View.GONE));
        }
    }

    private void showLocalEmote(String emoteName) {
        // Показываем эмоцию справа внизу (для локального игрока)
        playEmoteVideo(vvEmoteDisplay, emoteName);
    }

    private void showOpponentEmote(String emoteName) {
        // Показываем эмоцию слева вверху (для противника)
        playEmoteVideo(vvOpponentEmoteDisplay, emoteName);
    }

// --- ОБРАБОТКА P2P ОШИБОК И ЖИЗНЕННОГО ЦИКЛА ---

    @Override
    public void onConnected(String deviceName, ConnectionType type) { /* Игнорируем */ }

    @Override
    public void onConnectionFailed(String message) {
        runOnUiThread(() -> {
            if (isPvpMode) {
                Toast.makeText(this, "P2P Ошибка: " + message, Toast.LENGTH_LONG).show();
                endGame();
            }
        });
    }

    @Override
    public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            if (isPvpMode && gameInProgress) {
                Toast.makeText(this, "Противник отключился: " + reason, Toast.LENGTH_LONG).show();
                // Объявляем победу, если противник отключился во время игры
                localPlayerScore = 999;
                opponentScore = 0;
                endGame();
            }
        });
    }

    @Override
    public void onDeviceFound(String deviceName, String deviceAddress) { /* Игнорируем */ }
    @Override
    public void onDeviceLost(String deviceAddress) { /* Игнорируем */ }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gameTimer != null) {
            gameTimer.cancel();
        }
        if (isPvpMode && p2pManager != null) {
            P2PConnectionSingleton.getInstance().clear();
        }
        QuizApplication.getInstance().startBackgroundMusic();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Выход из игры")
                .setMessage("Вы уверены, что хотите выйти? Вы проиграете игру.")
                .setPositiveButton("Выйти", (dialog, which) -> {
                    localPlayerScore = 0;
                    opponentScore = isPvpMode ? 999 : 0; // В PVP противник побеждает
                    endGame();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}