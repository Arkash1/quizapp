package com.example.quizapp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.quizapp.shop.ShopItem;
import com.example.quizapp.shop.ShopItemAdapter;

import java.util.ArrayList; // Используем ArrayList для ownedItemsIds
import java.util.Arrays;
import java.util.List;

public class ShopActivity extends AppCompatActivity implements ShopItemAdapter.ShopPurchaseListener {

    private TextView tvPlayerPoints;
    private ListView lvShopItems;
    private ShopItemAdapter adapter;
    private int currentPoints;
    private QuizDatabaseHelper dbHelper;
    private List<String> ownedItemsIds; // Список ID уже купленных предметов

    // Список всех доступных для покупки товаров
    private final List<ShopItem> availableItems = Arrays.asList(
            new ShopItem("emote_laugh", "Смех", 150, "Посмейтесь над противником.", true),
            new ShopItem("emote_cry", "Плач", 200, "Выразите свое разочарование.", true),
            new ShopItem("emote_angry", "Злость", 300, "Покажите свою ярость.", true)
    );

    private void showLocalEmote(String emoteName) {
        // Для отладки
        Toast.makeText(this, "Моя эмоция: " + emoteName, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shop);

        tvPlayerPoints = findViewById(R.id.tv_player_points);
        lvShopItems = findViewById(R.id.lv_shop_items);
        dbHelper = QuizDatabaseHelper.getInstance(this);

        // 1. Загружаем купленные предметы ДО загрузки очков и настройки списка
        loadOwnedItems();

        loadPlayerPoints();
        setupShopList();

        QuizApplication.getInstance().startBackgroundMusic();
    }

    // Загрузка текущих очков игрока
    private void loadPlayerPoints() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                QuizDatabaseHelper.TABLE_PLAYER_STATS,
                new String[]{QuizDatabaseHelper.STATS_COLUMN_POINTS},
                QuizDatabaseHelper.STATS_COLUMN_ID + "=1", null, null, null, null);

        if (cursor.moveToFirst()) {
            currentPoints = cursor.getInt(cursor.getColumnIndexOrThrow(QuizDatabaseHelper.STATS_COLUMN_POINTS));
        } else {
            currentPoints = 0;
        }
        cursor.close();

        // Убедитесь, что у вас есть строка current_points в strings.xml, которая принимает один int аргумент
        tvPlayerPoints.setText(getString(R.string.current_points, currentPoints));
    }

    // Загрузка ID купленных предметов из инвентаря
    private void loadOwnedItems() {
        ownedItemsIds = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ВАЖНО: Предполагается, что существует таблица 'inventory' с колонкой 'item_id'
        // Если такой таблицы нет, этот метод вызовет ошибку!
        try (Cursor cursor = db.query("inventory", new String[]{"item_id"}, null, null, null, null, null)) {
            if (cursor.moveToFirst()) {
                int itemIdIndex = cursor.getColumnIndexOrThrow("item_id");
                do {
                    ownedItemsIds.add(cursor.getString(itemIdIndex));
                } while (cursor.moveToNext());
            }
        } catch (IllegalArgumentException e) {
            // Обработка ошибки, если колонка не найдена (например, нет таблицы)
            // Здесь может быть Toast или Log, предупреждающий о том, что нужно создать таблицу 'inventory'.
            // Log.e("ShopActivity", "Таблица инвентаря или колонка 'item_id' не найдены: " + e.getMessage());
        }
    }

    //  Настройка адаптера магазина
    private void setupShopList() {
        // ИСПРАВЛЕНИЕ: Передаем АДАПТЕРУ ТЕКУЩИЕ ОЧКИ И СПИСОК КУПЛЕННЫХ ID
        adapter = new ShopItemAdapter(this, availableItems, this, currentPoints, ownedItemsIds);
        lvShopItems.setAdapter(adapter);
    }

    // Метод проверки владения (используется только в логике onPurchaseClicked)
    public boolean isItemOwned(String itemId) {
        return ownedItemsIds.contains(itemId);
    }

    // =================================================================
    // ОБРАБОТКА ПОКУПКИ
    // =================================================================

    @Override
    public void onPurchaseClicked(ShopItem item) {
        QuizApplication.getInstance().playClickSound();

        // 0. ПРОВЕРКА: Если уже куплено, прервать покупку
        if (isItemOwned(item.id)) {
            Toast.makeText(this, item.name + " уже куплен!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentPoints < item.price) {
            Toast.makeText(this, "Недостаточно очков для покупки " + item.name, Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Списание очков
        int newPoints = currentPoints - item.price;
        if (updatePointsInDatabase(newPoints)) {
            currentPoints = newPoints;
            tvPlayerPoints.setText(getString(R.string.current_points, currentPoints));

            // 2. Добавление товара в инвентарь (или специальную таблицу)
            if (addItemToInventory(item)) {

                // КЛЮЧЕВОЙ ШАГ: Обновляем список купленных предметов в памяти
                ownedItemsIds.add(item.id);

                Toast.makeText(this, item.name + " успешно куплен!", Toast.LENGTH_LONG).show();

                // 3. Обновляем адаптер, чтобы кнопка изменила состояние на "Куплено"
                adapter.notifyDataSetChanged();
            } else {
                // Если покупка не добавлена в инвентарь, возвращаем очки (транзакция отменена)
                updatePointsInDatabase(currentPoints + item.price);
                Toast.makeText(this, "Ошибка записи в инвентарь.", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Ошибка списания очков.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean updatePointsInDatabase(int newPoints) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(QuizDatabaseHelper.STATS_COLUMN_POINTS, newPoints);

        int rowsAffected = db.update(QuizDatabaseHelper.TABLE_PLAYER_STATS,
                values,
                QuizDatabaseHelper.STATS_COLUMN_ID + "=1",
                null);
        return rowsAffected > 0;
    }

    private boolean addItemToInventory(ShopItem item) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("item_id", item.id);
        values.put("item_name", item.name);
        values.put("is_equipped", 0);

        // ВАЖНО: Требуется, чтобы таблица 'inventory' была создана в QuizDatabaseHelper
        long newRowId = db.insertWithOnConflict("inventory", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        return newRowId != -1;
    }
}