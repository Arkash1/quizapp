package com.example.quizapp;

import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GameActivity — turn-based PvP behavior:
 * - host answers first; client shows waiting screen (waiting_screen.mp4 animation)
 * - after host answers, client receives host's ANSWER_SUBMITTED and becomes active (shows same question)
 * - after client answers, host receives client's ANSWER_SUBMITTED and advances to next question (host active)
 * - timer expiry counts as wrong answer (selectedOption = 0) and marks red indicator
 * - emotes: only owned emotes can be used; EMOTE_USED is synchronized to opponent and shown on both sides
 * - when a player disconnects/exits mid-game, the remaining player gets +20 points
 */
public class GameActivity extends AppCompatActivity implements P2PManager.ConnectionListener {

    private static final String TAG = "GameActivity";
    private static final int TOTAL_QUESTIONS = 5;
    private static final long TIMER_DURATION_MS = 15000;

    // UI elements
    private TextView tvQuestion, tvTimer, tvPlayerName, tvOpponentName;
    private TextView tvPlayerStats, tvOpponentStats;
    private Button[] answerButtons = new Button[4];
    private LinearLayout llPlayerIndicators, llOpponentIndicators;
    private View vWaitingScreen, vGameContent;
    private VideoView vvEmoteDisplay, vvOpponentEmoteDisplay;

    // P2P and game state
    private P2PManager p2pManager;
    private boolean isPvpMode;
    private String localPlayerName;
    private String opponentName = "Противник";
    private boolean isMyTurn = false;
    private boolean amHost = false;
    private int currentQuestionIndex = 0;
    private int localPlayerScore = 0;
    private int opponentScore = 0;

    // Per-question flags
    private boolean hostAnsweredCurrent = false;
    private boolean clientAnsweredCurrent = false;

    // Questions
    private List<Question> currentQuestions = new ArrayList<>();
    private CountDownTimer gameTimer;
    private boolean gameInProgress = false;

    // Owned emotes
    private Set<String> ownedEmotes = new HashSet<>();

    private static class Question implements Serializable {
        private static final long serialVersionUID = 1L;
        public int id;
        public String question;
        public String[] options;
        public int answerNum; // 1-4
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);

        isPvpMode = getIntent().getBooleanExtra("IS_PVP_MODE", false);

        initializeUI();
        loadLocalPlayerInfo();

        String intentOpponent = getIntent().getStringExtra("OPPONENT_NAME");
        if (intentOpponent != null && !intentOpponent.trim().isEmpty()) {
            opponentName = intentOpponent;
        }
        tvOpponentName.setText(opponentName);

        boolean intentHost = getIntent().getBooleanExtra("IS_HOST", false);
        amHost = intentHost || P2PConnectionSingleton.getInstance().isGroupOwner();
        isMyTurn = amHost;

        setupQuestionIndicators(llPlayerIndicators);
        if (isPvpMode) setupQuestionIndicators(llOpponentIndicators);

        if (isPvpMode) {
            p2pManager = P2PConnectionSingleton.getInstance().getActiveManager();
            if (p2pManager == null) {
                Toast.makeText(this, "P2P соединение не активно.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            // ensure callbacks come here
            p2pManager.initialize(this, this);

            // Request remote name in case we missed it
            try { p2pManager.sendMessage("REQUEST_PLAYER_NAME"); } catch (Exception ignored) {}

            // Load owned emotes
            loadOwnedEmotes();

            // Host behavior: host should prepare questions and send them to client, then show first question
            if (amHost) {
                loadQuestionsFromDB();
                if (!currentQuestions.isEmpty()) {
                    gameInProgress = true;
                    // send START_GAME to client (questions)
                    p2pManager.sendMessage(new GameDataModel(GameDataModel.DataType.START_GAME, (Serializable) currentQuestions));
                    // host shows first question immediately
                    showQuestion(currentQuestionIndex);
                } else {
                    Toast.makeText(this, "Не удалось загрузить вопросы для PVP.", Toast.LENGTH_LONG).show();
                    endGame();
                }
            } else {
                // client: do NOT show question at start; wait for host's START_GAME and host's first ANSWER_SUBMITTED to trigger client turn
                showWaitingScreen();
            }
        } else {
            setupSinglePlayerMode();
        }

        QuizApplication.getInstance().stopBackgroundMusic();
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

        vvEmoteDisplay = findViewById(R.id.vv_emote_display);
        vvOpponentEmoteDisplay = findViewById(R.id.vv_opponent_emote_display);

        answerButtons[0] = findViewById(R.id.btn_option_1);
        answerButtons[1] = findViewById(R.id.btn_option_2);
        answerButtons[2] = findViewById(R.id.btn_option_3);
        answerButtons[3] = findViewById(R.id.btn_option_4);

        for (int i = 0; i < 4; i++) {
            final int optionIndex = i + 1;
            answerButtons[i].setOnClickListener(v -> {
                // only allow answering when it's player's turn in PvP
                if (!isPvpMode || isMyTurn) handleAnswerSubmission(optionIndex);
            });
        }

        findViewById(R.id.btn_emote_chat).setOnClickListener(v -> showEmoteSelectionDialog());
    }

    private void setupQuestionIndicators(LinearLayout layout) {
        layout.removeAllViews();
        for (int i = 0; i < TOTAL_QUESTIONS; i++) {
            ImageView indicator = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    (int) getResources().getDimension(R.dimen.indicator_size),
                    (int) getResources().getDimension(R.dimen.indicator_size));
            params.setMargins(8, 0, 8, 0);
            indicator.setLayoutParams(params);
            indicator.setImageResource(R.drawable.ic_circle);
            indicator.setColorFilter(getColor(R.color.colorLightGray));
            layout.addView(indicator);
        }
    }

    private void loadLocalPlayerInfo() {
        QuizDatabaseHelper dbHelper = QuizDatabaseHelper.getInstance(this);
        Cursor cursor = null;
        try {
            cursor = dbHelper.getReadableDatabase().query(
                    QuizDatabaseHelper.TABLE_PLAYER_STATS,
                    null, QuizDatabaseHelper.STATS_COLUMN_ID + "=1", null, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                localPlayerName = cursor.getString(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_NAME));
                int singleWins = cursor.getInt(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_SINGLE_WINS));
                int pvpWins = cursor.getInt(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_PVP_WINS));

                tvPlayerName.setText(localPlayerName != null ? localPlayerName : "noname");
                if (!isPvpMode) tvPlayerStats.setText(getString(R.string.wins_format, singleWins));
                else tvPlayerStats.setText(getString(R.string.wins_format, pvpWins));
            } else {
                localPlayerName = "noname";
                tvPlayerName.setText(localPlayerName);
            }
        } catch (Exception e) {
            Log.w(TAG, "loadLocalPlayerInfo: DB read failed", e);
            localPlayerName = "noname";
            tvPlayerName.setText(localPlayerName);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void loadOwnedEmotes() {
        try {
            QuizDatabaseHelper db = QuizDatabaseHelper.getInstance(this);
            List<String> owned = db.getOwnedEmotes();
            if (owned != null) ownedEmotes.addAll(owned);
            Log.d(TAG, "Owned emotes: " + ownedEmotes);
        } catch (Exception e) {
            Log.w(TAG, "loadOwnedEmotes failed", e);
        }
    }

    private void setupSinglePlayerMode() {
        opponentName = "AI Opponent";
        tvOpponentName.setVisibility(View.GONE);
        tvOpponentStats.setVisibility(View.GONE);
        llOpponentIndicators.setVisibility(View.GONE);
        findViewById(R.id.btn_emote_chat).setVisibility(View.GONE);

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

    private void loadQuestionsFromDB() {
        QuizDatabaseHelper dbHelper = QuizDatabaseHelper.getInstance(this);
        currentQuestions.clear();
        Cursor cursor = dbHelper.getReadableDatabase().rawQuery(
                "SELECT * FROM " + QuizDatabaseHelper.TABLE_QUESTIONS + " ORDER BY RANDOM() LIMIT " + TOTAL_QUESTIONS,
                null);

        try {
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
        } finally {
            if (cursor != null) cursor.close();
        }
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
            answerButtons[i].setBackgroundTintList(null);
            answerButtons[i].setBackgroundColor(getColor(R.color.colorDefaultButton));
        }

        // stop waiting video if any
        stopWaitingVideo();

        vWaitingScreen.setVisibility(View.GONE);
        vGameContent.setVisibility(View.VISIBLE);

        startTimer();
    }

    private void showWaitingScreen() {
        vGameContent.setVisibility(View.GONE);
        vWaitingScreen.setVisibility(View.VISIBLE);

        TextView vsText = findViewById(R.id.tv_vs_screen);
        vsText.setText(R.string.waiting_for_opponent);

        // Play waiting video if available (resource raw/waiting_screen.mp4)
        int waitingRes = getResources().getIdentifier("waiting_screen", "raw", getPackageName());
        if (waitingRes != 0 && vvOpponentEmoteDisplay != null) {
            vvOpponentEmoteDisplay.setVisibility(View.VISIBLE);
            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + waitingRes);
            vvOpponentEmoteDisplay.setVideoURI(uri);
            vvOpponentEmoteDisplay.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.setLooping(true);
                    vvOpponentEmoteDisplay.start();
                }
            });
        } else {
            Animation anim = new AlphaAnimation(0.0f, 1.0f);
            anim.setDuration(500);
            anim.setStartOffset(20);
            anim.setRepeatMode(Animation.REVERSE);
            anim.setRepeatCount(Animation.INFINITE);
            vWaitingScreen.startAnimation(anim);
        }

        stopTimer();
    }

    private void stopWaitingVideo() {
        if (vvOpponentEmoteDisplay != null && vvOpponentEmoteDisplay.isPlaying()) {
            vvOpponentEmoteDisplay.stopPlayback();
            vvOpponentEmoteDisplay.setVisibility(View.GONE);
        }
        vWaitingScreen.clearAnimation();
    }

    private void startTimer() {
        if (gameTimer != null) gameTimer.cancel();

        gameTimer = new CountDownTimer(TIMER_DURATION_MS, 1000) {
            @Override public void onTick(long millisUntilFinished) {
                long seconds = millisUntilFinished / 1000;
                tvTimer.setText(getString(R.string.timer_format, seconds));
            }
            @Override public void onFinish() {
                // treat timeout as wrong answer (0)
                handleAnswerSubmission(0);
            }
        }.start();
    }

    private void stopTimer() {
        if (gameTimer != null) { gameTimer.cancel(); gameTimer = null; }
        tvTimer.setText(getString(R.string.timer_format, 0L));
    }

    private void handleAnswerSubmission(int selectedOption) {
        if (!isPvpMode) {
            stopTimer();
            Question q = currentQuestions.get(currentQuestionIndex);
            boolean isCorrect = selectedOption > 0 && selectedOption == q.answerNum;
            updateAnswerUI(selectedOption, q.answerNum, true);
            if (isCorrect) { localPlayerScore += 20; QuizApplication.getInstance().playSound(R.raw.correct); }
            else if (selectedOption > 0) { QuizApplication.getInstance().playSound(R.raw.incorrect); }
            new Handler(Looper.getMainLooper()).postDelayed(this::moveToNextQuestion, 1500);
            return;
        }

        // PvP
        stopTimer();
        Question q = currentQuestions.get(currentQuestionIndex);
        boolean isCorrect = selectedOption > 0 && selectedOption == q.answerNum;

        updateAnswerUI(selectedOption, q.answerNum, true);

        if (isCorrect) {
            localPlayerScore += 25;
            QuizApplication.getInstance().playSound(R.raw.correct);
        } else if (selectedOption > 0) {
            QuizApplication.getInstance().playSound(R.raw.incorrect);
        }

        PlayerAnswer answer = new PlayerAnswer(currentQuestionIndex, selectedOption, isCorrect, true);

        // mark answered locally
        if (amHost) hostAnsweredCurrent = true; else clientAnsweredCurrent = true;

        // send answer to opponent
        try {
            p2pManager.sendMessage(new GameDataModel(GameDataModel.DataType.ANSWER_SUBMITTED, answer));
        } catch (Exception e) {
            Log.w(TAG, "Failed to send ANSWER_SUBMITTED", e);
        }

        // show waiting screen
        isMyTurn = false;
        stopWaitingVideo();
        showWaitingScreen();
    }

    /**
     * Update UI for a submitted answer.
     * @param selectedOption selected by player (0 = timeout/no answer)
     * @param correctOption correct answer index
     * @param isLocal whether it's the local player's indicator (true) or opponent's (false)
     */
    private void updateAnswerUI(int selectedOption, int correctOption, boolean isLocal) {
        for (int i = 0; i < 4; i++) {
            if (i + 1 == correctOption) {
                answerButtons[i].setBackgroundColor(getColor(R.color.colorCorrectAnswer));
            } else if (i + 1 == selectedOption) {
                answerButtons[i].setBackgroundColor(getColor(R.color.colorWrongAnswer));
            }
            answerButtons[i].setEnabled(false);
        }

        ImageView indicator = (ImageView) (isLocal ? llPlayerIndicators.getChildAt(currentQuestionIndex) : llOpponentIndicators.getChildAt(currentQuestionIndex));
        if (indicator != null) {
            indicator.setColorFilter(getColor(selectedOption > 0 && selectedOption == correctOption ?
                    R.color.colorIndicatorGreen : R.color.colorIndicatorRed));
        }
    }

    private void moveToNextQuestion() {
        // Called by host to advance after both answered, or by singleplayer flow
        currentQuestionIndex++;
        hostAnsweredCurrent = false;
        clientAnsweredCurrent = false;
        stopWaitingVideo();
        if (currentQuestionIndex < TOTAL_QUESTIONS) {
            if (isPvpMode) {
                if (amHost) {
                    isMyTurn = true;
                    showQuestion(currentQuestionIndex);
                } else {
                    isMyTurn = false;
                    showWaitingScreen();
                }
            } else {
                showQuestion(currentQuestionIndex);
            }
        } else {
            endGame();
        }
    }

    @Override
    public void onDataReceived(Serializable data) {
        runOnUiThread(() -> {
            // handle PLAYER_NAME string
            if (data instanceof String) {
                String s = (String) data;
                if (s.startsWith("PLAYER_NAME:")) {
                    String remoteName = s.substring("PLAYER_NAME:".length()).trim();
                    if (!remoteName.isEmpty()) {
                        opponentName = remoteName;
                        tvOpponentName.setText(opponentName);
                        TextView vsText = findViewById(R.id.tv_vs_screen);
                        if (vsText != null) vsText.setText(String.format("%s VS %s", localPlayerName, opponentName));
                    }
                    return;
                }
            }

            if (!(data instanceof GameDataModel)) return;
            GameDataModel model = (GameDataModel) data;

            switch (model.type) {
                case START_GAME:
                    // Client receives the whole question list but should stay waiting until host answers
                    if (model.data instanceof List) {
                        currentQuestions.clear();
                        try {
                            currentQuestions.addAll((List<Question>) model.data);
                            gameInProgress = true;
                            // ensure client shows waiting screen at start
                            if (!amHost) {
                                isMyTurn = false;
                                stopWaitingVideo();
                                showWaitingScreen();
                            }
                        } catch (ClassCastException e) {
                            Log.e(TAG, "Error casting START_GAME data", e);
                            endGame();
                        }
                    }
                    break;

                case ANSWER_SUBMITTED:
                    if (model.data instanceof PlayerAnswer) {
                        PlayerAnswer pa = (PlayerAnswer) model.data;
                        // Update opponent indicator and score if correct
                        ImageView indicator = (ImageView) llOpponentIndicators.getChildAt(pa.questionIndex);
                        if (indicator != null) indicator.setColorFilter(getColor(pa.isCorrect ? R.color.colorIndicatorGreen : R.color.colorIndicatorRed));

                        if (amHost) {
                            // Host receives client's answer -> update opponentScore and then proceed to next question
                            if (pa.isCorrect) opponentScore += 25;
                            clientAnsweredCurrent = true;
                            // If host already answered this question -> both answered -> host moves to next question
                            if (hostAnsweredCurrent) {
                                // small delay for UX
                                new Handler(Looper.getMainLooper()).postDelayed(this::moveToNextQuestion, 800);
                            }
                        } else {
                            // Client receives host's answer -> update opponentScore and now it's client's turn for same question
                            if (pa.isCorrect) opponentScore += 25;
                            hostAnsweredCurrent = true;
                            // client becomes active
                            isMyTurn = true;
                            stopWaitingVideo();
                            showQuestion(currentQuestionIndex);
                        }
                    }
                    break;

                case EMOTE_USED:
                    if (model.data instanceof EmoteAction) {
                        String emoteId = ((EmoteAction) model.data).emoteName;
                        // Show emote on opponent area
                        showOpponentEmote(emoteId);
                    }
                    break;

                case GAME_OVER:
                    if (gameInProgress) endGame();
                    break;
            }
        });
    }

    private void endGame() {
        if (!gameInProgress) return;
        gameInProgress = false;
        stopTimer();
        stopWaitingVideo();

        boolean isWinner = localPlayerScore > opponentScore;
        String message;
        if (isPvpMode) {
            message = isWinner ? "Победа!" : (localPlayerScore == opponentScore ? "Ничья" : "Поражение!");
            QuizApplication.getInstance().playSound(isWinner ? R.raw.victory : R.raw.defeat);
        } else {
            message = localPlayerScore > 0 ? "Одиночная игра завершена" : "Игра провалена.";
            QuizApplication.getInstance().playSound(localPlayerName != null ? R.raw.victory : R.raw.defeat);
        }

        saveGameResults(isWinner);

        if (isPvpMode && p2pManager != null) {
            try { p2pManager.sendMessage(new GameDataModel(GameDataModel.DataType.GAME_OVER, null)); } catch (Exception ignored) {}
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

    // EMOTES
    private void showEmoteSelectionDialog() {
        List<String> owned = new ArrayList<>(ownedEmotes);
        if (owned.isEmpty()) {
            Toast.makeText(this, "У вас нет купленных эмоций.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] ownedArray = owned.toArray(new String[0]);
        String[] displayNames = new String[ownedArray.length];
        for (int i = 0; i < ownedArray.length; i++) displayNames[i] = ownedArray[i];

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Выберите эмоцию");
        builder.setItems(displayNames, (dialog, which) -> {
            String selectedEmoteId = ownedArray[which];
            // Normalize id to resource name (strip "emote_" prefix and extension if present)
            String resName = normalizeEmoteIdToResourceName(selectedEmoteId);
            // Local play
            showLocalEmote(resName);
            // send to opponent
            if (isPvpMode && p2pManager != null) {
                try {
                    p2pManager.sendMessage(new GameDataModel(GameDataModel.DataType.EMOTE_USED, new EmoteAction(resName)));
                } catch (Exception e) {
                    Log.w(TAG, "Failed to send EMOTE_USED", e);
                }
            }
        });
        builder.show();
    }

    private String normalizeEmoteIdToResourceName(String id) {
        if (id == null) return "";
        String res = id;
        // remove extension
        int dot = res.lastIndexOf('.');
        if (dot > 0) res = res.substring(0, dot);
        // remove "emote_" prefix if present
        if (res.startsWith("emote_")) res = res.substring("emote_".length());
        return res;
    }

    private void playEmoteVideo(VideoView videoView, String emoteName) {
        if (emoteName == null || emoteName.isEmpty()) return;
        String resName = normalizeEmoteIdToResourceName(emoteName);
        int resourceId = getResources().getIdentifier(resName, "raw", getPackageName());
        if (resourceId != 0) {
            videoView.setVisibility(View.VISIBLE);
            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + resourceId);
            videoView.setVideoURI(uri);
            videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.setLooping(false);
                    videoView.start();
                }
            });
            videoView.setOnCompletionListener(mp -> videoView.setVisibility(View.GONE));
        } else {
            Log.w(TAG, "Emote resource not found: " + emoteName + " (tried " + resName + ")");
        }
    }

    private void showLocalEmote(String emoteName) {
        playEmoteVideo(vvEmoteDisplay, emoteName);
    }

    private void showOpponentEmote(String emoteName) {
        playEmoteVideo(vvOpponentEmoteDisplay, emoteName);
    }

    @Override public void onConnected(String deviceName, ConnectionType type) {}
    @Override public void onConnectionFailed(String message) {
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
                // award remaining player 20 points (as requested)
                localPlayerScore += 20;
                endGame();
            }
        });
    }

    @Override public void onDeviceFound(String deviceName, String deviceAddress) {}
    @Override public void onDeviceLost(String deviceAddress) {}

    @Override protected void onDestroy() {
        super.onDestroy();
        if (gameTimer != null) gameTimer.cancel();
        stopWaitingVideo();
        QuizApplication.getInstance().startBackgroundMusic();
    }

    @Override public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Выход из игры")
                .setMessage("Вы уверены, что хотите выйти? Вы проиграете игру.")
                .setPositiveButton("Выйти", (dialog, which) -> {
                    // If quitting during PvP, inform opponent by best-effort GAME_OVER
                    if (isPvpMode && p2pManager != null) {
                        try { p2pManager.sendMessage(new GameDataModel(GameDataModel.DataType.GAME_OVER, null)); } catch (Exception ignored) {}
                    }
                    localPlayerScore = 0;
                    opponentScore = isPvpMode ? 20 : 0; // if leaving, opponent will get 20
                    endGame();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}