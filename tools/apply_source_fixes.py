from pathlib import Path

path = Path("app/src/main/java/com/explapp/sendvialocalnet/MainActivity.java")
text = path.read_text(encoding="utf-8")

old_status = "        getWindow().setStatusBarColor(Color.rgb(38, 45, 94));"
new_status = "        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(Color.rgb(38, 45, 94));"
if old_status not in text:
    raise SystemExit("Status bar call pattern was not found")
text = text.replace(old_status, new_status, 1)

old_section = '''    private TextView sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 19, Color.rgb(16, 24, 40), true);
        TextView s = text(subtitle, 12, Color.rgb(102, 112, 133), false);
        s.setPadding(0, dp(3), 0, dp(12));
        box.addView(t);
        box.addView(s);
        return wrapAsTextContainer(box);
    }

    private TextView wrapAsTextContainer(final LinearLayout content) {
        TextView holder = new TextView(this) {
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                if (content.getParent() == null && getParent() instanceof LinearLayout) {
                    LinearLayout parent = (LinearLayout)getParent();
                    int index = parent.indexOfChild(this);
                    parent.removeView(this);
                    parent.addView(content, index);
                }
            }
        };
        holder.setVisibility(View.GONE);
        return holder;
    }
'''
new_section = '''    private LinearLayout sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 19, Color.rgb(16, 24, 40), true);
        TextView s = text(subtitle, 12, Color.rgb(102, 112, 133), false);
        s.setPadding(0, dp(3), 0, dp(12));
        box.addView(t);
        box.addView(s);
        return box;
    }
'''
if old_section not in text:
    raise SystemExit("Section title pattern was not found")
text = text.replace(old_section, new_section, 1)

path.write_text(text, encoding="utf-8")
print("Android source compatibility fixes applied")
