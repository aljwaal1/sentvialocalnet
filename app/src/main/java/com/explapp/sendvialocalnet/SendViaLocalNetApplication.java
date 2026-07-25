package com.explapp.sendvialocalnet;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class SendViaLocalNetApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final String IP_TAG = "svln-current-ip";

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityResumed(final Activity activity) {
        activity.getWindow().getDecorView().post(new Runnable() {
            @Override public void run() { attachIpChip(activity); }
        });
    }

    private void attachIpChip(final Activity activity) {
        View rootView = activity.findViewById(android.R.id.content);
        if (!(rootView instanceof ViewGroup)) return;
        final ViewGroup root = (ViewGroup) rootView;
        View old = root.findViewWithTag(IP_TAG);
        if (old instanceof TextView) {
            updateText((TextView) old);
            return;
        }

        final TextView chip = new TextView(activity);
        chip.setTag(IP_TAG);
        chip.setTextSize(11);
        chip.setTextColor(Color.rgb(55, 48, 163));
        chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(activity, 11), dp(activity, 7), dp(activity, 11), dp(activity, 7));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(242, 238, 242, 255));
        background.setCornerRadius(dp(activity, 18));
        background.setStroke(dp(activity, 1), Color.rgb(199, 210, 254));
        chip.setBackground(background);
        if (Build.VERSION.SDK_INT >= 21) chip.setElevation(8f);
        updateText(chip);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String value = LocalDiscovery.getBestLocalIp() + ":5051";
                ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("SendViaLocalNet IP", value));
                Toast.makeText(activity, "تم نسخ IP الحالي", Toast.LENGTH_SHORT).show();
            }
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT
        );
        params.leftMargin = dp(activity, 10);
        params.topMargin = dp(activity, 8);
        root.addView(chip, params);

        chip.postDelayed(new Runnable() {
            @Override public void run() {
                if (!chip.isAttachedToWindow()) return;
                updateText(chip);
                chip.postDelayed(this, 5000);
            }
        }, 5000);
    }

    private static void updateText(TextView chip) {
        String ip = LocalDiscovery.getBestLocalIp();
        chip.setText(LocalDiscovery.isIpv4(ip) ? "IP: " + ip + ":5051  •  اضغط للنسخ" : "Wi‑Fi غير متصل");
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
