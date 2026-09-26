package com.explapp.sendvialocalnet;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;

final class FileSender {
    private static final int PORT = 5051;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final long RESUME_CHUNK_SIZE = 128L * 1024L * 1024L;

    interface Listener {
        void onProgress(int completed, int total, int succeeded, int failed);
        void onLog(String message);
        void onDone(int succeeded, int failed);
    }

    static final class SendItem {
        final Uri uri;
        final String relativePath;
        final boolean directory;

        SendItem(Uri uri, String relativePath) {
            this(uri, relativePath, false);
        }

        SendItem(Uri uri, String relativePath, boolean directory) {
            this.uri = uri;
            this.relativePath = relativePath == null ? "" : relativePath;
            this.directory = directory;
        }
    }

    private static class FileInfo {
        String name;
        long size;
        Uri uri;
        File temporary;
        String relativePath;
    }

    private static class ResumeState {
        long offset;
        boolean completed;
    }

    private final Context context;
    private final ContentResolver resolver;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    FileSender(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = context.getContentResolver();
    }

    void send(List<DeviceRecord> targets, List<Uri> files, final Listener listener) {
        ArrayList<SendItem> items = new ArrayList<SendItem>();
        for (Uri uri : files) items.add(new SendItem(uri, ""));
        sendItems(targets, items, listener);
    }

    void sendItems(List<DeviceRecord> targets, List<SendItem> files, final Listener listener) {
        final int total = targets.size() * files.size();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();

        for (final DeviceRecord device : targets) {
            for (final SendItem item : files) {
                pool.submit(new Runnable() {
                    @Override public void run() {
                        boolean ok = sendOne(device, item, listener);
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

    private boolean sendOne(DeviceRecord device, SendItem item, Listener listener) {
        Uri uri = item.uri;
        FileInfo info = null;
        HttpURLConnection connection = null;
        try {
            if (item.directory) {
                String folderName = item.relativePath;
                int slash = folderName.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < folderName.length()) folderName = folderName.substring(slash + 1);
                if (folderName.length() == 0) folderName = "folder";
                listener.onLog("جاري إرسال المجلد " + item.relativePath + " إلى " + device.name);
                connection = (HttpURLConnection)new URL("http://" + device.ip + ":" + PORT + "/upload").openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/octet-stream");
                connection.setRequestProperty("X-File-Name", URLEncoder.encode(folderName, "UTF-8"));
                connection.setRequestProperty("X-File-Size", "0");
                connection.setRequestProperty("X-Relative-Path", URLEncoder.encode(item.relativePath, "UTF-8"));
                connection.setRequestProperty("X-Entry-Type", "directory");
                connection.setRequestProperty("X-Conflict-Policy", "skip");
                connection.setFixedLengthStreamingMode(0);
                OutputStream empty = connection.getOutputStream();
                empty.close();
                int code = connection.getResponseCode();
                boolean ok = code >= 200 && code < 300;
                listener.onLog((ok ? "تم إرسال المجلد " : "فشل إرسال المجلد ") + item.relativePath + " إلى " + device.name);
                return ok;
            }

            info = prepare(uri);
            listener.onLog("جاري إرسال " + info.name + " إلى " + device.name);

            Boolean resumed = sendResumable(device, item, info, listener);
            if (resumed != null) return resumed.booleanValue();

            connection = (HttpURLConnection)new URL("http://" + device.ip + ":" + PORT + "/upload").openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-File-Name", URLEncoder.encode(info.name, "UTF-8"));
            connection.setRequestProperty("X-File-Size", String.valueOf(info.size));
            connection.setRequestProperty("X-Conflict-Policy", "skip");
            if (item.relativePath != null && item.relativePath.length() > 0) {
                connection.setRequestProperty("X-Relative-Path", URLEncoder.encode(item.relativePath, "UTF-8"));
            }
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

    private ResumeState getResumeState(DeviceRecord device, SendItem item, FileInfo info) {
        HttpURLConnection connection = null;
        try {
            StringBuilder url = new StringBuilder("http://").append(device.ip).append(":").append(PORT)
                    .append("/api/resume-status?filename=").append(URLEncoder.encode(info.name, "UTF-8"))
                    .append("&size=").append(info.size);
            if (item.relativePath != null && item.relativePath.length() > 0) {
                url.append("&relative=").append(URLEncoder.encode(item.relativePath, "UTF-8"));
            }
            connection = (HttpURLConnection)new URL(url.toString()).openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            input.close();
            JSONObject json = new JSONObject(new String(output.toByteArray(), "UTF-8"));
            if (!json.optBoolean("ok", false)) return null;
            ResumeState state = new ResumeState();
            state.offset = Math.max(0L, Math.min(info.size, json.optLong("offset", 0L)));
            state.completed = json.optBoolean("completed", false);
            return state;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private InputStream openInput(FileInfo info) throws Exception {
        InputStream input = info.temporary != null ? new FileInputStream(info.temporary) : resolver.openInputStream(info.uri);
        if (input == null) throw new Exception("تعذر فتح الملف");
        return input;
    }

    private Boolean sendResumable(DeviceRecord device, SendItem item, FileInfo info, Listener listener) {
        ResumeState state = getResumeState(device, item, info);
        if (state == null) return null;
        if (state.completed) {
            listener.onLog(info.name + " موجود كاملًا على " + device.name + " — تم التخطي");
            return Boolean.TRUE;
        }

        long offset = state.offset;
        InputStream input = null;
        try {
            input = openInput(info);
            skipFully(input, offset);

            // If the receiver already has all bytes in .svln.part but has not finalized it yet,
            // send a zero-length resume request once to finalize.
            if (offset >= info.size) {
                HttpURLConnection finalizeConnection = null;
                try {
                    finalizeConnection = (HttpURLConnection)new URL("http://" + device.ip + ":" + PORT + "/upload").openConnection();
                    finalizeConnection.setConnectTimeout(12000);
                    finalizeConnection.setReadTimeout(120000);
                    finalizeConnection.setDoOutput(true);
                    finalizeConnection.setRequestMethod("POST");
                    finalizeConnection.setRequestProperty("Content-Type", "application/octet-stream");
                    finalizeConnection.setRequestProperty("X-File-Name", URLEncoder.encode(info.name, "UTF-8"));
                    finalizeConnection.setRequestProperty("X-File-Size", String.valueOf(info.size));
                    finalizeConnection.setRequestProperty("X-Transfer-Offset", String.valueOf(offset));
                    finalizeConnection.setRequestProperty("X-Conflict-Policy", "skip");
                    if (item.relativePath != null && item.relativePath.length() > 0) {
                        finalizeConnection.setRequestProperty("X-Relative-Path", URLEncoder.encode(item.relativePath, "UTF-8"));
                    }
                    finalizeConnection.setFixedLengthStreamingMode(0);
                    OutputStream empty = finalizeConnection.getOutputStream();
                    empty.close();
                    int code = finalizeConnection.getResponseCode();
                    return Boolean.valueOf(code >= 200 && code < 300);
                } finally {
                    if (finalizeConnection != null) finalizeConnection.disconnect();
                }
            }

            while (offset < info.size) {
                final long sendLength = Math.min(RESUME_CHUNK_SIZE, info.size - offset);
                boolean advanced = false;

                for (int attempt = 0; attempt < 3 && !advanced; attempt++) {
                    HttpURLConnection connection = null;
                    OutputStream output = null;
                    final long chunkStart = offset;
                    try {
                        connection = (HttpURLConnection)new URL("http://" + device.ip + ":" + PORT + "/upload").openConnection();
                        connection.setConnectTimeout(12000);
                        connection.setReadTimeout(180000);
                        connection.setDoOutput(true);
                        connection.setRequestMethod("POST");
                        connection.setRequestProperty("Connection", "keep-alive");
                        connection.setRequestProperty("Content-Type", "application/octet-stream");
                        connection.setRequestProperty("X-File-Name", URLEncoder.encode(info.name, "UTF-8"));
                        connection.setRequestProperty("X-File-Size", String.valueOf(info.size));
                        connection.setRequestProperty("X-Transfer-Offset", String.valueOf(chunkStart));
                        connection.setRequestProperty("X-Conflict-Policy", "skip");
                        if (item.relativePath != null && item.relativePath.length() > 0) {
                            connection.setRequestProperty("X-Relative-Path", URLEncoder.encode(item.relativePath, "UTF-8"));
                        }
                        if (Build.VERSION.SDK_INT >= 19) connection.setFixedLengthStreamingMode(sendLength);
                        else connection.setFixedLengthStreamingMode((int)sendLength);

                        output = new BufferedOutputStream(connection.getOutputStream(), BUFFER_SIZE);
                        byte[] buffer = new byte[BUFFER_SIZE];
                        long remaining = sendLength;
                        while (remaining > 0) {
                            int wanted = (int)Math.min(buffer.length, remaining);
                            int count = input.read(buffer, 0, wanted);
                            if (count < 0) throw new Exception("انتهى الملف قبل اكتمال الجزء");
                            output.write(buffer, 0, count);
                            remaining -= count;
                        }
                        output.flush();
                        output.close();
                        output = null;

                        int code = connection.getResponseCode();
                        if (code >= 200 && code < 300) {
                            // A 2xx response is emitted only after the receiver has persisted this chunk.
                            offset = chunkStart + sendLength;
                            advanced = true;
                            break;
                        }
                    } catch (Exception ignored) {
                        // The source stream may already have advanced. Ask the receiver for the
                        // exact persisted offset and reopen only when recovery is actually needed.
                    } finally {
                        try { if (output != null) output.close(); } catch (Exception ignored) {}
                        if (connection != null) connection.disconnect();
                    }

                    ResumeState refreshed = getResumeState(device, item, info);
                    if (refreshed != null && refreshed.completed) {
                        listener.onLog("تم إرسال " + info.name + " إلى " + device.name);
                        return Boolean.TRUE;
                    }

                    long recoveryOffset = refreshed != null ? refreshed.offset : chunkStart;
                    try { input.close(); } catch (Exception ignored) {}
                    input = openInput(info);
                    skipFully(input, recoveryOffset);
                    offset = recoveryOffset;

                    if (recoveryOffset != chunkStart) {
                        advanced = true;
                    }
                }

                if (!advanced) {
                    listener.onLog("فشل استكمال " + info.name + " إلى " + device.name);
                    return Boolean.FALSE;
                }
            }

            listener.onLog("تم إرسال " + info.name + " إلى " + device.name);
            return Boolean.TRUE;
        } catch (Exception error) {
            listener.onLog("فشل استكمال " + info.name + " إلى " + device.name + ": " + message(error));
            return Boolean.FALSE;
        } finally {
            try { if (input != null) input.close(); } catch (Exception ignored) {}
        }
    }

    private void skipFully(InputStream input, long amount) throws Exception {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) throw new Exception("تعذر الوصول إلى موضع الاستكمال");
            remaining--;
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
