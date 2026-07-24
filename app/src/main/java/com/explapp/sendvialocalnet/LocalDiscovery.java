package com.explapp.sendvialocalnet;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalDiscovery {
    public static final int DISCOVERY_PORT = 5052;
    private static final String DISCOVER_PREFIX = "SVLN_DISCOVER|";
    private static final String DEVICE_PREFIX = "SVLN_DEVICE|";

    public interface NameProvider {
        String getDeviceName();
    }

    public interface Listener {
        void onDevice(String name, String type, String ip, int port);
        void onFinished(int responses);
        void onError(String message);
    }

    private final Context context;
    private final NameProvider nameProvider;
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private volatile boolean responderRunning;
    private DatagramSocket responderSocket;
    private Thread responderThread;

    public LocalDiscovery(Context context, NameProvider nameProvider) {
        this.context = context.getApplicationContext();
        this.nameProvider = nameProvider;
    }

    public synchronized void startResponder() {
        if (responderRunning) return;
        responderRunning = true;
        responderThread = new Thread(new Runnable() {
            @Override public void run() {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket(null);
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.bind(new InetSocketAddress("0.0.0.0", DISCOVERY_PORT));
                    socket.setSoTimeout(1000);
                    responderSocket = socket;
                    byte[] buffer = new byte[1024];
                    while (responderRunning) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        try {
                            socket.receive(packet);
                        } catch (SocketTimeoutException ignored) {
                            continue;
                        }
                        String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), "UTF-8");
                        if (!request.startsWith(DISCOVER_PREFIX)) continue;
                        String ip = getBestLocalIp();
                        if ("0.0.0.0".equals(ip)) continue;
                        String reply = DEVICE_PREFIX + clean(nameProvider.getDeviceName()) + "|android|" + ip + "|5051";
                        byte[] data = reply.getBytes("UTF-8");
                        DatagramPacket answer = new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort());
                        socket.send(answer);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (socket != null) socket.close();
                    responderSocket = null;
                    responderRunning = false;
                }
            }
        }, "svln-discovery-responder");
        responderThread.start();
    }

    public void discover(final Listener listener) {
        worker.execute(new Runnable() {
            @Override public void run() {
                WifiManager.MulticastLock lock = null;
                DatagramSocket socket = null;
                int responses = 0;
                try {
                    WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                    if (wifi != null) {
                        lock = wifi.createMulticastLock("SendViaLocalNetDiscovery");
                        lock.setReferenceCounted(false);
                        lock.acquire();
                    }

                    socket = new DatagramSocket();
                    socket.setBroadcast(true);
                    socket.setSoTimeout(220);
                    String token = Long.toHexString(System.currentTimeMillis());
                    byte[] request = (DISCOVER_PREFIX + token).getBytes("UTF-8");

                    ArrayList<InetAddress> targets = new ArrayList<InetAddress>();
                    targets.add(InetAddress.getByName("255.255.255.255"));
                    String localIp = getBestLocalIp();
                    String subnetBroadcast = getSubnetBroadcast(localIp);
                    if (subnetBroadcast != null) targets.add(InetAddress.getByName(subnetBroadcast));

                    Set<String> uniqueTargets = new LinkedHashSet<String>();
                    for (InetAddress target : targets) {
                        if (!uniqueTargets.add(target.getHostAddress())) continue;
                        for (int index = 0; index < 3; index++) {
                            DatagramPacket packet = new DatagramPacket(request, request.length, target, DISCOVERY_PORT);
                            socket.send(packet);
                            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                        }
                    }

                    long deadline = System.currentTimeMillis() + 2300;
                    Set<String> seen = new LinkedHashSet<String>();
                    byte[] buffer = new byte[2048];
                    while (System.currentTimeMillis() < deadline) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        try {
                            socket.receive(packet);
                        } catch (SocketTimeoutException ignored) {
                            continue;
                        }
                        String reply = new String(packet.getData(), packet.getOffset(), packet.getLength(), "UTF-8");
                        if (!reply.startsWith(DEVICE_PREFIX)) continue;
                        String[] parts = reply.split("\\|", -1);
                        if (parts.length < 5) continue;
                        String name = parts[1].trim();
                        String type = parts[2].trim();
                        String ip = parts[3].trim();
                        int port = 5051;
                        try { port = Integer.parseInt(parts[4].trim()); } catch (Exception ignored) {}
                        if (!isIpv4(ip) || !seen.add(ip + ":" + port)) continue;
                        responses++;
                        listener.onDevice(name.length() == 0 ? "جهاز " + ip : name,
                                type.length() == 0 ? "device" : type, ip, port);
                    }
                    listener.onFinished(responses);
                } catch (Exception error) {
                    listener.onError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    listener.onFinished(responses);
                } finally {
                    if (socket != null) socket.close();
                    try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
                }
            }
        });
    }

    public synchronized void shutdown() {
        responderRunning = false;
        if (responderSocket != null) responderSocket.close();
        responderSocket = null;
        worker.shutdownNow();
    }

    private static String clean(String value) {
        if (value == null) return "Android";
        String cleaned = value.replace("|", " ").replace("\r", " ").replace("\n", " ").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : (cleaned.length() == 0 ? "Android" : cleaned);
    }

    private static String getSubnetBroadcast(String ip) {
        if (!isIpv4(ip)) return null;
        int dot = ip.lastIndexOf('.');
        return dot > 0 ? ip.substring(0, dot + 1) + "255" : null;
    }

    public static String getBestLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        if (isIpv4(ip) && !ip.startsWith("127.")) return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    public static boolean isIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                if (part.length() == 0) return false;
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }
}
