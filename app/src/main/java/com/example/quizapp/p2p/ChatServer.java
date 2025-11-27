
package com.example.quizapp.p2p;

import android.content.Context;
import android.util.Log;

import com.example.quizapp.p2p.P2PManager;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatServer implements Runnable {
    private static final String TAG = "P2P_Server";
    private static final int PORT = 8888;

    private final P2PManager.ConnectionListener listener;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;

    public ChatServer(P2PManager.ConnectionListener listener, Context context, InetAddress address) {
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(PORT);
            Log.d(TAG, "Сервер запущен. Ожидание клиента...");

            // Блокирующий вызов: ожидание клиента
            clientSocket = serverSocket.accept();
            Log.d(TAG, "Клиент подключен.");

            // Инициализация потоков для обмена данными
            outputStream = new ObjectOutputStream(clientSocket.getOutputStream());
            inputStream = new ObjectInputStream(clientSocket.getInputStream());

            readDataLoop(); // Начинаем цикл чтения данных

        } catch (IOException e) {
            Log.e(TAG, "Ошибка ServerSocket/Accept: " + e.getMessage());
            listener.onConnectionFailed("Ошибка при запуске сервера данных.");
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
                    // Передача данных в главную активность
                    listener.onDataReceived((Serializable) receivedObject);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            Log.e(TAG, "Разрыв соединения с клиентом: " + e.getMessage());
            listener.onDisconnected("Соединение с противником потеряно.");
            cancel();
        }
    }

    public void write(Serializable data) {
        try {
            if (outputStream != null) {
                outputStream.writeObject(data);
                outputStream.flush();
                Log.d(TAG, "Сервер отправил данные: " + data.getClass().getSimpleName());
            }
        } catch (IOException e) {
            Log.e(TAG, "Ошибка отправки данных клиенту: " + e.getMessage());
            // Возможно, соединение уже разорвано
        }
    }

    public void cancel() {
        try {
            if (outputStream != null) outputStream.close();
            if (inputStream != null) inputStream.close();
            if (clientSocket != null) clientSocket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка закрытия сокетов сервера: " + e.getMessage());
        }
    }
}