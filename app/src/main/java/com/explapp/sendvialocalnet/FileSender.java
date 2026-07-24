package com.explapp.sendvialocalnet;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class FileSender {
    private static final int PORT = 5051;
    private static final int BUFFER_SIZE = 64 * 1024;

    interface Listener {
        void onProgress(int completed, int total, int succeeded, int failed);
        void onLog(String message);
        void onDone(int succeeded, int failed);
    }

    private static class FileInfo {
        String name;
        long size;
        Uri uri;
        File temporary;
    }

    private final Context context;
    private final ContentResolver resolver;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    FileSender(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = context.getContentResolver();
    }

    void send(List<DeviceRecord> targets, List<Uri> files, final Listener listener) {
        final int total = targets.size() * files.size();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();

        for (final DeviceRecord device : targets) {
            for (final Uri uri : files) {
                pool.submit(new Runnable() {
                    @Override public void run() {
                        boolean ok = sendOne(device, uri, listener);
                        if (ok) succeeded.incrementAndGet(); else failed.incrementAndGet();
                        int done = completed.incrementAndGet();
                        listener.onProgress(done, total, succeeded.get(), failed.get());
                        if (done == total) listener.onDone(succeeded.get(), failed.get());
                    }
                });
            }
        }
    }

    String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String value = uri.getLastPathSegment();
        return value == null ? "file_" + System.currentTimeMillis() : value;
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private boolean sendOne(DeviceRecord device, Uri uri, Listener listener) {
        FileInfo info = null;
        HttpURLConnection connection = null;
        try {
            info = prepare(uri);
            listener.onLog("جاري إرسال " + info.name + " إلى " + device.name);
            connection = (HttpURLConnection)new URL("http://" + device.ip + ":" + PORT + "/upload").openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-File-Name", URLEncoder.encode(info.name, "UTF-8"));
            connection.setRequestProperty("X-File-Size", String.valueOf(info.size));
            if (info.size <= Integer.MAX_VALUE) connection.setFixedLengthStreamingMode((int)info.size);
            else if (Build.VERSION.SDK_INT >= 19) connection.setFixedLengthStreamingMode(info.size);
            else throw new Exception("حجم الملف أكبر من الحد المدعوم");

            InputStream input = info.temporary != null ? new FileInputStream(info.temporary) : resolver.openInputStream(info.uri);
            OutputStream output = new BufferedOutputStream(connection.getOutputStream());
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while (input != null && (count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            if (input != null) input.close();
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            listener.onLog((ok ? "تم إرسال " : "فشل إرسال ") + info.name + " إلى " + device.name);
            return ok;
        } catch (Exception error) {
            listener.onLog("فشل الإرسال إلى " + device.name + ": " + message(error));
            return false;
        } finally {
            if (connection != null) connection.disconnect();
            if (info != null && info.temporary != null) info.temporary.delete();
        }
    }

    private FileInfo prepare(Uri uri) throws Exception {
        FileInfo info = new FileInfo();
        info.uri = uri;
        info.name = displayName(uri);
        info.size = size(uri);
        if (info.size >= 0) return info;

        File temporary = new File(context.getCacheDir(), "send_" + System.nanoTime() + ".tmp");
        InputStream input = resolver.openInputStream(uri);
        FileOutputStream output = new FileOutputStream(temporary);
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while (input != null && (count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        if (input != null) input.close();
        output.flush();
        output.close();
        info.temporary = temporary;
        info.size = temporary.length();
        return info;
    }

    private long size(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    private String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
