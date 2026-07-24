package com.explapp.sendvialocalnet;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

public class StableMainActivity extends Activity {
    private static final int PICK_FILES = 1401;

    private final ArrayList<DeviceRecord> devices = new ArrayList<DeviceRecord>();
    private final ArrayList<Uri> files = new ArrayList<Uri>();
    private DeviceStore store;
    private DeviceScanner scanner;
    private TransferReceiver receiver;
    private FileSender sender;

    private LinearLayout deviceList;
    private TextView searchState;
    private TextView deviceSummary;
    private TextView fileSummary;
    private TextView transferState;
    private TextView receiverState;
    private ProgressBar progress;
    private Button searchButton;
    private Button sendButton;
    private EditText manualName;
    private EditText manualIp;
    private volatile boolean searching;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DeviceStore(this);
        devices.addAll(store.load());

        LocalDiscovery.NameProvider identity = new LocalDiscovery.NameProvider() {
            @Override public String getDeviceName() {
                return store.getMyName(Build.MODEL == null ? "هاتف Android" : Build.MODEL);
            }
            @Override public String getDeviceId() {
                return store.getMyId();
            }
        };

        scanner = new DeviceScanner(this, identity);
        sender = new FileSender(this);
        receiver = new TransferReceiver(this, identity, new TransferReceiver.Listener() {
            @Override public void onState(final boolean running, final String message) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    receiverState.setText(message);
                    receiverState.setTextColor(running ? Color.rgb(5, 122, 85) : Color.rgb(185, 28, 28));
                }});
            }
            @Override public void onProgress(final int percent) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    transferState.setText("استقبال ملف... " + percent + "%");
                    progress.setProgress(percent);
                }});
            }
            @Override public void onReceived(final File file) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    transferState.setText("تم استقبال: " + file.getName());
                    progress.setProgress(100);
                    toast("وصل ملف جديد");
                }});
            }
            @Override public void onLog(String message) { }
        });

        buildUi();
        requestStoragePermission();
        receiver.start();
        searchButton.postDelayed(new Runnable() { @Override public void run() { startSearch(); } }, 650);
    }

    private void buildUi() {
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(Color.rgb(38, 45, 94));
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(28));
        root.setBackgroundColor(Color.rgb(244, 246, 252));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout hero = card(Color.rgb(67, 56, 202));
        hero.addView(text("نقل محلي Pro", 25, Color.WHITE, true));
        hero.addView(text("لا تقلق من تغيّر IP — التطبيق يحدّثه تلقائيًا", 13, Color.rgb(224, 231, 255), false));
        receiverState = text("جاري تشغيل الاستقبال...", 13, Color.rgb(209, 250, 229), true);
        receiverState.setPadding(0, dp(10), 0, 0);
        hero.addView(receiverState);
        root.addView(hero, bottom(11));

        LinearLayout searchCard = card(Color.WHITE);
        searchCard.addView(title("1. ابحث عن الأجهزة", "يتم التعرف على الجهاز نفسه حتى لو تغيّر عنوانه"));
        searchButton = button("بحث عن الأجهزة الآن", Color.rgb(79, 70, 229), Color.WHITE);
        searchCard.addView(searchButton);
        searchState = info("سيبدأ البحث تلقائيًا");
        searchCard.addView(searchState, bottom(8));

        LinearLayout controls = row();
        deviceSummary = text("متصل: 0  •  محدد: 0", 13, Color.rgb(16, 24, 40), true);
        controls.addView(deviceSummary, new LinearLayout.LayoutParams(0, -2, 1));
        Button selectOnline = smallButton("تحديد المتصلة", Color.rgb(238, 242, 255), Color.rgb(67, 56, 202));
        Button clear = smallButton("إلغاء", Color.rgb(248, 250, 252), Color.rgb(71, 84, 103));
        controls.addView(selectOnline);
        controls.addView(clear);
        searchCard.addView(controls, bottom(7));

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        searchCard.addView(deviceList);
        root.addView(searchCard, bottom(11));

        searchButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startSearch(); }});
        selectOnline.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            synchronized (devices) { for (DeviceRecord d : devices) d.selected = d.online; }
            saveRender();
        }});
        clear.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            synchronized (devices) { for (DeviceRecord d : devices) d.selected = false; }
            saveRender();
        }});

        LinearLayout sendCard = card(Color.WHITE);
        sendCard.addView(title("2. اختر الملفات", "يمكن اختيار عدة ملفات وإرسالها لعدة أجهزة"));
        LinearLayout fileButtons = row();
        Button choose = button("اختيار الملفات", Color.rgb(14, 165, 233), Color.WHITE);
        Button clearFiles = button("مسح", Color.rgb(248, 250, 252), Color.rgb(71, 84, 103));
        fileButtons.addView(choose, weight(3, 5));
        fileButtons.addView(clearFiles, weight(1, 0));
        sendCard.addView(fileButtons);
        fileSummary = info("لم يتم اختيار ملفات");
        sendCard.addView(fileSummary, bottom(10));
        sendCard.addView(title("3. أرسل", "يستخدم التطبيق آخر IP مكتشف لكل جهاز"));
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

        choose.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseFiles(); }});
        clearFiles.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { files.clear(); renderFiles(); }});
        sendButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sendFiles(); }});

        LinearLayout advanced = card(Color.WHITE);
        advanced.addView(title("إضافة يدوية عند الحاجة", "استخدمها فقط إذا منع الراوتر اكتشاف الأجهزة"));
        manualName = input("اسم الجهاز");
        manualIp = input("IP مثل 192.168.1.20");
        manualIp.setInputType(InputType.TYPE_CLASS_PHONE);
        advanced.addView(manualName);
        advanced.addView(manualIp);
        Button add = button("حفظ الجهاز يدويًا", Color.rgb(238, 242, 255), Color.rgb(67, 56, 202));
        advanced.addView(add);
        Button old = button("فتح الواجهة السابقة", Color.rgb(248, 250, 252), Color.rgb(71, 84, 103));
        advanced.addView(old);
        root.addView(advanced);
        add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { addManual(); }});
        old.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            receiver.stop();
            startActivity(new Intent(StableMainActivity.this, SimpleMainActivity.class));
        }});

        setContentView(scroll);
        renderDevices();
        renderFiles();
    }

    private void startSearch() {
        if (searching) { toast("البحث يعمل الآن"); return; }
        if (!LocalDiscovery.isIpv4(LocalDiscovery.getBestLocalIp())) {
            searchState.setText("اتصل بشبكة Wi‑Fi ثم أعد البحث");
            return;
        }
        searching = true;
        synchronized (devices) { for (DeviceRecord d : devices) d.online = false; }
        renderDevices();
        searchButton.setEnabled(false);
        searchButton.setText("جارٍ البحث...");
        searchState.setText("البحث عن الأجهزة وتحديث عناوينها تلقائيًا...");

        scanner.scan(new DeviceScanner.Listener() {
            @Override public void onDevice(String name, String type, String ip) {
                mergeDevice("", name, type, ip);
            }
            @Override public void onIdentifiedDevice(String id, String name, String type, String ip) {
                mergeDevice(id, name, type, ip);
            }
            @Override public void onFinished(int count) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    searching = false;
                    searchButton.setEnabled(true);
                    searchButton.setText("إعادة البحث");
                    int online = onlineCount();
                    searchState.setText(online == 0 ? "لم يظهر جهاز. افتح الأداة على الجهاز الآخر وتأكد من شبكة Wi‑Fi." :
                            "تم العثور على " + online + " جهاز، وتم تحديث أي IP تغيّر.");
                    renderDevices();
                }});
            }
            @Override public void onLog(String message) { }
        });
    }

    private void mergeDevice(String id, String name, String type, String ip) {
        synchronized (devices) {
            DeviceRecord found = null;
            if (id != null && id.length() > 0) {
                for (DeviceRecord d : devices) if (id.equals(d.id)) { found = d; break; }
            }
            if (found == null) {
                for (DeviceRecord d : devices) if (ip.equals(d.ip)) { found = d; break; }
            }
            if (found == null) {
                found = new DeviceRecord(id, name, ip, type, true);
                devices.add(0, found);
            } else {
                if (id != null && id.length() > 0) found.id = id;
                found.name = name;
                found.type = type;
                found.ip = ip;
            }
            found.online = true;
            found.lastSeen = System.currentTimeMillis();
            removeDuplicates(found);
            store.save(devices);
        }
        runOnUiThread(new Runnable() { @Override public void run() { renderDevices(); }});
    }

    private void removeDuplicates(DeviceRecord kept) {
        for (int i = devices.size() - 1; i >= 0; i--) {
            DeviceRecord other = devices.get(i);
            if (other == kept) continue;
            boolean sameId = kept.id.length() > 0 && kept.id.equals(other.id);
            boolean sameIp = kept.ip.equals(other.ip);
            if (sameId || sameIp) devices.remove(i);
        }
    }

    private void renderDevices() {
        ArrayList<DeviceRecord> copy;
        synchronized (devices) { copy = new ArrayList<DeviceRecord>(devices); }
        Collections.sort(copy, new Comparator<DeviceRecord>() { @Override public int compare(DeviceRecord a, DeviceRecord b) {
            if (a.online != b.online) return a.online ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        }});
        deviceList.removeAllViews();
        int online = 0, selected = 0;
        for (DeviceRecord d : copy) { if (d.online) online++; if (d.selected) selected++; }
        deviceSummary.setText("متصل: " + online + "  •  محدد: " + selected);
        if (copy.isEmpty()) {
            deviceList.addView(info("اضغط البحث وستظهر الأجهزة هنا تلقائيًا"));
        } else {
            for (final DeviceRecord d : copy) {
                LinearLayout item = row();
                item.setPadding(dp(8), dp(8), dp(7), dp(8));
                item.setBackground(rounded(d.selected ? Color.rgb(238, 242, 255) : Color.rgb(248, 250, 252), 13,
                        d.online ? Color.rgb(167, 243, 208) : Color.rgb(234, 236, 240)));
                CheckBox check = new CheckBox(this);
                check.setChecked(d.selected);
                check.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    d.selected = ((CheckBox)v).isChecked(); saveRender();
                }});
                item.addView(check, new LinearLayout.LayoutParams(dp(44), -2));
                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.addView(text(d.name, 15, Color.rgb(16, 24, 40), true));
                info.addView(text((d.online ? "● متصل الآن" : "○ محفوظ") + "  •  " + d.ip,
                        12, d.online ? Color.rgb(5, 122, 85) : Color.rgb(102, 112, 133), false));
                item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                Button remove = smallButton("حذف", Color.rgb(254, 242, 242), Color.rgb(185, 28, 28));
                remove.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    synchronized (devices) { devices.remove(d); } saveRender();
                }});
                item.addView(remove);
                deviceList.addView(item, bottom(7));
            }
        }
        sendButton.setEnabled(selected > 0 && !files.isEmpty());
    }

    private void chooseFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "اختر الملفات"), PICK_FILES);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return;
        Set<String> seen = new LinkedHashSet<String>();
        for (Uri u : files) seen.add(u.toString());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) addUri(clip.getItemAt(i).getUri(), seen);
        else addUri(data.getData(), seen);
        renderFiles();
    }

    private void addUri(Uri uri, Set<String> seen) {
        if (uri == null || !seen.add(uri.toString())) return;
        files.add(uri);
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
    }

    private void renderFiles() {
        fileSummary.setText(files.isEmpty() ? "لم يتم اختيار ملفات" : "تم اختيار " + files.size() + " ملف");
        int selected = 0;
        synchronized (devices) { for (DeviceRecord d : devices) if (d.selected) selected++; }
        sendButton.setEnabled(selected > 0 && !files.isEmpty());
    }

    private void sendFiles() {
        final ArrayList<DeviceRecord> targets = new ArrayList<DeviceRecord>();
        synchronized (devices) { for (DeviceRecord d : devices) if (d.selected) targets.add(d); }
        if (targets.isEmpty() || files.isEmpty()) { toast("حدد جهازًا واختر الملفات"); return; }
        sendButton.setEnabled(false);
        progress.setProgress(0);
        sender.send(targets, new ArrayList<Uri>(files), new FileSender.Listener() {
            @Override public void onProgress(final int done, final int total, final int ok, final int failed) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    progress.setProgress(done * 100 / Math.max(1, total));
                    transferState.setText("اكتمل " + done + "/" + total + " — نجح " + ok + "، فشل " + failed);
                }});
            }
            @Override public void onLog(String message) { }
            @Override public void onDone(final int ok, final int failed) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    sendButton.setEnabled(true);
                    if (failed == 0) toast("تم الإرسال بنجاح");
                }});
            }
        });
    }

    private void addManual() {
        String ip = manualIp.getText().toString().trim();
        String name = manualName.getText().toString().trim();
        if (!LocalDiscovery.isIpv4(ip)) { toast("عنوان IP غير صحيح"); return; }
        if (name.length() == 0) name = "جهاز " + ip;
        synchronized (devices) { devices.add(0, new DeviceRecord("", name, ip, "device", true)); }
        manualIp.setText(""); manualName.setText(""); saveRender();
    }

    private int onlineCount() {
        int count = 0; synchronized (devices) { for (DeviceRecord d : devices) if (d.online) count++; } return count;
    }
    private void saveRender() { synchronized (devices) { store.save(devices); } renderDevices(); }
    private LinearLayout card(int color) { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(15),dp(15),dp(15),dp(15)); l.setBackground(rounded(color,19)); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout title(String a,String b) { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.addView(text(a,19,Color.rgb(16,24,40),true)); TextView s=text(b,12,Color.rgb(102,112,133),false); s.setPadding(0,dp(3),0,dp(10)); l.addView(s); return l; }
    private TextView info(String value) { TextView t=text(value,13,Color.rgb(71,84,103),false); t.setPadding(dp(10),dp(10),dp(10),dp(10)); t.setBackground(rounded(Color.rgb(248,250,252),12)); return t; }
    private EditText input(String hint) { EditText e=new EditText(this); e.setHint(hint); e.setSingleLine(true); e.setTextSize(15); e.setPadding(dp(12),dp(10),dp(12),dp(10)); e.setBackground(rounded(Color.WHITE,12,Color.rgb(208,213,221))); e.setLayoutParams(bottom(8)); return e; }
    private Button button(String label,int bg,int fg) { Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(fg); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setBackground(rounded(bg,13)); b.setMinHeight(dp(46)); b.setLayoutParams(bottom(8)); return b; }
    private Button smallButton(String label,int bg,int fg) { Button b=button(label,bg,fg); b.setTextSize(12); b.setMinHeight(dp(38)); b.setLayoutParams(new LinearLayout.LayoutParams(-2,-2)); return b; }
    private TextView text(String value,int size,int color,boolean bold) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.RIGHT); if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t; }
    private GradientDrawable rounded(int color,int radius) { return rounded(color,radius,0); }
    private GradientDrawable rounded(int color,int radius,int stroke) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if(stroke!=0)d.setStroke(dp(1),stroke); return d; }
    private LinearLayout.LayoutParams bottom(int v) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(v)); return p; }
    private LinearLayout.LayoutParams top(int v) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(v),0,0); return p; }
    private LinearLayout.LayoutParams weight(int w,int margin) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,w); p.setMargins(0,0,dp(margin),0); return p; }
    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    private void toast(String v) { Toast.makeText(this,v,Toast.LENGTH_SHORT).show(); }
    private void requestStoragePermission() { if(Build.VERSION.SDK_INT>=23 && Build.VERSION.SDK_INT<=32 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},5); }

    @Override protected void onDestroy() {
        if(scanner!=null)scanner.shutdown(); if(receiver!=null)receiver.shutdown(); if(sender!=null)sender.shutdown(); super.onDestroy();
    }
}
