package com.explapp.sendvialocalnet;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 77;
    private static final int PORT = 5051;
    private TextView logView;
    private EditText ipBox;
    private volatile boolean serverRunning = false;
    private ServerSocket serverSocket;

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
        title.setText("إرسال محلي عبر الشبكة");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("يعمل على Android 4.4 فأكثر. اجعل الهاتف والكمبيوتر على نفس شبكة Wi-Fi.\nعنوان هذا الهاتف للاستقبال: http://" + getLocalIp() + ":" + PORT + "/upload");
        info.setTextSize(16);
        info.setPadding(0, 18, 0, 18);
        root.addView(info);

        ipBox = new EditText(this);
        ipBox.setHint("IP الكمبيوتر مثال: 192.168.1.20");
        ipBox.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(ipBox);

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
        log("جاهز للعمل.");
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
            log("جاري الإرسال إلى " + ip + " ...");
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
            log(code >= 200 && code < 300 ? "تم الإرسال بنجاح." : "فشل الإرسال، الكود: " + code);
        } catch (Exception e) {
            log("خطأ في الإرسال: " + e.getMessage());
        }
    }

    private void startServer() {
        if (serverRunning) { log("الاستقبال يعمل بالفعل."); return; }
        serverRunning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    log("تم تشغيل الاستقبال: http://" + getLocalIp() + ":" + PORT + "/upload");
                    while (serverRunning) {
                        Socket s = serverSocket.accept();
                        handleClient(s);
                    }
                } catch (Exception e) {
                    if (serverRunning) log("خطأ في الاستقبال: " + e.getMessage());
                }
            }
        }).start();
    }

    private void handleClient(Socket socket) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            socket.setSoTimeout(5000);
            while ((n = in.read(buf)) != -1) {
                all.write(buf, 0, n);
                if (n < buf.length) break;
            }
            byte[] data = all.toByteArray();
            String raw = new String(data, "ISO-8859-1");
            int bodyStart = raw.indexOf("\r\n\r\n");
            if (!raw.startsWith("POST") || bodyStart < 0) {
                writeResponse(socket, "200 OK", "Send POST /upload");
                socket.close();
                return;
            }
            String header = raw.substring(0, bodyStart);
            String boundary = null;
            int bi = header.indexOf("boundary=");
            if (bi >= 0) boundary = "--" + header.substring(bi + 9).trim();
            int fileHeaderEnd = raw.indexOf("\r\n\r\n", bodyStart + 4);
            if (boundary == null || fileHeaderEnd < 0) throw new Exception("صيغة الملف غير صحيحة");
            int fileStart = fileHeaderEnd + 4;
            int fileEnd = raw.indexOf("\r\n" + boundary, fileStart);
            if (fileEnd < 0) fileEnd = data.length;
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "localnet_" + System.currentTimeMillis() + ".bin");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data, fileStart, Math.max(0, fileEnd - fileStart));
            fos.close();
            writeResponse(socket, "200 OK", "OK");
            socket.close();
            log("تم استقبال ملف وحفظه في Download: " + f.getName());
        } catch (Exception e) {
            try { writeResponse(socket, "500 ERROR", e.getMessage()); socket.close(); } catch (Exception ignored) {}
            log("فشل استقبال ملف: " + e.getMessage());
        }
    }

    private void writeResponse(Socket s, String status, String body) throws Exception {
        OutputStream out = s.getOutputStream();
        byte[] b = body.getBytes("UTF-8");
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private void stopServer() {
        serverRunning = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        log("تم إيقاف الاستقبال.");
    }

    private String getLocalIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            int ip = wi.getIpAddress();
            return String.format(Locale.US, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        } catch (Exception e) { return "0.0.0.0"; }
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
