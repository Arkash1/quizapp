package com.example.quizapp.p2p;

import java.io.Serializable;

/**
 * Передает ответ игрока и его результат.
 */
public class PlayerAnswer implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int questionIndex;
    public final int selectedOption;
    public final boolean isCorrect;
    public final boolean isLocalPlayer; // Для отметки ответа в кружочках

    public PlayerAnswer(int questionIndex, int selectedOption, boolean isCorrect, boolean isLocalPlayer) {
        this.questionIndex = questionIndex;
        this.selectedOption = selectedOption;
        this.isCorrect = isCorrect;
        this.isLocalPlayer = isLocalPlayer;
    }
}