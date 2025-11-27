package com.example.quizapp.p2p;

import android.content.Context;
import android.util.Log;

import com.example.quizapp.p2p.P2PManager;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class ChatClient implements Runnable {
    private static final String TAG = "P2P_Client";
    private static final int PORT = 8888;

    private final P2PManager.ConnectionListener listener;
    private final InetAddress hostAddress;
    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;

    public ChatClient(P2PManager.ConnectionListener listener, Context context, InetAddress hostAddress) {
        this.listener = listener;
        this.hostAddress = hostAddress;
    }

    @Override
    public void run() {
        socket = new Socket();
        try {
            // Подключение к владельцу группы (Group Owner)
            socket.bind(null);
            socket.connect(new InetSocketAddress(hostAddress.getHostAddress(), PORT), 5000); // Таймаут 5с
            Log.d(TAG, "Клиент подключен к серверу.");

            // Инициализация потоков для обмена данными
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            inputStream = new ObjectInputStream(socket.getInputStream());

            readDataLoop(); // Начинаем цикл чтения данных

        } catch (IOException e) {
            Log.e(TAG, "Ошибка подключения клиента к серверу: " + e.getMessage());
            listener.onConnectionFailed("Не удалось подключиться к владельцу группы.");
            cancel();
        }
    }

    private void readDataLoop() {
        try {
            Object receivedObject;
            while (true) {
                // Блокирующий вызов: ожидание объекта
                receivedObject = inputStream.readObject();
                if (receivedObject != null) {
                    listener.onDataReceived((Serializable) receivedObject);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            Log.e(TAG, "Разрыв соединения с сервером: " + e.getMessage());
            listener.onDisconnected("Соединение с противником потеряно.");
            cancel();
        }
    }

    public void write(Serializable data) {
        try {
            if (outputStream != null) {
                outputStream.writeObject(data);
                outputStream.flush();
                Log.d(TAG, "Клиент отправил данные: " + data.getClass().getSimpleName());
            }
        } catch (IOException e) {
            Log.e(TAG, "Ошибка отправки данных серверу: " + e.getMessage());
            // Возможно, соединение уже разорвано
        }
    }

    public void cancel() {
        try {
            if (outputStream != null) outputStream.close();
            if (inputStream != null) inputStream.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка закрытия сокетов клиента: " + e.getMessage());
        }
    }
}