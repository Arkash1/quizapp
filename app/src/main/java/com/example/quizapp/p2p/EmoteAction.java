package com.example.quizapp.p2p;

import java.io.Serializable;

/**
 * Передает информацию об использованной эмоции.
 */
public class EmoteAction implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String emoteName;

    public EmoteAction(String emoteName) {
        this.emoteName = emoteName;
    }
}