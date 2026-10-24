package mod.omnit.patcher;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Весь процесс: вход (APK или набор APK) -> патч dex -> выравнивание -> подпись. */
final class ApkPatcher {
    private ApkPatcher() {}

    interface Log {
        void log(String msg);
    }

    static final class Result {
        final List<File> apks = new ArrayList<File>();
        String packageName;
        String versionName;
        int hooks;
    }

    private static final Pattern DEX = Pattern.compile("classes(\\d*)\\.dex");

    // ------------------------------------------------------------------ вход

    static Result run(Context ctx, File input, File workDir, File outDir, Log log) throws Exception {
        List<File> apks = new ArrayList<File>();
        File base = unpackInput(input, workDir, apks, log);

        Result res = new Result();

        log.log("Собираю hook-dex из патчера…");
        byte[] hookDex = HookDex.build(ctx, workDir);
        log.log("Hook-dex: " + (hookDex.length / 1024) + " КБ");

        ApkSign signer = ApkSign.create();
        log.log("Ключ подписи: " + signer.describe());

        // 1) base: патчим dex
        File baseAligned = new File(workDir, "base.aligned.apk");
        log.log("Патчу " + base.getName() + " (это самый долгий шаг)…");
        res.hooks = patchZip(base, baseAligned, true, hookDex, workDir, log);

        File baseOut = new File(outDir, apks.size() == 1 ? "tiktok-omnit.apk" : "base.apk");
        log.log("Подписываю base…");
        signer.sign(baseAligned, baseOut);
        verify(baseOut, log);
        res.apks.add(baseOut);
        //noinspection ResultOfMethodCallIgnored
        baseAligned.delete();

        // 2) остальные части (config.*.apk): без патча dex, но с той же подписью
        for (File split : apks) {
            if (split.equals(base)) continue;
            File aligned = new File(workDir, split.getName() + ".aligned");
            log.log("Подписываю " + split.getName() + "…");
            patchZip(split, aligned, false, null, workDir, log);
            File out = new File(outDir, split.getName());
            signer.sign(aligned, out);
            res.apks.add(out);
            //noinspection ResultOfMethodCallIgnored
            aligned.delete();
        }

        PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(baseOut.getAbsolutePath(), 0);
        if (pi != null) {
            res.packageName = pi.packageName;
            res.versionName = pi.versionName;
        }
        return res;
    }

    /**
     * Определяет формат входа. Обычный APK -> один файл. Набор (.apks/.xapk/.apkm) -> распаковывает
     * все вложенные .apk и выбирает основной.
     */
    private static File unpackInput(File input, File workDir, List<File> out, Log log) throws IOException {
        ZipFile zf = new ZipFile(input);
        try {
            if (zf.getEntry("AndroidManifest.xml") != null) {
                log.log("Формат: одиночный APK");
                out.add(input);
                return input;
            }
            log.log("Формат: набор APK (bundle)");
            File dir = new File(workDir, "bundle");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File base = null;
            File biggest = null;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName();
                if (ze.isDirectory() || !n.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                if (n.indexOf('/') >= 0) n = n.substring(n.lastIndexOf('/') + 1);
                File f = new File(dir, n);
                copy(zf.getInputStream(ze), f);
                out.add(f);
                if (n.equals("base.apk")) base = f;
                if (biggest == null || f.length() > biggest.length()) biggest = f;
            }
            if (out.isEmpty()) throw new IOException("В архиве нет .apk файлов");
            if (base == null) base = biggest;
            log.log("Основной файл: " + base.getName() + ", частей: " + out.size());
            return base;
        } finally {
            zf.close();
        }
    }

    // ------------------------------------------------------------------ патч одного APK

    /**
     * Копирует APK, выравнивая записи как zipalign и убирая старую v1-подпись.
     * При injectHook ещё и переписывает dex и добавляет dex с классами мода.
     *
     * @return число перехваченных вызовов
     */
    static int patchZip(File in, File out, boolean injectHook, byte[] hookDex, File tmpDir, Log log) throws Exception {
        int totalHooks = 0;
        int maxDex = 1;
        int failed = 0;

        RandomAccessFile raf = new RandomAccessFile(in, "r");
        ZipFile zf = new ZipFile(in);
        ZipIo.Writer w = new ZipIo.Writer(out);
        try {
            List<ZipIo.Entry> entries = ZipIo.readDirectory(raf);
            for (ZipIo.Entry e : entries) {
                String n = e.name;
                if (isOldSignature(n)) continue;

                Matcher m = DEX.matcher(n);
                if (m.matches()) {
                    int idx = m.group(1).length() == 0 ? 1 : Integer.parseInt(m.group(1));
                    if (idx > maxDex) maxDex = idx;
                    if (injectHook) {
                        DexPatcher.Result r = null;
                        try {
                            byte[] bytes = HookDex.readAll(zf.getInputStream(zf.getEntry(n)));
                            r = DexPatcher.patch(bytes, tmpDir);
                        } catch (OutOfMemoryError oom) {
                            throw new IOException("Не хватило памяти на " + n
                                    + ". Закройте другие приложения и повторите.");
                        } catch (Exception ex) {
                            failed++;
                            log.log("⚠ " + n + ": не удалось пропатчить (" + ex + "), оставляю как есть");
                        }
                        if (r != null) {
                            log.log(n + ": перехвачено вызовов — " + r.hooks);
                            totalHooks += r.hooks;
                            w.addBytes(e.nameBytes, r.data, e.method == ZipIo.STORED);
                            continue;
                        }
                    }
                }
                w.copyRaw(raf, e, alignFor(e));
            }

            if (injectHook) {
                if (totalHooks == 0) {
                    throw new IOException("Не найдено ни одного вызова для перехвата"
                            + (failed > 0 ? " (часть dex не удалось обработать)" : "")
                            + ". Это точно TikTok?");
                }
                String hookName = "classes" + (maxDex + 1) + ".dex";
                w.addBytes(hookName.getBytes("UTF-8"), hookDex, false);
                log.log("Добавлен " + hookName + " с модом");
            }
            w.finish();
        } finally {
            try {
                w.close();
            } catch (IOException ignored) {
            }
            zf.close();
            raf.close();
        }
        return totalHooks;
    }

    private static int alignFor(ZipIo.Entry e) {
        if (e.method != ZipIo.STORED) return 1;
        return e.name.endsWith(".so") ? 16384 : 4;
    }

    private static boolean isOldSignature(String n) {
        if (!n.startsWith("META-INF/")) return false;
        String rest = n.substring(9);
        if (rest.indexOf('/') >= 0) return false;
        String up = rest.toUpperCase(Locale.ROOT);
        return up.equals("MANIFEST.MF") || up.endsWith(".SF") || up.endsWith(".RSA")
                || up.endsWith(".DSA") || up.endsWith(".EC");
    }

    private static void verify(File apk, Log log) {
        try {
            com.android.apksig.ApkVerifier.Result r =
                    new com.android.apksig.ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(24).build().verify();
            if (r.isVerified()) {
                log.log("Подпись проверена ✔ (v2" + (r.isVerifiedUsingV3Scheme() ? "+v3" : "") + ")");
            } else {
                log.log("⚠ Проверка подписи: " + r.getErrors());
            }
        } catch (Exception e) {
            log.log("⚠ Не удалось проверить подпись: " + e);
        }
    }

    // ------------------------------------------------------------------ утилиты

    static void copy(InputStream in, File dst) throws IOException {
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            out.close();
            in.close();
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
