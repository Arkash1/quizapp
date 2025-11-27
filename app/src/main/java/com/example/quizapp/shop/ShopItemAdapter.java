package com.example.quizapp.shop;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.ContextCompat; // Используем ContextCompat для старых версий Android
import com.example.quizapp.R;

import java.util.List;

public class ShopItemAdapter extends ArrayAdapter<ShopItem> {

    private final ShopPurchaseListener listener;
    private int currentPoints;
    // НОВОЕ ПОЛЕ: Список ID купленных предметов
    private final List<String> ownedItemsIds;

    public interface ShopPurchaseListener {
        void onPurchaseClicked(ShopItem item);
    }

    // ИЗМЕНЕННЫЙ КОНСТРУКТОР: Теперь принимает список купленных ID
    public ShopItemAdapter(Context context, List<ShopItem> items, ShopPurchaseListener listener, int currentPoints, List<String> ownedItemsIds) {
        super(context, 0, items);
        this.listener = listener;
        this.currentPoints = currentPoints;
        this.ownedItemsIds = ownedItemsIds; // Инициализация нового поля
    }

    // Метод для обновления отображения очков игрока (оставлен для совместимости, хотя теперь мы обновляем ownedItemsIds в ShopActivity)
    public void updatePoints(int newPoints) {
        this.currentPoints = newPoints;
        // notifyDataSetChanged() будет вызван в ShopActivity после покупки
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ShopItem item = getItem(position);

        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_shop, parent, false);
        }

        TextView tvName = convertView.findViewById(R.id.tv_item_name);
        TextView tvDescription = convertView.findViewById(R.id.tv_item_description);
        Button btnBuy = convertView.findViewById(R.id.btn_buy);

        // --- КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: ПРОВЕРКА ВЛАДЕНИЯ ---
        // Проверяем, есть ли ID предмета в списке купленных
        boolean isOwned = ownedItemsIds.contains(item.id);

        tvName.setText(item.name);
        tvDescription.setText(item.description);

        if (isOwned) {
            // 1. Товар уже куплен: Серая кнопка, Куплено
            btnBuy.setText("Куплено");
            btnBuy.setEnabled(false);

            // Используем серый цвет для неактивной кнопки
            btnBuy.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.gray_button_color));

            // Сбрасываем слушатель, чтобы исключить случайные нажатия
            btnBuy.setOnClickListener(null);

        } else if (currentPoints >= item.price) {
            // 2. Достаточно очков: Активная кнопка Купить
            btnBuy.setText(String.format("Купить (%d оч.)", item.price));
            btnBuy.setEnabled(true);

            // Используем акцентный цвет для активной кнопки
            btnBuy.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.colorAccent));

            // Устанавливаем слушатель покупки
            btnBuy.setOnClickListener(v -> listener.onPurchaseClicked(item));

        } else {
            // 3. Недостаточно очков: Неактивная кнопка с требованием
            btnBuy.setText(String.format("Нужно %d оч.", item.price));
            btnBuy.setEnabled(false);

            // Используем цвет для индикации нехватки (красный/оранжевый)
            btnBuy.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.colorWrongAnswer));

            // Сбрасываем слушатель
            btnBuy.setOnClickListener(null);
        }

        return convertView;
    }
}