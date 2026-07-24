package com.explapp.sendvialocalnet;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int PICK_FILES = 77;
    private static final int PORT = 5051;
    private static final int SOCKET_TIMEOUT_MS = 60000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String PREFS = "send_via_local_net";
    private static final String KEY_DEVICES = "devices";
    private static final String KEY_MY_NAME = "my_name";

    private final ArrayList<Device> devices = new ArrayList<Device>();
    private final ArrayList<Uri> selectedFiles = new ArrayList<Uri>();
    private final ExecutorService transferPool = Executors.newFixedThreadPool(3);

    private SharedPreferences prefs;
    private TextView phoneUrlView;
    private TextView serverStateView;
    private TextView selectedFilesView;
    private TextView logView;
    private TextView summaryView;
    private LinearLayout devicesContainer;
    private EditText deviceNameBox;
    private EditText deviceIpBox;
    private EditText myNameBox;
    private ProgressBar progressBar;

    private volatile boolean serverRunning = false;
    private ServerSocket serverSocket;
    private String currentPhoneIp = "0.0.0.0";
    private android.os.PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private static class Device {
        String name;
        String ip;
        boolean selected;

        Device(String name, String ip, boolean selected) {
            this.name = name;
            this.ip = ip;
            this.selected = selected;
        }
    }

    private static class FileInfo {
        String name;
        long size;
        Uri uri;
        File temporaryFile;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadDevices();
        buildUi();
        requestStoragePermission();
        refreshIpText(false);
        startServer();
    }

    private void buildUi() {
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(Color.rgb(38, 45, 94));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(28));
        root.setBackgroundColor(Color.rgb(244, 246, 252));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout hero = verticalCard(Color.rgb(72, 69, 190), 24);
        TextView title = text("نقل محلي Pro", 26, Color.WHITE, true);
        title.setGravity(Gravity.RIGHT);
        hero.addView(title);
        TextView subtitle = text("إرسال واستقبال سريع وآمن داخل شبكة Wi‑Fi", 15, Color.rgb(228, 230, 255), false);
        subtitle.setPadding(0, dp(5), 0, dp(14));
        hero.addView(subtitle);

        myNameBox = input("اسم هذا الجهاز، مثال: هاتف أسامة");
        myNameBox.setText(prefs.getString(KEY_MY_NAME, Build.MODEL == null ? "هاتف Android" : Build.MODEL));
        hero.addView(myNameBox);
        Button saveMyName = button("حفظ اسم الجهاز", Color.rgb(255, 255, 255), Color.rgb(67, 56, 202));
        hero.addView(saveMyName);
        saveMyName.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String value = myNameBox.getText().toString().trim();
                if (value.length() == 0) value = "هاتف Android";
                prefs.edit().putString(KEY_MY_NAME, value).apply();
                toast("تم حفظ اسم الجهاز");
                refreshIpText(false);
            }
        });
        root.addView(hero, marginBottom(12));

        LinearLayout receiveCard = verticalCard(Color.WHITE, 20);
        receiveCard.addView(sectionTitle("الاستقبال", "يعمل تلقائيًا عند فتح التطبيق"));
        serverStateView = text("جاري تشغيل الاستقبال...", 14, Color.rgb(5, 122, 85), true);
        serverStateView.setPadding(dp(12), dp(10), dp(12), dp(10));
        serverStateView.setBackground(rounded(Color.rgb(236, 253, 245), 14));
        receiveCard.addView(serverStateView, marginBottom(8));

        phoneUrlView = text("", 15, Color.rgb(52, 64, 84), false);
        phoneUrlView.setTextIsSelectable(true);
        phoneUrlView.setPadding(dp(4), dp(8), dp(4), dp(10));
        receiveCard.addView(phoneUrlView);

        LinearLayout receiveButtons = horizontalRow();
        Button copy = button("نسخ العنوان", Color.rgb(238, 242, 255), Color.rgb(67, 56, 202));
        Button refresh = button("تحديث IP", Color.rgb(240, 249, 255), Color.rgb(3, 105, 161));
        receiveButtons.addView(copy, weighted(1, 5));
        receiveButtons.addView(refresh, weighted(1, 0));
        receiveCard.addView(receiveButtons);

        LinearLayout serverButtons = horizontalRow();
        Button start = button("تشغيل الاستقبال", Color.rgb(16, 185, 129), Color.WHITE);
        Button stop = button("إيقاف", Color.rgb(254, 242, 242), Color.rgb(185, 28, 28));
        serverButtons.addView(start, weighted(1, 5));
        serverButtons.addView(stop, weighted(1, 0));
        receiveCard.addView(serverButtons);
        root.addView(receiveCard, marginBottom(12));

        copy.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copyPhoneUrl(); } });
        refresh.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { refreshIpText(true); } });
        start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startServer(); } });
        stop.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stopServer(); } });

        LinearLayout devicesCard = verticalCard(Color.WHITE, 20);
        devicesCard.addView(sectionTitle("الأجهزة المحفوظة", "اختر جهازًا واحدًا أو عدة أجهزة"));
        deviceNameBox = input("اسم اختياري، مثال: لابتوب المكتب");
        deviceIpBox = input("عنوان IP، مثال: 192.168.1.20");
        deviceIpBox.setInputType(InputType.TYPE_CLASS_PHONE);
        devicesCard.addView(deviceNameBox);
        devicesCard.addView(deviceIpBox);

        LinearLayout deviceActions = horizontalRow();
        Button addDevice = button("+ حفظ الجهاز", Color.rgb(79, 70, 229), Color.WHITE);
        Button selectAll = button("تحديد الكل", Color.rgb(238, 242, 255), Color.rgb(67, 56, 202));
        deviceActions.addView(addDevice, weighted(1, 5));
        deviceActions.addView(selectAll, weighted(1, 0));
        devicesCard.addView(deviceActions);

        devicesContainer = new LinearLayout(this);
        devicesContainer.setOrientation(LinearLayout.VERTICAL);
        devicesContainer.setPadding(0, dp(10), 0, 0);
        devicesCard.addView(devicesContainer);
        root.addView(devicesCard, marginBottom(12));
        renderDevices();

        addDevice.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { addOrUpdateDevice(); } });
        selectAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean all = devices.size() > 0;
                for (Device d : devices) if (!d.selected) all = false;
                for (Device d : devices) d.selected = !all;
                saveDevices();
                renderDevices();
            }
        });

        LinearLayout sendCard = verticalCard(Color.WHITE, 20);
        sendCard.addView(sectionTitle("إرسال الملفات", "يدعم عدة ملفات وعدة أجهزة"));
        Button pick = button("اختيار الملفات", Color.rgb(79, 70, 229), Color.WHITE);
        sendCard.addView(pick);
        selectedFilesView = text("لم يتم اختيار ملفات بعد", 14, Color.rgb(102, 112, 133), false);
        selectedFilesView.setPadding(dp(4), dp(12), dp(4), dp(12));
        sendCard.addView(selectedFilesView);
        Button send = button("إرسال إلى الأجهزة المحددة", Color.rgb(16, 185, 129), Color.WHITE);
        sendCard.addView(send);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        sendCard.addView(progressBar, marginTop(12));
        summaryView = text("جاهز", 14, Color.rgb(52, 64, 84), true);
        summaryView.setPadding(dp(4), dp(10), dp(4), 0);
        sendCard.addView(summaryView);
        root.addView(sendCard, marginBottom(12));

        pick.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickFiles(); } });
        send.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sendSelectedFiles(); } });

        LinearLayout logCard = verticalCard(Color.WHITE, 20);
        logCard.addView(sectionTitle("سجل النشاط", "آخر العمليات تظهر أولًا"));
        logView = text("", 13, Color.rgb(71, 84, 103), false);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        Button clearLog = button("مسح السجل", Color.rgb(248, 250, 252), Color.rgb(71, 84, 103));
        logCard.addView(clearLog, marginTop(10));
        clearLog.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { logView.setText(""); } });
        root.addView(logCard);

        setContentView(scroll);
        log("تم تشغيل واجهة Pro. الاستقبال سيبدأ تلقائيًا.");
    }

    private LinearLayout sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 19, Color.rgb(16, 24, 40), true);
        TextView s = text(subtitle, 12, Color.rgb(102, 112, 133), false);
        s.setPadding(0, dp(3), 0, dp(12));
        box.addView(t);
        box.addView(s);
        return box;
    }

    private void addOrUpdateDevice() {
        String ip = deviceIpBox.getText().toString().trim();
        String name = deviceNameBox.getText().toString().trim();
        if (!isValidIpv4(ip)) { toast("عنوان IP غير صحيح"); return; }
        if (name.length() == 0) name = "جهاز " + ip;
        Device existing = null;
        for (Device d : devices) if (d.ip.equals(ip)) existing = d;
        if (existing == null) devices.add(0, new Device(name, ip, true));
        else { existing.name = name; existing.selected = true; }
        deviceNameBox.setText("");
        deviceIpBox.setText("");
        saveDevices();
        renderDevices();
        toast("تم حفظ الجهاز");
    }

    private void renderDevices() {
        if (devicesContainer == null) return;
        devicesContainer.removeAllViews();
        if (devices.size() == 0) {
            TextView empty = text("لا توجد أجهزة محفوظة. أضف IP الجهاز المستقبل.", 13, Color.rgb(102, 112, 133), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(18), dp(8), dp(18));
            empty.setBackground(rounded(Color.rgb(248, 250, 252), 14));
            devicesContainer.addView(empty);
            return;
        }
        for (int i = 0; i < devices.size(); i++) {
            final Device d = devices.get(i);
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(8), dp(9));
            row.setBackground(rounded(d.selected ? Color.rgb(238, 242, 255) : Color.rgb(248, 250, 252), 14));

            CheckBox check = new CheckBox(this);
            check.setChecked(d.selected);
            check.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.selected = ((CheckBox)v).isChecked();
                    saveDevices();
                    renderDevices();
                }
            });
            row.addView(check, new LinearLayout.LayoutParams(dp(46), -2));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(d.name, 15, Color.rgb(16, 24, 40), true);
            TextView ip = text(d.ip + ":" + PORT, 12, Color.rgb(102, 112, 133), false);
            info.addView(name);
            info.addView(ip);
            info.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    deviceNameBox.setText(d.name);
                    deviceIpBox.setText(d.ip);
                }
            });
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            Button remove = button("حذف", Color.rgb(254, 242, 242), Color.rgb(185, 28, 28));
            remove.setTextSize(12);
            remove.setPadding(dp(10), dp(7), dp(10), dp(7));
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    devices.remove(index);
                    saveDevices();
                    renderDevices();
                }
            });
            row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
            devicesContainer.addView(row, marginBottom(7));
        }
    }

    private void pickFiles() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 19) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        else intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "اختر الملفات"), PICK_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return;
        Set<String> seen = new LinkedHashSet<String>();
        for (Uri u : selectedFiles) seen.add(u.toString());
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && seen.add(uri.toString())) selectedFiles.add(uri);
            }
        } else if (data.getData() != null && seen.add(data.getData().toString())) selectedFiles.add(data.getData());
        updateSelectedFilesText();
    }

    private void updateSelectedFilesText() {
        if (selectedFiles.size() == 0) { selectedFilesView.setText("لم يتم اختيار ملفات بعد"); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("تم اختيار ").append(selectedFiles.size()).append(" ملف:\n");
        int max = Math.min(selectedFiles.size(), 6);
        for (int i = 0; i < max; i++) sb.append("• ").append(getDisplayName(selectedFiles.get(i))).append("\n");
        if (selectedFiles.size() > max) sb.append("… و").append(selectedFiles.size() - max).append(" ملفات أخرى");
        selectedFilesView.setText(sb.toString().trim());
    }

    private void sendSelectedFiles() {
        final ArrayList<Device> targets = new ArrayList<Device>();
        for (Device d : devices) if (d.selected) targets.add(d);
        if (targets.size() == 0) { toast("حدد جهازًا واحدًا على الأقل"); return; }
        if (selectedFiles.size() == 0) { toast("اختر ملفًا واحدًا على الأقل"); return; }

        final ArrayList<Uri> files = new ArrayList<Uri>(selectedFiles);
        final int total = targets.size() * files.size();
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicInteger succeeded = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        progressBar.setProgress(0);
        summaryView.setText("بدء الإرسال إلى " + targets.size() + " جهاز...");

        for (final Device device : targets) {
            for (final Uri uri : files) {
                transferPool.submit(new Runnable() {
                    @Override public void run() {
                        boolean ok = sendFile(device, uri);
                        if (ok) succeeded.incrementAndGet(); else failed.incrementAndGet();
                        final int done = completed.incrementAndGet();
                        final int percent = (int)((done * 100L) / total);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                progressBar.setProgress(percent);
                                summaryView.setText("اكتمل " + done + " من " + total + " — نجح: " + succeeded.get() + "، فشل: " + failed.get());
                            }
                        });
                    }
                });
            }
        }
    }

    private boolean sendFile(Device device, Uri uri) {
        FileInfo info = null;
        HttpURLConnection connection = null;
        try {
            info = prepareFile(uri);
            log("جاري إرسال " + info.name + " إلى " + device.name + "...");
            URL url = new URL("http://" + device.ip + ":" + PORT + "/upload");
            connection = (HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-File-Name", URLEncoder.encode(info.name, "UTF-8"));
            connection.setRequestProperty("X-File-Size", String.valueOf(info.size));
            if (info.size <= Integer.MAX_VALUE) connection.setFixedLengthStreamingMode((int)info.size);
            else if (Build.VERSION.SDK_INT >= 19) connection.setFixedLengthStreamingMode(info.size);
            else throw new Exception("حجم الملف أكبر من الحد المدعوم في هذا الإصدار");

            InputStream input = info.temporaryFile != null ? new FileInputStream(info.temporaryFile) : getContentResolver().openInputStream(info.uri);
            OutputStream output = new BufferedOutputStream(connection.getOutputStream());
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while (input != null && (n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            if (input != null) input.close();
            output.flush();
            output.close();
            int code = connection.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            log((ok ? "✓ تم إرسال " : "✕ فشل إرسال ") + info.name + " إلى " + device.name + (ok ? "" : " — HTTP " + code));
            return ok;
        } catch (Exception e) {
            log("✕ فشل الإرسال إلى " + device.name + ": " + safeMessage(e));
            return false;
        } finally {
            if (connection != null) connection.disconnect();
            if (info != null && info.temporaryFile != null) info.temporaryFile.delete();
        }
    }

    private FileInfo prepareFile(Uri uri) throws Exception {
        FileInfo info = new FileInfo();
        info.uri = uri;
        info.name = getDisplayName(uri);
        info.size = getFileSize(uri);
        if (info.size >= 0) return info;
        File temp = new File(getCacheDir(), "send_" + System.currentTimeMillis() + ".tmp");
        InputStream in = getContentResolver().openInputStream(uri);
        FileOutputStream out = new FileOutputStream(temp);
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while (in != null && (n = in.read(buf)) != -1) out.write(buf, 0, n);
        if (in != null) in.close();
        out.flush(); out.close();
        info.temporaryFile = temp;
        info.size = temp.length();
        return info;
    }

    private String getDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally { if (cursor != null) cursor.close(); }
        String path = uri.getLastPathSegment();
        return path == null ? "file_" + System.currentTimeMillis() : path;
    }

    private long getFileSize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) {
        } finally { if (cursor != null) cursor.close(); }
        return -1;
    }

    private synchronized void startServer() {
        if (serverRunning) { toast("الاستقبال يعمل بالفعل"); return; }
        refreshIpText(false);
        if ("0.0.0.0".equals(currentPhoneIp)) {
            serverStateView.setText("غير متصل بالشبكة");
            log("تعذر تشغيل الاستقبال: لا يوجد IP محلي صالح.");
            return;
        }
        serverRunning = true;
        acquireLocks();
        serverStateView.setText("● الاستقبال يعمل على المنفذ " + PORT);
        serverStateView.setTextColor(Color.rgb(5, 122, 85));
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ServerSocket socket = new ServerSocket();
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress(PORT));
                    serverSocket = socket;
                    log("✓ تم تشغيل الاستقبال تلقائيًا على " + currentPhoneIp + ":" + PORT);
                    while (serverRunning) {
                        final Socket client = serverSocket.accept();
                        client.setSoTimeout(SOCKET_TIMEOUT_MS);
                        transferPool.submit(new Runnable() { @Override public void run() { handleClient(client); } });
                    }
                } catch (Exception e) {
                    if (serverRunning) log("خطأ في خادم الاستقبال: " + safeMessage(e));
                    serverRunning = false;
                    closeServerSocket();
                    releaseLocks();
                    updateServerStoppedUi();
                }
            }
        }, "svln-receiver").start();
    }

    private void handleClient(Socket socket) {
        File target = null;
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String header = readHeader(input);
            if (header.startsWith("OPTIONS")) { writeResponse(socket, "200 OK", "OK"); return; }
            if (!header.startsWith("POST")) {
                String deviceName = prefs.getString(KEY_MY_NAME, "Android");
                writeResponse(socket, "200 OK", "SVLN|" + deviceName + "|android");
                return;
            }
            long contentLength = getContentLength(header);
            if (contentLength < 0) throw new Exception("لم يتم تحديد حجم الملف");
            String fileName = getHeaderValue(header, "X-File-Name");
            if (fileName == null || fileName.length() == 0) fileName = "received_" + System.currentTimeMillis() + ".bin";
            try { fileName = URLDecoder.decode(fileName, "UTF-8"); } catch (Exception ignored) {}
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SendViaLocalNet");
            if (!directory.exists() && !directory.mkdirs()) throw new Exception("تعذر إنشاء مجلد التنزيل");
            target = uniqueFile(directory, safeFileName(fileName));
            streamToFile(input, target, contentLength);
            writeResponse(socket, "200 OK", "OK");
            log("✓ تم استقبال " + target.getName() + " وحفظه في Download/SendViaLocalNet");
        } catch (Exception e) {
            if (target != null && target.exists()) target.delete();
            try { writeResponse(socket, "500 ERROR", safeMessage(e)); } catch (Exception ignored) {}
            log("✕ فشل الاستقبال: " + safeMessage(e));
        } finally { try { socket.close(); } catch (Exception ignored) {} }
    }

    private String readHeader(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        int value;
        byte[] end = new byte[]{13, 10, 13, 10};
        while ((value = input.read()) != -1) {
            bytes.write(value);
            if (value == end[matched]) { matched++; if (matched == 4) break; }
            else matched = value == 13 ? 1 : 0;
            if (bytes.size() > 65536) throw new Exception("رأس الطلب كبير جدًا");
        }
        return new String(bytes.toByteArray(), "ISO-8859-1");
    }

    private void streamToFile(InputStream input, File target, long total) throws Exception {
        FileOutputStream output = new FileOutputStream(target);
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = total;
        long received = 0;
        long lastUpdate = 0;
        try {
            while (remaining > 0) {
                int wanted = (int)Math.min(buffer.length, remaining);
                int n = input.read(buffer, 0, wanted);
                if (n < 0) throw new Exception("انقطع الاتصال قبل اكتمال الملف");
                output.write(buffer, 0, n);
                received += n;
                remaining -= n;
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 2500) {
                    final int percent = total == 0 ? 100 : (int)((received * 100L) / total);
                    runOnUiThread(new Runnable() { @Override public void run() { summaryView.setText("استقبال ملف... " + percent + "%"); } });
                    lastUpdate = now;
                }
            }
            output.flush();
        } finally { output.close(); }
    }

    private long getContentLength(String header) {
        String value = getHeaderValue(header, "Content-Length");
        try { return value == null ? -1 : Long.parseLong(value); } catch (Exception e) { return -1; }
    }

    private String getHeaderValue(String header, String name) {
        String[] lines = header.split("\r\n");
        String target = name.toLowerCase(Locale.US) + ":";
        for (String line : lines) if (line.toLowerCase(Locale.US).startsWith(target)) return line.substring(name.length() + 1).trim();
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

    private synchronized void stopServer() {
        if (!serverRunning) { updateServerStoppedUi(); return; }
        serverRunning = false;
        closeServerSocket();
        releaseLocks();
        updateServerStoppedUi();
        log("تم إيقاف الاستقبال.");
    }

    private void updateServerStoppedUi() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (serverStateView != null) {
                    serverStateView.setText("○ الاستقبال متوقف");
                    serverStateView.setTextColor(Color.rgb(185, 28, 28));
                }
            }
        });
    }

    private void closeServerSocket() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
    }

    private void refreshIpText(boolean showToast) {
        currentPhoneIp = getBestLocalIp();
        String url = "http://" + currentPhoneIp + ":" + PORT + "/upload";
        if ("0.0.0.0".equals(currentPhoneIp)) phoneUrlView.setText("لم يتم العثور على عنوان IP. تأكد من الاتصال بشبكة Wi‑Fi.");
        else phoneUrlView.setText("عنوان هذا الجهاز:\n" + url + "\nيمكن للكمبيوتر العثور عليه أو حفظ IP باسم اختياري.");
        if (showToast) toast("IP الحالي: " + currentPhoneIp);
    }

    private void copyPhoneUrl() {
        refreshIpText(false);
        String text = "http://" + currentPhoneIp + ":" + PORT + "/upload";
        ClipboardManager manager = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("SendViaLocalNet", text));
        toast("تم نسخ العنوان");
    }

    private void acquireLocks() {
        try {
            android.os.PowerManager manager = (android.os.PowerManager)getSystemService(Context.POWER_SERVICE);
            if (manager != null && wakeLock == null) {
                wakeLock = manager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SendViaLocalNet:Receiver");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) { log("تعذر تثبيت WakeLock: " + safeMessage(e)); }
        try {
            WifiManager manager = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager != null && wifiLock == null) {
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL, "SendViaLocalNetWifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) { log("تعذر تثبيت WiFiLock: " + safeMessage(e)); }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        wifiLock = null;
    }

    private String getBestLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        if (ip != null && !ip.startsWith("127.") && !"0.0.0.0".equals(ip)) return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private void loadDevices() {
        devices.clear();
        String raw = prefs.getString(KEY_DEVICES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                devices.add(new Device(o.optString("name", "جهاز"), o.optString("ip", ""), o.optBoolean("selected", false)));
            }
        } catch (Exception ignored) {}
    }

    private void saveDevices() {
        JSONArray array = new JSONArray();
        try {
            for (Device d : devices) {
                JSONObject o = new JSONObject();
                o.put("name", d.name);
                o.put("ip", d.ip);
                o.put("selected", d.selected);
                array.put(o);
            }
        } catch (Exception ignored) {}
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply();
    }

    private boolean isValidIpv4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try { for (String part : parts) { int n = Integer.parseInt(part); if (n < 0 || n > 255) return false; } }
        catch (Exception e) { return false; }
        return true;
    }

    private File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        while (file.exists()) file = new File(directory, base + " (" + index++ + ")" + ext);
        return file;
    }

    private String safeFileName(String name) {
        if (name == null || name.trim().length() == 0) name = "received_" + System.currentTimeMillis() + ".bin";
        return name.replace("\\", "_").replace("/", "_").replace(":", "_").replace("*", "_").replace("?", "_").replace("\"", "_").replace("<", "_").replace(">", "_").replace("|", "_");
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 5);
        }
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private LinearLayout verticalCard(int color, int radius) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(color, radius));
        return layout;
    }

    private LinearLayout horizontalRow() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setPadding(dp(13), dp(11), dp(13), dp(11));
        input.setTextColor(Color.rgb(16, 24, 40));
        input.setHintTextColor(Color.rgb(152, 162, 179));
        input.setBackground(rounded(Color.WHITE, 13, Color.rgb(208, 213, 221)));
        input.setLayoutParams(marginBottom(9));
        return input;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(rounded(background, 13));
        button.setMinHeight(dp(46));
        button.setLayoutParams(marginBottom(8));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.RIGHT);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private GradientDrawable rounded(int color, int radius) { return rounded(color, radius, 0); }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams marginBottom(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(value));
        return params;
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(value), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weighted(int weight, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weight);
        params.setMargins(0, 0, dp(rightMargin), 0);
        return params;
    }

    private void log(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (logView != null) logView.setText("• " + message + "\n" + logView.getText());
            }
        });
    }

    private void toast(final String message) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show(); } });
    }

    @Override
    protected void onDestroy() {
        stopServer();
        transferPool.shutdownNow();
        super.onDestroy();
    }
}
