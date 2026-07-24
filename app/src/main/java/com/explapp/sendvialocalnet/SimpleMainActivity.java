package com.explapp.sendvialocalnet;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

public class SimpleMainActivity extends Activity {
    private static final int PICK_FILES = 901;

    private final ArrayList<DeviceRecord> devices = new ArrayList<DeviceRecord>();
    private final ArrayList<Uri> files = new ArrayList<Uri>();
    private final Handler handler = new Handler();

    private DeviceStore store;
    private DeviceScanner scanner;
    private TransferReceiver receiver;
    private FileSender sender;

    private TextView receiverState;
    private TextView addressView;
    private TextView searchState;
    private TextView deviceCount;
    private TextView fileState;
    private TextView readyState;
    private TextView transferState;
    private TextView logView;
    private LinearLayout deviceList;
    private LinearLayout advancedPanel;
    private Button searchButton;
    private Button sendButton;
    private Button advancedButton;
    private ProgressBar progress;

    private EditText myNameInput;
    private EditText manualNameInput;
    private EditText manualIpInput;
    private volatile boolean searching;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DeviceStore(this);
        devices.addAll(store.load());

        LocalDiscovery.NameProvider nameProvider = new LocalDiscovery.NameProvider() {
            @Override public String getDeviceName() {
                return store.getMyName(Build.MODEL == null ? "هاتف Android" : Build.MODEL);
            }
        };

        scanner = new DeviceScanner(this, nameProvider);
        sender = new FileSender(this);
        receiver = new TransferReceiver(this, nameProvider, new TransferReceiver.Listener() {
            @Override public void onState(final boolean running, final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        receiverState.setText(message);
                        receiverState.setTextColor(running ? Color.rgb(209, 250, 229) : Color.rgb(254, 202, 202));
                        updateAddress();
                    }
                });
            }

            @Override public void onProgress(final int percent) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        transferState.setText("استقبال ملف... " + percent + "%");
                        progress.setProgress(percent);
                    }
                });
            }

            @Override public void onReceived(final File file) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        transferState.setText("تم استقبال: " + file.getName());
                        progress.setProgress(100);
                        toast("وصل ملف جديد");
                    }
                });
            }

            @Override public void onLog(final String message) {
                addLog(message);
            }
        });

        buildUi();
        requestStoragePermission();
        receiver.start();
        handler.postDelayed(new Runnable() {
            @Override public void run() { startSearch(); }
        }, 650);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (receiver != null && !receiver.isRunning()) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { receiver.start(); }
            }, 250);
        }
        updateAddress();
    }

    private void buildUi() {
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(Color.rgb(38, 45, 94));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(26));
        root.setBackgroundColor(Color.rgb(244, 246, 252));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout hero = card(Color.rgb(67, 56, 202), 22);
        hero.addView(text("نقل محلي Pro", 25, Color.WHITE, true));
        TextView subtitle = text("ثلاث خطوات فقط: ابحث، اختر، أرسل", 14, Color.rgb(224, 231, 255), false);
        subtitle.setPadding(0, dp(4), 0, dp(11));
        hero.addView(subtitle);
        receiverState = text("جاري تشغيل الاستقبال...", 13, Color.rgb(209, 250, 229), true);
        receiverState.setPadding(dp(11), dp(9), dp(11), dp(9));
        receiverState.setBackground(rounded(Color.rgb(55, 48, 163), 12));
        hero.addView(receiverState);
        addressView = text("", 12, Color.rgb(224, 231, 255), false);
        addressView.setPadding(dp(2), dp(8), dp(2), 0);
        addressView.setTextIsSelectable(true);
        hero.addView(addressView);
        root.addView(hero, bottom(11));

        LinearLayout searchCard = card(Color.WHITE, 19);
        searchCard.addView(step("1", "ابحث عن الأجهزة", "افتح التطبيق على الجهاز الآخر ثم اضغط البحث"));
        searchButton = button("بحث عن الأجهزة الآن", Color.rgb(79, 70, 229), Color.WHITE);
        searchButton.setTextSize(16);
        searchButton.setMinHeight(dp(54));
        searchCard.addView(searchButton);
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startSearch(); }
        });

        searchState = text("سيبدأ البحث تلقائيًا", 13, Color.rgb(71, 84, 103), false);
        searchState.setPadding(dp(11), dp(9), dp(11), dp(9));
        searchState.setBackground(rounded(Color.rgb(248, 250, 252), 12));
        searchCard.addView(searchState, bottom(9));

        LinearLayout listHeader = row();
        deviceCount = text("الأجهزة: 0", 14, Color.rgb(16, 24, 40), true);
        listHeader.addView(deviceCount, new LinearLayout.LayoutParams(0, -2, 1));
        Button selectOnline = smallButton("تحديد المتصلة", Color.rgb(238, 242, 255), Color.rgb(67, 56, 202));
        Button clearSelection = smallButton("إلغاء", Color.rgb(248, 250, 252), Color.rgb(71, 84, 103));
        listHeader.addView(selectOnline);
        listHeader.addView(clearSelection);
        searchCard.addView(listHeader, bottom(7));

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        searchCard.addView(deviceList);
        root.addView(searchCard, bottom(11));

        selectOnline.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                synchronized (devices) {
                    for (DeviceRecord device : devices) device.selected = device.online;
                }
                saveAndRender();
            }
        });
        clearSelection.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                synchronized (devices) {
                    for (DeviceRecord device : devices) device.selected = false;
                }
                saveAndRender();
            }
        });

        LinearLayout sendCard = card(Color.WHITE, 19);
        sendCard.addView(step("2", "اختر الملفات", "يمكن اختيار أكثر من ملف في المرة نفسها"));
        LinearLayout fileButtons = row();
        Button chooseFiles = button("اختيار الملفات", Color.rgb(14, 165, 233), Color.WHITE);
        Button clearFiles = button("مسح", Color.rgb(248, 250, 252), Color.rgb(71, 84, 103));
        fileButtons.addView(chooseFiles, weight(3, 5));
        fileButtons.addView(clearFiles, weight(1, 0));
        sendCard.addView(fileButtons);
        fileState = text("لم يتم اختيار ملفات", 13, Color.rgb(102, 112, 133), false);
        fileState.setPadding(dp(11), dp(10), dp(11), dp(10));
        fileState.setBackground(rounded(Color.rgb(248, 250, 252), 12));
        sendCard.addView(fileState, bottom(10));

        sendCard.addView(step("3", "أرسل", "سيتم الإرسال إلى جميع الأجهزة المحددة"));
        readyState = text("حدد جهازًا ثم اختر الملفات", 13, Color.rgb(71, 84, 103), true);
        readyState.setPadding(0, 0, 0, dp(8));
        sendCard.addView(readyState);
        sendButton = button("إرسال الآن", Color.rgb(16, 185, 129), Color.WHITE);
        sendButton.setTextSize(17);
        sendButton.setMinHeight(dp(56));
        sendCard.addView(sendButton);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        sendCard.addView(progress, top(10));
        transferState = text("جاهز", 13, Color.rgb(52, 64, 84), true);
        transferState.setPadding(0, dp(8), 0, 0);
        sendCard.addView(transferState);
        root.addView(sendCard, bottom(11));

        chooseFiles.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { chooseFiles(); }
        });
        clearFiles.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                files.clear();
                renderFiles();
            }
        });
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { sendFiles(); }
        });

        advancedButton = button("الإعدادات المتقدمة", Color.rgb(238, 242, 255), Color.rgb(67, 56, 202));
        root.addView(advancedButton, bottom(8));

        advancedPanel = card(Color.WHITE, 19);
        advancedPanel.setVisibility(View.GONE);
        advancedPanel.addView(section("الإعدادات المتقدمة", "لا تحتاج إليها في الاستخدام العادي"));

        myNameInput = input("اسم هذا الهاتف");
        myNameInput.setText(store.getMyName(Build.MODEL == null ? "هاتف Android" : Build.MODEL));
        advancedPanel.addView(myNameInput);
        Button saveName = button("حفظ اسم الهاتف", Color.rgb(79, 70, 229), Color.WHITE);
        advancedPanel.addView(saveName);

        TextView manualTitle = text("إضافة جهاز يدويًا", 15, Color.rgb(16, 24, 40), true);
        manualTitle.setPadding(0, dp(8), 0, dp(7));
        advancedPanel.addView(manualTitle);
        manualNameInput = input("اسم الجهاز");
        manualIpInput = input("IP مثل 192.168.1.20");
        manualIpInput.setInputType(InputType.TYPE_CLASS_PHONE);
        advancedPanel.addView(manualNameInput);
        advancedPanel.addView(manualIpInput);
        Button saveManual = button("حفظ الجهاز يدويًا", Color.rgb(14, 165, 233), Color.WHITE);
        advancedPanel.addView(saveManual);

        LinearLayout tools = row();
        Button copyAddress = button("نسخ عنوان الهاتف", Color.rgb(238, 242, 255), Color.rgb(67, 56, 202));
        Button advancedOld = button("الوضع المتقدم القديم", Color.rgb(248, 250, 252), Color.rgb(71, 84, 103));
        tools.addView(copyAddress, weight(1, 5));
        tools.addView(advancedOld, weight(1, 0));
        advancedPanel.addView(tools);

        TextView logTitle = text("سجل النشاط", 15, Color.rgb(16, 24, 40), true);
        logTitle.setPadding(0, dp(8), 0, dp(7));
        advancedPanel.addView(logTitle);
        logView = text("", 12, Color.rgb(71, 84, 103), false);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(9), dp(9), dp(9), dp(9));
        logView.setBackground(rounded(Color.rgb(248, 250, 252), 12));
        advancedPanel.addView(logView);
        Button clearLog = smallButton("مسح السجل", Color.rgb(254, 242, 242), Color.rgb(185, 28, 28));
        advancedPanel.addView(clearLog);
        root.addView(advancedPanel);

        advancedButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                boolean show = advancedPanel.getVisibility() != View.VISIBLE;
                advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                advancedButton.setText(show ? "إخفاء الإعدادات المتقدمة" : "الإعدادات المتقدمة");
            }
        });
        saveName.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                store.setMyName(myNameInput.getText().toString());
                toast("تم حفظ اسم الهاتف");
            }
        });
        saveManual.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { addManualDevice(); }
        });
        copyAddress.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { copyAddress(); }
        });
        advancedOld.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                receiver.stop();
                startActivity(new Intent(SimpleMainActivity.this, MainActivity.class));
            }
        });
        clearLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { logView.setText(""); }
        });

        setContentView(scroll);
        renderDevices();
        renderFiles();
        updateAddress();
    }

    private void startSearch() {
        if (searching) {
            toast("البحث يعمل الآن");
            return;
        }
        String ip = LocalDiscovery.getBestLocalIp();
        if (!LocalDiscovery.isIpv4(ip)) {
            searchState.setText("اتصل بشبكة Wi‑Fi ثم أعد البحث.");
            return;
        }

        searching = true;
        synchronized (devices) {
            for (DeviceRecord device : devices) device.online = false;
        }
        renderDevices();
        searchButton.setEnabled(false);
        searchButton.setText("جارٍ البحث...");
        searchState.setText("بحث سريع مع فحص احتياطي للشبكة. قد يستغرق عدة ثوانٍ.");

        scanner.scan(new DeviceScanner.Listener() {
            @Override public void onDevice(String name, String type, String ip) {
                updateDiscovered(name, type, ip);
            }

            @Override public void onFinished(final int count) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        searching = false;
                        searchButton.setEnabled(true);
                        searchButton.setText("إعادة البحث عن الأجهزة");
                        int online = onlineCount();
                        searchState.setText(online == 0
                                ? "لم يظهر جهاز. افتح الأداة على الجهاز الآخر وتأكد أن الشبكة نفسها."
                                : "تم العثور على " + online + " جهاز متصل. حدد الجهاز ثم اختر الملفات.");
                        renderDevices();
                        addLog("اكتمل البحث: " + online + " جهاز متصل");
                    }
                });
            }

            @Override public void onLog(String message) {
                addLog(message);
            }
        });
    }

    private void updateDiscovered(String name, String type, String ip) {
        synchronized (devices) {
            DeviceRecord existing = null;
            for (DeviceRecord device : devices) {
                if (device.ip.equals(ip)) {
                    existing = device;
                    break;
                }
            }
            if (existing == null) {
                existing = new DeviceRecord(name, ip, type, true);
                devices.add(0, existing);
            } else {
                if (existing.name == null || existing.name.trim().length() == 0 || existing.name.startsWith("جهاز ")) {
                    existing.name = name;
                }
                existing.type = type;
            }
            existing.online = true;
            existing.lastSeen = System.currentTimeMillis();
            store.save(devices);
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                renderDevices();
                searchState.setText("تم العثور على " + onlineCount() + " جهاز حتى الآن...");
            }
        });
    }

    private void renderDevices() {
        if (deviceList == null) return;
        ArrayList<DeviceRecord> copy;
        synchronized (devices) {
            copy = new ArrayList<DeviceRecord>(devices);
        }
        Collections.sort(copy, new Comparator<DeviceRecord>() {
            @Override public int compare(DeviceRecord first, DeviceRecord second) {
                if (first.online != second.online) return first.online ? -1 : 1;
                return first.name.compareToIgnoreCase(second.name);
            }
        });

        deviceList.removeAllViews();
        int selected = 0;
        int online = 0;
        for (DeviceRecord device : copy) {
            if (device.selected) selected++;
            if (device.online) online++;
        }
        deviceCount.setText("متصل: " + online + "  •  محدد: " + selected);

        if (copy.isEmpty()) {
            TextView empty = text("اضغط زر البحث وستظهر الأجهزة هنا تلقائيًا.", 13, Color.rgb(102, 112, 133), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(20), dp(10), dp(20));
            empty.setBackground(rounded(Color.rgb(248, 250, 252), 14));
            deviceList.addView(empty);
            refreshReadyState();
            return;
        }

        for (final DeviceRecord device : copy) {
            LinearLayout item = row();
            item.setPadding(dp(9), dp(9), dp(8), dp(9));
            item.setBackground(rounded(
                    device.selected ? Color.rgb(238, 242, 255) : Color.rgb(248, 250, 252),
                    14,
                    device.online ? Color.rgb(167, 243, 208) : Color.rgb(234, 236, 240)
            ));

            CheckBox checkbox = new CheckBox(this);
            checkbox.setChecked(device.selected);
            checkbox.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    device.selected = ((CheckBox)view).isChecked();
                    saveAndRender();
                }
            });
            item.addView(checkbox, new LinearLayout.LayoutParams(dp(44), -2));

            LinearLayout information = new LinearLayout(this);
            information.setOrientation(LinearLayout.VERTICAL);
            information.addView(text(device.name, 15, Color.rgb(16, 24, 40), true));
            String type = "windows".equalsIgnoreCase(device.type) ? "Windows" :
                    ("android".equalsIgnoreCase(device.type) ? "Android" : "جهاز");
            information.addView(text(
                    (device.online ? "● متصل الآن" : "○ محفوظ وغير ظاهر") + "  •  " + type,
                    12,
                    device.online ? Color.rgb(5, 122, 85) : Color.rgb(102, 112, 133),
                    false
            ));
            information.addView(text(device.ip + ":5051", 11, Color.rgb(152, 162, 179), false));
            information.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    device.selected = !device.selected;
                    saveAndRender();
                }
            });
            item.addView(information, new LinearLayout.LayoutParams(0, -2, 1));

            Button remove = smallButton("حذف", Color.rgb(254, 242, 242), Color.rgb(185, 28, 28));
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    synchronized (devices) { devices.remove(device); }
                    saveAndRender();
                }
            });
            item.addView(remove);
            deviceList.addView(item, bottom(7));
        }
        refreshReadyState();
    }

    private void addManualDevice() {
        String ip = manualIpInput.getText().toString().trim();
        String name = manualNameInput.getText().toString().trim();
        if (!LocalDiscovery.isIpv4(ip)) {
            toast("عنوان IP غير صحيح");
            return;
        }
        if (name.length() == 0) name = "جهاز " + ip;
        synchronized (devices) {
            DeviceRecord existing = null;
            for (DeviceRecord device : devices) if (device.ip.equals(ip)) existing = device;
            if (existing == null) devices.add(0, new DeviceRecord(name, ip, "device", true));
            else {
                existing.name = name;
                existing.selected = true;
            }
        }
        manualNameInput.setText("");
        manualIpInput.setText("");
        saveAndRender();
        toast("تم حفظ الجهاز");
    }

    private void saveAndRender() {
        synchronized (devices) { store.save(devices); }
        renderDevices();
    }

    private int onlineCount() {
        int count = 0;
        synchronized (devices) {
            for (DeviceRecord device : devices) if (device.online) count++;
        }
        return count;
    }

    private void chooseFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "اختر الملفات"), PICK_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return;
        Set<String> seen = new LinkedHashSet<String>();
        for (Uri uri : files) seen.add(uri.toString());

        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) addUri(clip.getItemAt(index).getUri(), seen);
        } else {
            addUri(data.getData(), seen);
        }
        renderFiles();
    }

    private void addUri(Uri uri, Set<String> seen) {
        if (uri == null || !seen.add(uri.toString())) return;
        files.add(uri);
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    private void renderFiles() {
        if (files.isEmpty()) {
            fileState.setText("لم يتم اختيار ملفات");
            refreshReadyState();
            return;
        }
        StringBuilder value = new StringBuilder("تم اختيار " + files.size() + " ملف");
        int maximum = Math.min(4, files.size());
        for (int index = 0; index < maximum; index++) value.append("\n• ").append(sender.displayName(files.get(index)));
        if (files.size() > maximum) value.append("\n… و").append(files.size() - maximum).append(" ملفات أخرى");
        fileState.setText(value.toString());
        refreshReadyState();
    }

    private void refreshReadyState() {
        if (sendButton == null) return;
        int selected = 0;
        synchronized (devices) {
            for (DeviceRecord device : devices) if (device.selected) selected++;
        }
        sendButton.setEnabled(selected > 0 && !files.isEmpty());
        if (selected == 0 && files.isEmpty()) readyState.setText("حدد جهازًا ثم اختر الملفات");
        else if (selected == 0) readyState.setText("الملفات جاهزة — حدد جهازًا");
        else if (files.isEmpty()) readyState.setText("تم تحديد " + selected + " جهاز — اختر الملفات");
        else readyState.setText("جاهز: " + files.size() + " ملف إلى " + selected + " جهاز");
    }

    private void sendFiles() {
        final ArrayList<DeviceRecord> targets = new ArrayList<DeviceRecord>();
        synchronized (devices) {
            for (DeviceRecord device : devices) if (device.selected) targets.add(device);
        }
        if (targets.isEmpty()) {
            toast("حدد جهازًا واحدًا على الأقل");
            return;
        }
        if (files.isEmpty()) {
            toast("اختر ملفًا واحدًا على الأقل");
            return;
        }

        sendButton.setEnabled(false);
        progress.setProgress(0);
        transferState.setText("بدء الإرسال...");
        sender.send(targets, new ArrayList<Uri>(files), new FileSender.Listener() {
            @Override public void onProgress(final int completed, final int total, final int succeeded, final int failed) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        progress.setProgress(completed * 100 / Math.max(1, total));
                        transferState.setText("اكتمل " + completed + "/" + total + " — نجح " + succeeded + "، فشل " + failed);
                    }
                });
            }

            @Override public void onLog(String message) {
                addLog(message);
            }

            @Override public void onDone(final int succeeded, final int failed) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        sendButton.setEnabled(true);
                        if (failed == 0) toast("تم إرسال جميع الملفات بنجاح");
                    }
                });
            }
        });
    }

    private void updateAddress() {
        if (addressView == null) return;
        String ip = LocalDiscovery.getBestLocalIp();
        addressView.setText(LocalDiscovery.isIpv4(ip) ? "هذا الهاتف: " + ip + ":5051" : "غير متصل بشبكة Wi‑Fi");
    }

    private void copyAddress() {
        String ip = LocalDiscovery.getBestLocalIp();
        if (!LocalDiscovery.isIpv4(ip)) {
            toast("لا يوجد IP صالح");
            return;
        }
        ClipboardManager manager = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(android.content.ClipData.newPlainText("SendViaLocalNet", "http://" + ip + ":5051/upload"));
        }
        toast("تم نسخ العنوان");
    }

    private void addLog(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (logView != null) logView.setText("• " + message + "\n" + logView.getText());
            }
        });
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 5);
        }
    }

    private LinearLayout step(String number, String title, String subtitle) {
        LinearLayout container = row();
        TextView badge = text(number, 16, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(Color.rgb(79, 70, 229), 99));
        container.addView(badge, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout information = new LinearLayout(this);
        information.setOrientation(LinearLayout.VERTICAL);
        information.setPadding(dp(10), 0, 0, dp(10));
        information.addView(text(title, 19, Color.rgb(16, 24, 40), true));
        information.addView(text(subtitle, 12, Color.rgb(102, 112, 133), false));
        container.addView(information, new LinearLayout.LayoutParams(0, -2, 1));
        return container;
    }

    private LinearLayout section(String title, String subtitle) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(text(title, 18, Color.rgb(16, 24, 40), true));
        TextView sub = text(subtitle, 12, Color.rgb(102, 112, 133), false);
        sub.setPadding(0, dp(3), 0, dp(10));
        container.addView(sub);
        return container;
    }

    private LinearLayout card(int color, int radius) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(15), dp(15), dp(15), dp(15));
        container.setBackground(rounded(color, radius));
        return container;
    }

    private LinearLayout row() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        return container;
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
        input.setLayoutParams(bottom(9));
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
        button.setLayoutParams(bottom(8));
        return button;
    }

    private Button smallButton(String label, int background, int foreground) {
        Button button = button(label, background, foreground);
        button.setTextSize(12);
        button.setMinHeight(dp(38));
        button.setPadding(dp(9), dp(6), dp(9), dp(6));
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
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

    private GradientDrawable rounded(int color, int radius) {
        return rounded(color, radius, 0);
    }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams bottom(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(value));
        return params;
    }

    private LinearLayout.LayoutParams top(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(value), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weight(int weight, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weight);
        params.setMargins(0, 0, dp(margin), 0);
        return params;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (scanner != null) scanner.shutdown();
        if (receiver != null) receiver.shutdown();
        if (sender != null) sender.shutdown();
        super.onDestroy();
    }
}
