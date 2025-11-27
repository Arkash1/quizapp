package com.example.quizapp.shop;

public class ShopItem {
    public final String id;
    public final String name;
    public final int price;
    public final String description;
    public final boolean isEmote; // true для эмоций, false для скинов/другого

    public ShopItem(String id, String name, int price, String description, boolean isEmote) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.description = description;
        this.isEmote = isEmote;
    }
}