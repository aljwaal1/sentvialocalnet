package com.explapp.sendvialocalnet;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 77;
    private static final int PORT = 5051;
    private static final int SOCKET_TIMEOUT_MS = 12000;
    private TextView logView;
    private TextView phoneUrlView;
    private EditText ipBox;
    private volatile boolean serverRunning = false;
    private ServerSocket serverSocket;
    private String currentPhoneIp = "0.0.0.0";

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
        title.setText("إرسال محلي عبر الشبكة V3");
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
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshIpText(true); }
        });

        Button copy = new Button(this);
        copy.setText("نسخ عنوان الهاتف");
        root.addView(copy);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyPhoneUrl(); }
        });

        Button send = new Button(this);
        send.setText("اختيار ملف وإرساله للكمبيوتر");
        root.addView(send);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });

        Button start = new Button(this);
        start.setText("تشغيل استقبال الملفات على الهاتف");
        root.addView(start);
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startServer(); }
        });

        Button stop = new Button(this);
        stop.setText("إيقاف الاستقبال");
        root.addView(stop);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopServer(); }
        });

        logView = new TextView(this);
        logView.setTextSize(14);
        logView.setPadding(0, 20, 0, 0);
        root.addView(logView);
        setContentView(scroll);
        log("جاهز. النسخة V3 تمنع تعليق التطبيق عند فشل الإرسال أو انقطاع الاتصال.");
    }

    private void refreshIpText(boolean showToast) {
        currentPhoneIp = getBestLocalIp();
        String url = "http://" + currentPhoneIp + ":" + PORT + "/upload";
        if ("0.0.0.0".equals(currentPhoneIp)) {
            phoneUrlView.setText("لم يتم العثور على IP حقيقي للهاتف.\nتأكد أن Wi‑Fi يعمل وأن الكمبيوتر والهاتف على نفس الشبكة.\nالعنوان الحالي غير صالح: " + url);
        } else {
            phoneUrlView.setText("عنوان استقبال الهاتف:\n" + url + "\nاكتب هذا IP في أداة الكمبيوتر أو استخدم البحث التلقائي.");
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
        } catch (Exception e) {
            toast(text);
        }
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
            new Thread(new Runnable() {
                @Override public void run() { sendFile(ip, uri); }
            }).start();
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
            out.flush();
            out.close();
            int code = c.getResponseCode();
            log(code >= 200 && code < 300 ? "تم الإرسال للكمبيوتر بنجاح." : "فشل الإرسال، الكود: " + code);
        } catch (Exception e) {
            log("فشل إرسال الهاتف للكمبيوتر بدون تعليق: " + e.getMessage());
        }
    }

    private void startServer() {
        if (serverRunning) { log("الاستقبال يعمل بالفعل."); return; }
        refreshIpText(false);
        if ("0.0.0.0".equals(currentPhoneIp)) {
            log("لا يوجد IP حقيقي. اتصل بالواي فاي ثم اضغط تحديث IP.");
            toast("لا يوجد IP حقيقي للهاتف");
            return;
        }
        serverRunning = true;
        final String url = "http://" + currentPhoneIp + ":" + PORT + "/upload";
        phoneUrlView.setText("استقبال الهاتف يعمل على:\n" + url + "\nأرسل الملف من أداة الكمبيوتر الآن.");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    log("تم تشغيل استقبال الهاتف: " + url);
                    while (serverRunning) {
                        final Socket s = serverSocket.accept();
                        s.setSoTimeout(SOCKET_TIMEOUT_MS);
                        new Thread(new Runnable() {
                            @Override public void run() { handleClient(s); }
                        }).start();
                    }
                } catch (Exception e) {
                    if (serverRunning) log("خطأ في الاستقبال: " + e.getMessage());
                }
            }
        }).start();
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
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
            String header = new String(headerBytes.toByteArray(), "ISO-8859-1");
            if (header.startsWith("OPTIONS")) {
                writeResponse(socket, "200 OK", "OK");
                socket.close();
                return;
            }
            if (!header.startsWith("POST")) {
                writeResponse(socket, "200 OK", "SendViaLocalNet READY " + currentPhoneIp);
                socket.close();
                return;
            }

            int contentLength = getContentLength(header);
            if (contentLength <= 0) throw new Exception("لم يتم معرفة حجم الملف");
            if (contentLength > 500 * 1024 * 1024) throw new Exception("الملف كبير جدًا لهذه النسخة");
            byte[] body = readExact(in, contentLength);
            String bodyText = new String(body, "ISO-8859-1");
            String boundary = getBoundary(header);
            if (boundary == null) throw new Exception("صيغة الإرسال غير صحيحة");

            int fileHeaderEnd = bodyText.indexOf("\r\n\r\n");
            if (fileHeaderEnd < 0) throw new Exception("لم يتم العثور على بداية الملف");
            String partHeader = bodyText.substring(0, fileHeaderEnd);
            String fileName = extractFileName(partHeader);
            int fileStart = fileHeaderEnd + 4;
            int fileEnd = bodyText.indexOf("\r\n--" + boundary, fileStart);
            if (fileEnd < 0) fileEnd = body.length;

            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SendViaLocalNet");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, safeFileName(fileName));
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(body, fileStart, Math.max(0, fileEnd - fileStart));
            fos.close();

            writeResponse(socket, "200 OK", "OK");
            socket.close();
            log("تم استقبال ملف وحفظه في Download/SendViaLocalNet: " + f.getName());
        } catch (Exception e) {
            try { writeResponse(socket, "500 ERROR", e.getMessage()); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
            log("فشل استقبال ملف بدون تعليق: " + e.getMessage());
        }
    }

    private byte[] readExact(InputStream in, int len) throws Exception {
        byte[] data = new byte[len];
        int off = 0;
        long lastProgress = System.currentTimeMillis();
        while (off < len) {
            int n = in.read(data, off, len - off);
            if (n == -1) break;
            off += n;
            lastProgress = System.currentTimeMillis();
            if (System.currentTimeMillis() - lastProgress > SOCKET_TIMEOUT_MS) throw new Exception("انقطع الإرسال");
        }
        if (off != len) throw new Exception("الإرسال لم يكتمل. تم استلام " + off + " من " + len);
        return data;
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

    private String getBoundary(String header) {
        int bi = header.indexOf("boundary=");
        if (bi < 0) return null;
        String value = header.substring(bi + 9).trim();
        int end = value.indexOf("\r\n");
        if (end >= 0) value = value.substring(0, end).trim();
        if (value.startsWith("\"")) value = value.substring(1);
        if (value.endsWith("\"")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private String extractFileName(String partHeader) {
        int fi = partHeader.indexOf("filename=\"");
        if (fi >= 0) {
            int start = fi + 10;
            int end = partHeader.indexOf("\"", start);
            if (end > start) return partHeader.substring(start, end);
        }
        return "pc_file_" + System.currentTimeMillis() + ".bin";
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
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + b.length + "\r\n" +
                "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private void stopServer() {
        serverRunning = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        log("تم إيقاف الاستقبال.");
    }

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
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 5);
            }
        }
    }

    private void log(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (logView != null) logView.setText(s + "\n" + logView.getText());
        }});
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); }});
    }
}
