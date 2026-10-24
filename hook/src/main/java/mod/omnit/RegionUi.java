package mod.omnit;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Маленькая плавающая кнопка внутри приложения для выбора региона. */
final class RegionUi implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "omnit_region_button";

    static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new RegionUi());
    }

    private static String flag(String iso) {
        int base = 0x1F1E6;
        char a = iso.charAt(0);
        char b = iso.charAt(1);
        return new String(Character.toChars(base + (a - 'A')))
                + new String(Character.toChars(base + (b - 'A')));
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    private static String buttonLabel() {
        RegionData.Entry e = RegionHook.getCurrent();
        return e != null ? flag(e.iso) : "🌐";
    }

    private void attach(final Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decor;

            View existing = root.findViewWithTag(TAG);
            if (existing instanceof TextView) {
                ((TextView) existing).setText(buttonLabel());
                return;
            }

            TextView btn = new TextView(activity);
            btn.setTag(TAG);
            btn.setText(buttonLabel());
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(Color.WHITE);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x88000000);
            btn.setBackground(bg);
            btn.setAlpha(0.6f);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showPicker(activity);
                }
            });

            int size = dp(activity, 36);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size,
                    Gravity.START | Gravity.CENTER_VERTICAL);
            lp.leftMargin = dp(activity, 4);
            root.addView(btn, lp);
        } catch (Throwable ignored) {
        }
    }

    private void showPicker(final Activity activity) {
        try {
            final RegionData.Entry[] all = RegionData.ALL;
            RegionData.Entry cur = RegionHook.getCurrent();

            String[] items = new String[all.length + 1];
            items[0] = (cur == null ? "✓ " : "") + "🌐  Реальный регион (подмена выкл.)";
            for (int i = 0; i < all.length; i++) {
                RegionData.Entry e = all[i];
                boolean sel = cur != null && cur.iso.equals(e.iso);
                items[i + 1] = (sel ? "✓ " : "") + flag(e.iso) + "  " + e.name + " (" + e.iso + ")";
            }

            new AlertDialog.Builder(activity)
                    .setTitle("OmniT — выбор региона")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            RegionHook.setRegion(which == 0 ? null : all[which - 1].iso);
                            askRestart(activity);
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private void askRestart(final Activity activity) {
        try {
            RegionData.Entry e = RegionHook.getCurrent();
            String msg = e == null ? "Подмена региона выключена." : "Регион: " + e.name + " (" + e.iso + ").";
            new AlertDialog.Builder(activity)
                    .setTitle("Нужен перезапуск")
                    .setMessage(msg + "\nЗакройте приложение и откройте снова, чтобы изменения вступили в силу.")
                    .setPositiveButton("Закрыть приложение", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            activity.finishAffinity();
                            Process.killProcess(Process.myPid());
                        }
                    })
                    .setNegativeButton("Позже", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Toast.makeText(activity, "Применится после перезапуска", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    // ---- ActivityLifecycleCallbacks ----
    @Override public void onActivityResumed(Activity activity) { attach(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
