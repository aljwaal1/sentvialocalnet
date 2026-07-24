package com.explapp.sendvialocalnet;

final class DeviceRecord {
    String id;
    String name;
    String ip;
    String type;
    boolean selected;
    boolean online;
    long lastSeen;

    DeviceRecord(String name, String ip, String type, boolean selected) {
        this("", name, ip, type, selected);
    }

    DeviceRecord(String id, String name, String ip, String type, boolean selected) {
        this.id = id == null ? "" : id.trim();
        this.name = name == null || name.trim().length() == 0 ? "جهاز " + ip : name.trim();
        this.ip = ip;
        this.type = type == null || type.trim().length() == 0 ? "device" : type.trim();
        this.selected = selected;
    }
}
