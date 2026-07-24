package com.explapp.sendvialocalnet;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TransferReceiver {
    static final int PORT = 5051;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int SOCKET_TIMEOUT = 60000;

    interface Listener {
        void onState(boolean running, String message);
        void onProgress(int percent);
        void onReceived(File file);
        void onLog(String message);
    }

    private final Context context;
    private final LocalDiscovery.NameProvider nameProvider;
    private final Listener listener;
    private final ExecutorService clients = Executors.newFixedThreadPool(3);
    private volatile boolean running;
    private ServerSocket serverSocket;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    TransferReceiver(Context context, LocalDiscovery.NameProvider nameProvider, Listener listener) {
        this.context = context.getApplicationContext();
        this.nameProvider = nameProvider;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) {
            listener.onState(true, "● الاستقبال يعمل تلقائيًا");
            return;
        }
        String ip = LocalDiscovery.getBestLocalIp();
        if (!LocalDiscovery.isIpv4(ip)) {
            listener.onState(false, "○ اتصل بشبكة Wi‑Fi");
            return;
        }
        running = true;
        acquireLocks();
        listener.onState(true, "● الاستقبال يعمل تلقائيًا");
        final String address = ip;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ServerSocket socket = new ServerSocket();
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress(PORT));
                    serverSocket = socket;
                    listener.onLog("تم تشغيل الاستقبال على " + address + ":" + PORT);
                    while (running) {
                        final Socket client = socket.accept();
                        client.setSoTimeout(SOCKET_TIMEOUT);
                        clients.submit(new Runnable() {
                            @Override public void run() { handle(client); }
                        });
                    }
                } catch (Exception error) {
                    if (running) listener.onLog("خطأ في الاستقبال: " + message(error));
                } finally {
                    running = false;
                    closeSocket();
                    releaseLocks();
                    listener.onState(false, "○ الاستقبال متوقف");
                }
            }
        }, "svln-simple-receiver").start();
    }

    synchronized void stop() {
        running = false;
        closeSocket();
        releaseLocks();
        listener.onState(false, "○ الاستقبال متوقف");
    }

    boolean isRunning() {
        return running;
    }

    void shutdown() {
        stop();
        clients.shutdownNow();
    }

    private void handle(Socket socket) {
        File target = null;
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String header = readHeader(input);
            if (header.startsWith("OPTIONS")) {
                writeResponse(socket, "200 OK", "OK");
                return;
            }
            if (!header.startsWith("POST")) {
                String name = nameProvider.getDeviceName();
                writeResponse(socket, "200 OK", "SVLN|" + clean(name) + "|android");
                return;
            }

            long length = contentLength(header);
            if (length < 0) throw new Exception("لم يتم تحديد حجم الملف");
            String filename = headerValue(header, "X-File-Name");
            if (filename == null || filename.length() == 0) filename = "received_" + System.currentTimeMillis() + ".bin";
            try { filename = URLDecoder.decode(filename, "UTF-8"); } catch (Exception ignored) {}

            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SendViaLocalNet");
            if (!directory.exists() && !directory.mkdirs()) throw new Exception("تعذر إنشاء مجلد التنزيل");
            target = uniqueFile(directory, safeFilename(filename));
            stream(input, target, length);
            writeResponse(socket, "200 OK", "OK");
            listener.onReceived(target);
            listener.onLog("تم استقبال " + target.getName());
        } catch (Exception error) {
            if (target != null && target.exists()) target.delete();
            try { writeResponse(socket, "500 ERROR", message(error)); } catch (Exception ignored) {}
            listener.onLog("فشل الاستقبال: " + message(error));
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private String readHeader(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] end = new byte[]{13, 10, 13, 10};
        int matched = 0;
        int value;
        while ((value = input.read()) != -1) {
            output.write(value);
            if (value == end[matched]) {
                matched++;
                if (matched == 4) break;
            } else {
                matched = value == 13 ? 1 : 0;
            }
            if (output.size() > 65536) throw new Exception("رأس الطلب كبير جدًا");
        }
        return new String(output.toByteArray(), "ISO-8859-1");
    }

    private void stream(InputStream input, File target, long total) throws Exception {
        FileOutputStream output = new FileOutputStream(target);
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = total;
        long received = 0;
        long lastUpdate = 0;
        try {
            while (remaining > 0) {
                int wanted = (int)Math.min(buffer.length, remaining);
                int count = input.read(buffer, 0, wanted);
                if (count < 0) throw new Exception("انقطع الاتصال قبل اكتمال الملف");
                output.write(buffer, 0, count);
                received += count;
                remaining -= count;
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 1500) {
                    listener.onProgress(total == 0 ? 100 : (int)(received * 100L / total));
                    lastUpdate = now;
                }
            }
            output.flush();
        } finally {
            output.close();
        }
    }

    private long contentLength(String header) {
        String value = headerValue(header, "Content-Length");
        try { return value == null ? -1 : Long.parseLong(value); } catch (Exception error) { return -1; }
    }

    private String headerValue(String header, String name) {
        String target = name.toLowerCase(Locale.US) + ":";
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase(Locale.US).startsWith(target)) return line.substring(name.length() + 1).trim();
        }
        return null;
    }

    private void writeResponse(Socket socket, String status, String body) throws Exception {
        OutputStream output = socket.getOutputStream();
        byte[] data = body.getBytes("UTF-8");
        String headers = "HTTP/1.1 " + status + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, OPTIONS, GET\r\n" +
                "Access-Control-Allow-Headers: Content-Type, X-File-Name, X-File-Size\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes("UTF-8"));
        output.write(data);
        output.flush();
    }

    private File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        while (file.exists()) file = new File(directory, base + " (" + index++ + ")" + extension);
        return file;
    }

    private String safeFilename(String value) {
        String name = value == null || value.trim().length() == 0 ? "received_" + System.currentTimeMillis() + ".bin" : value;
        return name.replace("\\", "_").replace("/", "_").replace(":", "_").replace("*", "_")
                .replace("?", "_").replace("\"", "_").replace("<", "_").replace(">", "_").replace("|", "_");
    }

    private String clean(String value) {
        if (value == null) return "Android";
        return value.replace("|", " ").replace("\r", " ").replace("\n", " ").trim();
    }

    private String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void closeSocket() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
    }

    private void acquireLocks() {
        try {
            PowerManager manager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if (manager != null && wakeLock == null) {
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SendViaLocalNet:SimpleReceiver");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
        try {
            WifiManager manager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            if (manager != null && wifiLock == null) {
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL, "SendViaLocalNetSimpleWifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        wifiLock = null;
    }
}
