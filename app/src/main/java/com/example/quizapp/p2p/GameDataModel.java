package com.example.quizapp.p2p;

import java.io.Serializable;

/**
 * Основная модель данных для обмена между игроками.
 */
public class GameDataModel implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum DataType {
        START_GAME,     // Начало игры (передача набора вопросов)
        QUESTION_INDEX, // Текущий индекс вопроса
        ANSWER_SUBMITTED, // Ответ игрока
        EMOTE_USED,     // Использование эмоции
        GAME_OVER       // Конец игры
    }

    public final DataType type;
    public final Serializable data;

    public GameDataModel(DataType type, Serializable data) {
        this.type = type;
        this.data = data;
    }
}