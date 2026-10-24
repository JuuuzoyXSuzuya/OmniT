package com.example.regionpatcher;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.util.zip.ZipFile;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK_APK = 501;
    private Uri apkUri;
    private TextView fileInfo, status;
    private Spinner regionSpinner;
    private Button analyzeButton, exportButton;
    private String[] regions = {
        "Казахстан", "Кыргызстан", "США", "Беларусь", "Украина"
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(17,24,39));
        getWindow().setNavigationBarColor(Color.rgb(17,24,39));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(245,247,250));

        TextView title = new TextView(this);
        title.setText("TikTok Region Patcher");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(17,24,39));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("APK analyzer • Region profile prototype");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams subLp = lp(-1, -2);
        subLp.topMargin = dp(6);
        root.addView(subtitle, subLp);

        TextView warning = new TextView(this);
        warning.setText("Важно: выбор страны здесь сохраняется как профиль. Он сам по себе не меняет серверный регион TikTok.");
        warning.setTextColor(Color.rgb(120,65,0));
        warning.setTextSize(13);
        warning.setPadding(dp(12), dp(12), dp(12), dp(12));
        warning.setBackground(round(Color.rgb(255,243,220), dp(12)));
        LinearLayout.LayoutParams wlp = lp(-1,-2);
        wlp.topMargin = dp(18);
        root.addView(warning, wlp);

        Button pick = new Button(this);
        pick.setText("1. Выбрать APK TikTok");
        pick.setAllCaps(false);
        LinearLayout.LayoutParams pickLp = lp(-1,-2);
        pickLp.topMargin = dp(18);
        root.addView(pick, pickLp);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/vnd.android.package-archive");
            startActivityForResult(i, PICK_APK);
        });

        fileInfo = new TextView(this);
        fileInfo.setText("APK не выбран");
        fileInfo.setTextColor(Color.rgb(55,65,81));
        fileInfo.setTextSize(14);
        fileInfo.setPadding(dp(12),dp(12),dp(12),dp(12));
        fileInfo.setBackground(round(Color.WHITE, dp(10)));
        LinearLayout.LayoutParams infoLp = lp(-1,-2);
        infoLp.topMargin = dp(10);
        root.addView(fileInfo, infoLp);

        TextView regionLabel = new TextView(this);
        regionLabel.setText("2. Региональный профиль");
        regionLabel.setTextColor(Color.rgb(17,24,39));
        regionLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        regionLabel.setTextSize(16);
        LinearLayout.LayoutParams labelLp = lp(-1,-2);
        labelLp.topMargin = dp(22);
        root.addView(regionLabel, labelLp);

        regionSpinner = new Spinner(this);
        regionSpinner.setAdapter(new ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, regions));
        LinearLayout.LayoutParams spinnerLp = lp(-1,-2);
        spinnerLp.topMargin = dp(8);
        root.addView(regionSpinner, spinnerLp);

        analyzeButton = new Button(this);
        analyzeButton.setText("3. Проверить APK");
        analyzeButton.setAllCaps(false);
        analyzeButton.setEnabled(false);
        LinearLayout.LayoutParams aLp = lp(-1,-2);
        aLp.topMargin = dp(16);
        root.addView(analyzeButton, aLp);
        analyzeButton.setOnClickListener(v -> analyzeApk());

        exportButton = new Button(this);
        exportButton.setText("4. Экспортировать отчёт");
        exportButton.setAllCaps(false);
        exportButton.setEnabled(false);
        LinearLayout.LayoutParams eLp = lp(-1,-2);
        eLp.topMargin = dp(8);
        root.addView(exportButton, eLp);
        exportButton.setOnClickListener(v -> createReport());

        status = new TextView(this);
        status.setText("Готово к работе.");
        status.setTextColor(Color.rgb(31,41,55));
        status.setTextSize(14);
        status.setPadding(0,dp(18),0,0);
        root.addView(status, lp(-1,-2));

        setContentView(root);
    }

    private void analyzeApk() {
        if (apkUri == null) return;
        status.setText("Проверяю APK…");
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            report.append("TikTok Region Patcher — отчёт\n\n");
            report.append("Файл: ").append(getName(apkUri)).append("\n");
            report.append("Выбранный профиль: ").append(regions[regionSpinner.getSelectedItemPosition()]).append("\n\n");
            int count = 0;
            List<String> dex = new ArrayList<>();
            List<String> nativeLibs = new ArrayList<>();
            try (InputStream in = getContentResolver().openInputStream(apkUri)) {
                // APK is a ZIP container. Copy to cache so ZipFile can inspect large files without loading into RAM.
                java.io.File temp = new java.io.File(getCacheDir(), "inspect.apk");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                    byte[] buf = new byte[1024 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                try (ZipFile zip = new ZipFile(temp)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        String name = e.getName();
                        count++;
                        if (name.matches("classes(\\d*)\\.dex")) dex.add(name);
                        if (name.startsWith("lib/") && name.endsWith(".so")) nativeLibs.add(name);
                    }
                    report.append("ZIP entries: ").append(count).append("\n");
                    report.append("DEX files: ").append(dex.isEmpty() ? "не найдены" : String.join(", ", dex)).append("\n");
                    report.append("Native .so files: ").append(nativeLibs.size()).append("\n");
                    boolean arm64 = false;
                    for (String lib : nativeLibs) {
                        if (lib.startsWith("lib/arm64-v8a/")) arm64 = true;
                    }
                    report.append("ARM64 libraries: ").append(arm64 ? "есть" : "не обнаружены").append("\n");
                    report.append("\nЭто структурный анализ, не изменение APK.\n");
                    report.append("Автоматический патч региона пока не применён.\n");
                }
                // Keep report in app-private cache; clean temporary APK copy.
                temp.delete();
            } catch (Exception ex) {
                report.append("Ошибка анализа: ").append(ex.getClass().getSimpleName())
                      .append(": ").append(ex.getMessage()).append("\n");
            }
            String result = report.toString();
            runOnUiThread(() -> {
                status.setText(result);
                exportButton.setEnabled(true);
            });
        }).start();
    }

    private void createReport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "tiktok-region-patcher-report.txt");
        startActivityForResult(i, 502);
    }

    private String lastReport() { return status.getText().toString(); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_APK) {
            apkUri = uri;
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fileInfo.setText("Выбран файл:\n" + getName(uri));
            analyzeButton.setEnabled(true);
            status.setText("APK выбран. Нажми «Проверить APK».");
        } else if (requestCode == 502) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(lastReport().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                status.setText("Отчёт экспортирован.");
            } catch (Exception e) {
                status.setText("Не удалось сохранить отчёт: " + e.getMessage());
            }
        }
    }

    private String getName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name == null ? "unknown.apk" : name;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }
}
