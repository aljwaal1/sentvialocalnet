package com.explapp.sendvialocalnet;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

final class TransferReceiver {
    static final int PORT = 5051;
    private static final int BUFFER_SIZE = 256 * 1024;
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
    private final ExecutorService clients = Executors.newFixedThreadPool(6);
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
                        client.setReceiveBufferSize(1024 * 1024);
                        client.setTcpNoDelay(true);
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
            String requestLine = header.split("\r\n", 2)[0];
            String[] requestParts = requestLine.split(" ");
            String method = requestParts.length > 0 ? requestParts[0].toUpperCase(Locale.US) : "";
            String requestPath = requestParts.length > 1 ? requestParts[1] : "/";

            if ("OPTIONS".equals(method)) {
                writeResponse(socket, "200 OK", "OK");
                return;
            }
            if ("GET".equals(method) && requestPath.startsWith("/api/resume-status")) {
                handleResumeStatus(socket, requestPath);
                return;
            }
            if (!"POST".equals(method)) {
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
            String relative = headerValue(header, "X-Relative-Path");
            String entryType = headerValue(header, "X-Entry-Type");
            if (entryType != null && "directory".equalsIgnoreCase(entryType.trim())) {
                if (relative != null && relative.length() > 0) {
                    try { relative = URLDecoder.decode(relative, "UTF-8"); } catch (Exception ignored) {}
                    String[] dirParts = relative.replace("\\", "/").split("/");
                    File targetDir = directory;
                    for (String raw : dirParts) {
                        String part = exactComponent(raw);
                        if (part.length() == 0 || ".".equals(part) || "..".equals(part)) continue;
                        targetDir = new File(targetDir, part);
                    }
                    if (!targetDir.exists() && !targetDir.mkdirs()) throw new Exception("تعذر إنشاء المجلد");
                    writeResponse(socket, "200 OK", "OK");
                    listener.onLog("تم إنشاء المجلد " + targetDir.getPath());
                    return;
                }
                writeResponse(socket, "200 OK", "OK");
                return;
            }
            if (relative != null && relative.length() > 0) {
                try { relative = URLDecoder.decode(relative, "UTF-8"); } catch (Exception ignored) {}
                String[] parts = relative.replace("\\", "/").split("/");
                if (parts.length > 1) {
                    for (int i = 0; i < parts.length - 1; i++) {
                        String part = exactComponent(parts[i]);
                        if (part.length() > 0 && !".".equals(part) && !"..".equals(part)) directory = new File(directory, part);
                    }
                    if (!directory.exists() && !directory.mkdirs()) throw new Exception("تعذر إنشاء بنية المجلد");
                    filename = exactComponent(parts[parts.length - 1]);
                }
            }
            filename = exactComponent(filename);
            target = new File(directory, filename);
            String conflict = headerValue(header, "X-Conflict-Policy");
            if (conflict == null || conflict.length() == 0) conflict = "skip";

            long totalSize = length;
            String totalHeader = headerValue(header, "X-File-Size");
            try { if (totalHeader != null) totalSize = Long.parseLong(totalHeader); } catch (Exception ignored) {}

            String offsetHeader = headerValue(header, "X-Transfer-Offset");
            boolean resumable = offsetHeader != null;
            long offset = 0L;
            try { if (offsetHeader != null) offset = Long.parseLong(offsetHeader); } catch (Exception ignored) {}

            if (target.exists()) {
                if ("skip".equalsIgnoreCase(conflict)) {
                    drain(input, length);
                    writeResponse(socket, "200 OK", "SKIPPED");
                    listener.onLog("تم تخطي " + target.getName() + " لأنه موجود مسبقًا");
                    return;
                }
                if ("cancel".equalsIgnoreCase(conflict)) {
                    writeResponse(socket, "409 Conflict", "EXISTS");
                    return;
                }
            }

            File temp = new File(directory, filename + ".svln.part");
            long current = temp.exists() ? temp.length() : 0L;
            if (resumable && current != offset) {
                drain(input, length);
                writeResponse(socket, "409 Conflict", "OFFSET_MISMATCH:" + current);
                return;
            }

            stream(input, temp, length, resumable);
            long received = temp.length();

            if (totalSize > 0 && received < totalSize) {
                writeResponse(socket, "200 OK", "PARTIAL:" + received);
                listener.onLog("تم حفظ جزء " + received + " من " + totalSize + " للملف " + filename);
                return;
            }
            if (totalSize > 0 && received > totalSize) {
                writeResponse(socket, "409 Conflict", "SIZE_MISMATCH:" + received);
                return;
            }

            if (target.exists() && !target.delete()) throw new Exception("تعذر استبدال الملف الموجود");
            if (!temp.renameTo(target)) {
                copyReplace(temp, target);
                temp.delete();
            }
            writeResponse(socket, "200 OK", "OK");
            listener.onReceived(target);
            listener.onLog("تم استقبال " + target.getName());
        } catch (Exception error) {
            // Never delete the destination here: it may be a pre-existing user file.
            // Partial transfers use a separate .svln.part file.
            try { writeResponse(socket, "500 ERROR", message(error)); } catch (Exception ignored) {}
            listener.onLog("فشل الاستقبال: " + message(error));
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void handleResumeStatus(Socket socket, String requestPath) throws Exception {
        String filename = queryValue(requestPath, "filename");
        String relative = queryValue(requestPath, "relative");
        long total = 0L;
        try { total = Long.parseLong(queryValue(requestPath, "size")); } catch (Exception ignored) {}

        if (filename == null) filename = "";
        filename = exactComponent(filename);

        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SendViaLocalNet");
        if (relative != null && relative.length() > 0) {
            String[] parts = relative.replace("\\", "/").split("/");
            if (parts.length > 1) {
                for (int i = 0; i < parts.length - 1; i++) {
                    String part = exactComponent(parts[i]);
                    directory = new File(directory, part);
                }
                filename = exactComponent(parts[parts.length - 1]);
            } else if (parts.length == 1) {
                filename = exactComponent(parts[0]);
            }
        }

        File target = new File(directory, filename);
        File part = new File(directory, filename + ".svln.part");
        boolean completed = target.exists() && (total <= 0L || target.length() == total);
        long offset = completed ? 0L : (part.exists() ? part.length() : 0L);
        if (total > 0L && offset > total) {
            part.delete();
            offset = 0L;
        }

        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("offset", offset);
        json.put("completed", completed);
        json.put("filename", filename);
        writeJsonResponse(socket, "200 OK", json.toString());
    }

    private String queryValue(String requestPath, String key) {
        try {
            URI uri = new URI(requestPath);
            String query = uri.getRawQuery();
            if (query == null) return "";
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
                if (key.equals(URLDecoder.decode(rawKey, "UTF-8"))) {
                    String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
                    return URLDecoder.decode(rawValue, "UTF-8");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void writeJsonResponse(Socket socket, String status, String body) throws Exception {
        OutputStream output = socket.getOutputStream();
        byte[] data = body.getBytes("UTF-8");
        String headers = "HTTP/1.1 " + status + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes("UTF-8"));
        output.write(data);
        output.flush();
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
        stream(input, target, total, false);
    }

    private void stream(InputStream input, File target, long total, boolean append) throws Exception {
        FileOutputStream output = new FileOutputStream(target, append);
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
                "Access-Control-Allow-Headers: Content-Type, X-File-Name, X-File-Size, X-Relative-Path, X-Entry-Type, X-Conflict-Policy, X-Transfer-Offset\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes("UTF-8"));
        output.write(data);
        output.flush();
    }

    private String exactComponent(String value) throws Exception {
        if (value == null || value.length() == 0 || ".".equals(value) || "..".equals(value)) {
            throw new Exception("اسم ملف/مجلد غير صالح");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new Exception("اسم غير صالح: " + value);
        }
        return value;
    }

    private void drain(InputStream input, long length) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if (count < 0) break;
            remaining -= count;
        }
    }

    private void copyReplace(File source, File target) throws Exception {
        FileInputStream in = new FileInputStream(source);
        FileOutputStream out = new FileOutputStream(target, false);
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        try {
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
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
