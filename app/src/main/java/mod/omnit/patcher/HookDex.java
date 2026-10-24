package mod.omnit.patcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Собирает dex с классами мода (mod.omnit.*) из собственного APK патчера.
 * Модуль :hook подключён к приложению как обычная зависимость, поэтому его классы уже
 * лежат в dex патчера — остаётся вынуть их в отдельный dex, который вшивается в TikTok.
 *
 * Берутся только классы пакета mod.omnit без вложенных пакетов
 * (mod.omnit.patcher.* — это сам патчер, в TikTok он не нужен).
 */
final class HookDex {
    private HookDex() {}

    private static final Pattern HOOK_TYPE = Pattern.compile("Lmod/omnit/[A-Za-z0-9_$]+;");
    private static final Pattern DEX_NAME = Pattern.compile("classes\\d*\\.dex");

    static byte[] build(Context ctx, File tmpDir) throws IOException {
        ApplicationInfo ai = ctx.getApplicationInfo();
        List<String> apks = new ArrayList<String>();
        apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) Collections.addAll(apks, ai.splitSourceDirs);

        List<ClassDef> classes = new ArrayList<ClassDef>();
        Opcodes opcodes = null;
        for (String path : apks) {
            ZipFile zf = new ZipFile(path);
            try {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (!DEX_NAME.matcher(ze.getName()).matches()) continue;
                    byte[] b = readAll(zf.getInputStream(ze));
                    DexBackedDexFile d = new DexBackedDexFile(null, b);
                    if (opcodes == null) opcodes = d.getOpcodes();
                    for (ClassDef c : d.getClasses()) {
                        if (HOOK_TYPE.matcher(c.getType()).matches()) classes.add(c);
                    }
                }
            } finally {
                zf.close();
            }
        }
        if (classes.isEmpty()) {
            throw new IOException("В патчере не найдены классы мода (mod.omnit.*). "
                    + "Проверьте, что сборка не минифицирована (minifyEnabled false).");
        }
        return DexPatcher.write(new ImmutableDexFile(opcodes, classes), tmpDir);
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
