package com.explapp.sendvialocalnet;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

final class DeviceScanner {
    private static final int PORT = 5051;

    interface Listener {
        void onDevice(String name, String type, String ip);
        void onFinished(int count);
        void onLog(String message);
    }

    private static class Found {
        String name;
        String type;
        String ip;

        Found(String name, String type, String ip) {
            this.name = name;
            this.type = type;
            this.ip = ip;
        }
    }

    private final LocalDiscovery discovery;
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final AtomicInteger generation = new AtomicInteger();

    DeviceScanner(Context context, LocalDiscovery.NameProvider provider) {
        discovery = new LocalDiscovery(context, provider);
        discovery.startResponder();
    }

    void scan(final Listener listener) {
        final int currentGeneration = generation.incrementAndGet();
        final AtomicInteger sources = new AtomicInteger(2);
        final Set<String> seen = Collections.synchronizedSet(new LinkedHashSet<String>());
        final String ownIp = LocalDiscovery.getBestLocalIp();

        discovery.discover(new LocalDiscovery.Listener() {
            @Override public void onDevice(String name, String type, String ip, int port) {
                if (port == PORT) report(currentGeneration, ownIp, name, type, ip, seen, listener);
            }

            @Override public void onFinished(int responses) {
                sourceFinished(currentGeneration, sources, seen, listener);
            }

            @Override public void onError(String message) {
                listener.onLog("تعذر البحث السريع: " + message);
            }
        });

        worker.execute(new Runnable() {
            @Override public void run() {
                scanSubnet(currentGeneration, ownIp, seen, listener);
                sourceFinished(currentGeneration, sources, seen, listener);
            }
        });
    }

    void shutdown() {
        generation.incrementAndGet();
        discovery.shutdown();
        worker.shutdownNow();
    }

    private void scanSubnet(int currentGeneration, String ownIp, Set<String> seen, Listener listener) {
        if (!LocalDiscovery.isIpv4(ownIp)) return;
        final String subnet = ownIp.substring(0, ownIp.lastIndexOf('.'));
        ExecutorService probes = Executors.newFixedThreadPool(24);
        ArrayList<Future<Found>> futures = new ArrayList<Future<Found>>();
        for (int index = 1; index <= 254; index++) {
            final String host = subnet + "." + index;
            if (host.equals(ownIp)) continue;
            futures.add(probes.submit(new java.util.concurrent.Callable<Found>() {
                @Override public Found call() { return probe(host); }
            }));
        }

        for (Future<Found> future : futures) {
            if (currentGeneration != generation.get()) break;
            try {
                Found found = future.get();
                if (found != null) report(currentGeneration, ownIp, found.name, found.type, found.ip, seen, listener);
            } catch (Exception ignored) {}
        }
        probes.shutdownNow();
    }

    private Found probe(String host) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL("http://" + host + ":" + PORT + "/upload").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(420);
            connection.setReadTimeout(650);
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;

            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while (output.size() < 1024 && (count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            input.close();
            String body = new String(output.toByteArray(), "UTF-8").trim();
            if (!body.startsWith("SVLN|")) return null;
            String[] parts = body.split("\\|", -1);
            String name = parts.length > 1 && parts[1].trim().length() > 0 ? parts[1].trim() : "جهاز " + host;
            String type = parts.length > 2 && parts[2].trim().length() > 0 ? parts[2].trim() : "device";
            return new Found(name, type, host);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void report(int currentGeneration, String ownIp, String name, String type, String ip,
                        Set<String> seen, Listener listener) {
        if (currentGeneration != generation.get()) return;
        if (!LocalDiscovery.isIpv4(ip) || ip.equals(ownIp) || !seen.add(ip)) return;
        listener.onDevice(name, type, ip);
    }

    private void sourceFinished(int currentGeneration, AtomicInteger sources, Set<String> seen, Listener listener) {
        if (currentGeneration != generation.get()) return;
        if (sources.decrementAndGet() == 0) listener.onFinished(seen.size());
    }
}
