package mod.omnit.patcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final String ACTION_STATUS = "mod.omnit.patcher.INSTALL_STATUS";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Uri picked;
    private ApkPatcher.Result result;
    private volatile boolean busy;

    private TextView fileLabel;
    private TextView logView;
    private ScrollView logScroll;
    private Button pickBtn;
    private Button patchBtn;
    private Button installBtn;
    private Button removeBtn;
    private ProgressBar bar;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("OmniT Patcher");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Выберите APK оригинального TikTok (или набор .apks/.xapk/.apkm). "
                + "Патчер вошьёт мод подмены региона и подпишет приложение. "
                + "Регион потом выбирается в самом TikTok кнопкой слева на экране.");
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        pickBtn = button("1. Выбрать файл TikTok", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(); }
        });
        root.addView(pickBtn);

        fileLabel = new TextView(this);
        fileLabel.setText("Файл не выбран");
        fileLabel.setPadding(0, dp(4), 0, dp(8));
        root.addView(fileLabel);

        patchBtn = button("2. Пропатчить", new View.OnClickListener() {
            @Override public void onClick(View v) { startPatch(); }
        });
        patchBtn.setEnabled(false);
        root.addView(patchBtn);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.GONE);
        root.addView(bar);

        installBtn = button("3. Установить", new View.OnClickListener() {
            @Override public void onClick(View v) { install(); }
        });
        installBtn.setEnabled(false);
        root.addView(installBtn);

        removeBtn = button("Удалить оригинальный TikTok", new View.OnClickListener() {
            @Override public void onClick(View v) { removeOriginal(); }
        });
        removeBtn.setEnabled(false);
        root.addView(removeBtn);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(12), 0, 0);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        IntentFilter f = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {
        }
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void log(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                logView.append(s + "\n");
                logScroll.post(new Runnable() {
                    @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    // ------------------------------------------------------------------ выбор файла

    private void pick() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        picked = data.getData();
        result = null;
        installBtn.setEnabled(false);
        removeBtn.setEnabled(false);
        String name = String.valueOf(picked.getLastPathSegment());
        Cursor c = null;
        try {
            c = getContentResolver().query(picked, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        fileLabel.setText(name);
        patchBtn.setEnabled(true);
    }

    // ------------------------------------------------------------------ патч

    private void startPatch() {
        if (busy || picked == null) return;
        busy = true;
        patchBtn.setEnabled(false);
        pickBtn.setEnabled(false);
        installBtn.setEnabled(false);
        removeBtn.setEnabled(false);
        bar.setVisibility(View.VISIBLE);
        logView.setText("");
        final Uri src = picked;

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File work = new File(getCacheDir(), "work");
                    ApkPatcher.deleteRecursive(work);
                    //noinspection ResultOfMethodCallIgnored
                    work.mkdirs();

                    log("Копирую файл…");
                    File in = new File(work, "input.bin");
                    InputStream is = getContentResolver().openInputStream(src);
                    if (is == null) throw new IllegalStateException("Не удалось открыть файл");
                    ApkPatcher.copy(is, in);

                    File ext = getExternalFilesDir(null);
                    File outDir = new File(ext != null ? ext : getFilesDir(), "patched");
                    ApkPatcher.deleteRecursive(outDir);
                    //noinspection ResultOfMethodCallIgnored
                    outDir.mkdirs();

                    final ApkPatcher.Result r = ApkPatcher.run(MainActivity.this, in, work, outDir,
                            new ApkPatcher.Log() {
                                @Override public void log(String msg) { MainActivity.this.log(msg); }
                            });
                    ApkPatcher.deleteRecursive(work);

                    log("");
                    log("Готово. Перехвачено вызовов: " + r.hooks);
                    log("Пакет: " + r.packageName + (r.versionName != null ? " " + r.versionName : ""));
                    for (File f : r.apks) log("→ " + f.getAbsolutePath());
                    log("Если установка скажет «конфликт»/«не совпадает подпись», нажмите "
                            + "«Удалить оригинальный TikTok» и установите снова.");
                    ui.post(new Runnable() {
                        @Override public void run() {
                            result = r;
                            installBtn.setEnabled(true);
                            removeBtn.setEnabled(r.packageName != null);
                        }
                    });
                } catch (Throwable t) {
                    log("");
                    log("✖ Ошибка: " + t);
                    Throwable c = t.getCause();
                    if (c != null) log("   причина: " + c);
                } finally {
                    busy = false;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            bar.setVisibility(View.GONE);
                            pickBtn.setEnabled(true);
                            patchBtn.setEnabled(picked != null);
                        }
                    });
                }
            }
        }, "omnit-patch").start();
    }

    // ------------------------------------------------------------------ установка

    private void install() {
        if (result == null || result.apks.isEmpty()) return;
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(id);
            try {
                List<File> files = new ArrayList<File>(result.apks);
                for (File f : files) {
                    InputStream in = new FileInputStream(f);
                    OutputStream out = session.openWrite(f.getName(), 0, f.length());
                    try {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        session.fsync(out);
                    } finally {
                        out.close();
                        in.close();
                    }
                }
                Intent cb = new Intent(ACTION_STATUS).setPackage(getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getBroadcast(this, id, cb, flags);
                session.commit(pend.getIntentSender());
                log("Запрос на установку отправлен…");
            } catch (Exception e) {
                session.abandon();
                throw e;
            } finally {
                session.close();
            }
        } catch (Exception e) {
            log("✖ Установка: " + e);
        }
    }

    private void removeOriginal() {
        if (result == null || result.packageName == null) return;
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + result.packageName));
        try {
            startActivity(i);
        } catch (Exception e) {
            log("✖ Не удалось открыть удаление: " + e);
        }
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @SuppressWarnings("deprecation")
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                log("✔ Установлено. Откройте TikTok: слева на экране будет кнопка выбора региона.");
            } else {
                log("✖ Установка не удалась (код " + st + "): " + msg);
                if (msg != null && (msg.contains("INCOMPATIBLE") || msg.contains("signatures"))) {
                    log("   Подпись отличается от установленного TikTok — удалите оригинал кнопкой выше.");
                }
            }
        }
    };
}
