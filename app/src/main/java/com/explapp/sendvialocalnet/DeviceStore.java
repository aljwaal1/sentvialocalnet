package com.explapp.sendvialocalnet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class DeviceStore {
    private static final String PREFS = "send_via_local_net";
    private static final String KEY_DEVICES = "devices";
    private static final String KEY_MY_NAME = "my_name";
    private final SharedPreferences prefs;

    DeviceStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String getMyName(String fallback) {
        String value = prefs.getString(KEY_MY_NAME, fallback);
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    void setMyName(String value) {
        prefs.edit().putString(KEY_MY_NAME, value == null || value.trim().length() == 0 ? "هاتف Android" : value.trim()).apply();
    }

    ArrayList<DeviceRecord> load() {
        ArrayList<DeviceRecord> devices = new ArrayList<DeviceRecord>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_DEVICES, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                String ip = item.optString("ip", "");
                if (!LocalDiscovery.isIpv4(ip)) continue;
                devices.add(new DeviceRecord(
                        item.optString("name", "جهاز " + ip),
                        ip,
                        item.optString("type", "device"),
                        item.optBoolean("selected", false)
                ));
            }
        } catch (Exception ignored) {}
        return devices;
    }

    synchronized void save(List<DeviceRecord> devices) {
        JSONArray array = new JSONArray();
        try {
            for (DeviceRecord device : devices) {
                JSONObject item = new JSONObject();
                item.put("name", device.name);
                item.put("ip", device.ip);
                item.put("type", device.type);
                item.put("selected", device.selected);
                array.put(item);
            }
        } catch (Exception ignored) {}
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply();
    }
}
