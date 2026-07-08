package com.explapp.sendvialocalnet;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 77;
    private static final int PORT = 5051;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 64 * 1024;

    private TextView logView;
    private TextView phoneUrlView;
    private EditText ipBox;
    private volatile boolean serverRunning = false;
    private ServerSocket serverSocket;
    private String currentPhoneIp = "0.0.0.0";
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestStoragePermission();
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("إرسال محلي عبر الشبكة V5");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        phoneUrlView = new TextView(this);
        phoneUrlView.setTextSize(17);
        phoneUrlView.setPadding(0, 18, 0, 18);
        root.addView(phoneUrlView);
        refreshIpText(false);

        ipBox = new EditText(this);
        ipBox.setHint("IP الكمبيوتر مثال: 192.168.1.20");
        ipBox.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(ipBox);

        Button refresh = new Button(this);
        refresh.setText("تحديث عنوان الهاتف IP");
        root.addView(refresh);
        refresh.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { refreshIpText(true); } });

        Button copy = new Button(this);
        copy.setText("نسخ عنوان الهاتف");
        root.addView(copy);
        copy.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copyPhoneUrl(); } });

        Button send = new Button(this);
        send.setText("اختيار ملف وإرساله للكمبيوتر");
        root.addView(send);
        send.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickFile(); } });

        Button start = new Button(this);
        start.setText("تشغيل استقبال الملفات على الهاتف");
        root.addView(start);
        start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startServer(); } });

        Button stop = new Button(this);
        stop.setText("إيقاف الاستقبال");
        root.addView(stop);
        stop.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stopServer(); } });

        logView = new TextView(this);
        logView.setTextSize(14);
        logView.setPadding(0, 20, 0, 0);
        root.addView(logView);
        setContentView(scroll);
        log("جاهز. V5 تستقبل الملفات الكبيرة بطريقة Streaming بدون تحميل الملف كاملًا في الذاكرة.");
    }

    private void refreshIpText(boolean showToast) {
        currentPhoneIp = getBestLocalIp();
        String url = "http://" + currentPhoneIp + ":" + PORT + "/upload";
        if ("0.0.0.0".equals(currentPhoneIp)) {
            phoneUrlView.setText("لم يتم العثور على IP حقيقي للهاتف.\nتأكد أن Wi‑Fi يعمل وأن الكمبيوتر والهاتف على نفس الشبكة.\nالعنوان الحالي غير صالح: " + url);
        } else {
            phoneUrlView.setText("عنوان استقبال الهاتف:\n" + url + "\nاستخدم أداة ويندوز V5 لإرسال الملفات الكبيرة.");
        }
        if (showToast) toast("تم تحديث IP: " + currentPhoneIp);
    }

    private void copyPhoneUrl() {
        refreshIpText(false);
        String text = "http://" + currentPhoneIp + ":" + PORT + "/upload";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("SendViaLocalNet", text));
            toast("تم نسخ العنوان");
        } catch (Exception e) { toast(text); }
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "اختر ملف"), PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            final String ip = ipBox.getText().toString().trim();
            if (ip.length() < 7) { toast("اكتب IP الكمبيوتر أولاً"); return; }
            new Thread(new Runnable() { @Override public void run() { sendFile(ip, uri); } }).start();
        }
    }

    private void sendFile(String ip, Uri uri) {
        try {
            log("جاري الإرسال إلى الكمبيوتر " + ip + " ...");
            String boundary = "----LocalNetBoundary" + System.currentTimeMillis();
            URL url = new URL("http://" + ip + ":5050/upload");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            OutputStream out = new BufferedOutputStream(c.getOutputStream());
            String name = "android-file-" + System.currentTimeMillis();
            out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n").getBytes("UTF-8"));
            out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes("UTF-8"));
            InputStream in = getContentResolver().openInputStream(uri);
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) != -1) out.write(buf, 0, n);
            if (in != null) in.close();
            out.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
            out.flush(); out.close();
            int code = c.getResponseCode();
            log(code >= 200 && code < 300 ? "تم الإرسال للكمبيوتر بنجاح." : "فشل الإرسال، الكود: " + code);
        } catch (Exception e) { log("فشل إرسال الهاتف للكمبيوتر بدون تعليق: " + e.getMessage()); }
    }

    private synchronized void startServer() {
        if (serverRunning) { log("الاستقبال يعمل بالفعل. لا تضغط تشغيل مرة ثانية."); return; }
        refreshIpText(false);
        if ("0.0.0.0".equals(currentPhoneIp)) {
            log("لا يوجد IP حقيقي. اتصل بالواي فاي ثم اضغط تحديث IP.");
            toast("لا يوجد IP حقيقي للهاتف");
            return;
        }
        serverRunning = true;
        acquireLocks();
        final String url = "http://" + currentPhoneIp + ":" + PORT + "/upload";
        phoneUrlView.setText("استقبال الهاتف يعمل في الخلفية على:\n" + url + "\nV5 يستقبل الملفات الكبيرة دون استهلاك الذاكرة.");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(PORT));
                    serverSocket = ss;
                    log("تم تشغيل استقبال V5: " + url);
                    while (serverRunning) {
                        final Socket s = serverSocket.accept();
                        s.setSoTimeout(SOCKET_TIMEOUT_MS);
                        new Thread(new Runnable() { @Override public void run() { handleClient(s); } }).start();
                    }
                } catch (Exception e) {
                    if (serverRunning) log("خطأ في الاستقبال: " + e.getMessage());
                    serverRunning = false;
                    closeServerSocket();
                    releaseLocks();
                }
            }
        }).start();
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String header = readHeader(in);
            if (header.startsWith("OPTIONS")) { writeResponse(socket, "200 OK", "OK"); socket.close(); return; }
            if (!header.startsWith("POST")) { writeResponse(socket, "200 OK", "SendViaLocalNet READY " + currentPhoneIp); socket.close(); return; }

            int contentLength = getContentLength(header);
            if (contentLength <= 0) throw new Exception("لم يتم معرفة حجم الملف");
            String fileName = getHeaderValue(header, "X-File-Name");
            if (fileName == null || fileName.length() == 0) fileName = "pc_file_" + System.currentTimeMillis() + ".bin";
            try { fileName = URLDecoder.decode(fileName, "UTF-8"); } catch (Exception ignored) {}

            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SendViaLocalNet");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, safeFileName(fileName));
            streamToFile(in, f, contentLength);

            writeResponse(socket, "200 OK", "OK");
            socket.close();
            log("تم استقبال ملف: " + f.getName() + "\nالحفظ: Download/SendViaLocalNet");
        } catch (Exception e) {
            try { writeResponse(socket, "500 ERROR", e.getMessage()); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
            log("فشل استقبال ملف بدون تعليق: " + e.getMessage());
        }
    }

    private String readHeader(InputStream in) throws Exception {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        byte[] end = new byte[]{13, 10, 13, 10};
        while ((b = in.read()) != -1) {
            headerBytes.write(b);
            if (b == end[matched]) {
                matched++;
                if (matched == 4) break;
            } else {
                matched = (b == 13) ? 1 : 0;
            }
            if (headerBytes.size() > 65536) throw new Exception("رأس الطلب كبير جدًا");
        }
        return new String(headerBytes.toByteArray(), "ISO-8859-1");
    }

    private void streamToFile(InputStream in, File f, int total) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        byte[] buf = new byte[BUFFER_SIZE];
        int remaining = total;
        int received = 0;
        long lastLog = System.currentTimeMillis();
        try {
            while (remaining > 0) {
                int want = Math.min(buf.length, remaining);
                int n = in.read(buf, 0, want);
                if (n == -1) throw new Exception("انقطع الإرسال قبل اكتمال الملف");
                fos.write(buf, 0, n);
                remaining -= n;
                received += n;
                long now = System.currentTimeMillis();
                if (now - lastLog > 3000) {
                    final int pct = total > 0 ? (int)((received * 100L) / total) : 0;
                    log("استقبال الملف... " + pct + "%");
                    lastLog = now;
                }
            }
            fos.flush();
        } finally { try { fos.close(); } catch (Exception ignored) {} }
    }

    private int getContentLength(String header) {
        String[] lines = header.split("\r\n");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("content-length:")) {
                try { return Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
            }
        }
        return -1;
    }

    private String getHeaderValue(String header, String name) {
        String[] lines = header.split("\r\n");
        String target = name.toLowerCase(Locale.US) + ":";
        for (String line : lines) {
            if (line.toLowerCase(Locale.US).startsWith(target)) return line.substring(name.length() + 1).trim();
        }
        return null;
    }

    private String safeFileName(String name) {
        if (name == null || name.trim().length() == 0) name = "pc_file_" + System.currentTimeMillis() + ".bin";
        name = name.replace("\\", "_").replace("/", "_").replace(":", "_").replace("*", "_").replace("?", "_").replace("\"", "_").replace("<", "_").replace(">", "_").replace("|", "_");
        return System.currentTimeMillis() + "_" + name;
    }

    private void writeResponse(Socket s, String status, String body) throws Exception {
        OutputStream out = s.getOutputStream();
        byte[] b = body.getBytes("UTF-8");
        out.write(("HTTP/1.1 " + status + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, OPTIONS, GET\r\n" +
                "Access-Control-Allow-Headers: Content-Type, X-File-Name, X-File-Size\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + b.length + "\r\n" +
                "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b); out.flush();
    }

    private synchronized void stopServer() {
        serverRunning = false;
        closeServerSocket();
        releaseLocks();
        log("تم إيقاف الاستقبال وتحرير المنفذ 5051.");
    }

    private void closeServerSocket() { try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {} serverSocket = null; }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SendViaLocalNet:ReceiverWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) { log("تعذر تشغيل WakeLock: " + e.getMessage()); }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "SendViaLocalNetWifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) { log("تعذر تشغيل WifiLock: " + e.getMessage()); }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        wakeLock = null; wifiLock = null;
    }

    @Override protected void onDestroy() { stopServer(); super.onDestroy(); }

    private String getBestLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                for (InetAddress addr : Collections.list(addresses)) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.") && !"0.0.0.0".equals(ip)) return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 5);
            }
        }
    }

    private void log(final String s) { runOnUiThread(new Runnable() { @Override public void run() { if (logView != null) logView.setText(s + "\n" + logView.getText()); }}); }
    private void toast(final String s) { runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); }}); }
}
