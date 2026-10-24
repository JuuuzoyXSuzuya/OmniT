package mod.omnit;

import android.app.Application;
import android.os.Process;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Мод подмены региона. Статические методы ниже подставляет патчер вместо вызовов
 * TelephonyManager / Locale / SubscriptionInfo внутри оригинального приложения:
 * первым аргументом идёт бывший приёмник вызова.
 *
 * Выбранный регион хранится в файле files/omnit_region.txt внутри данных приложения
 * и меняется из самого приложения через кнопку (см. RegionUi).
 *
 * Весь код обёрнут в try/catch: мод никогда не должен ронять приложение.
 */
public final class RegionHook {

    private static final String FILE_NAME = "omnit_region.txt";

    private static volatile boolean loaded;
    private static volatile boolean uiInstalled;
    private static volatile RegionData.Entry current; // null = подмена выключена
    private static volatile File file;

    private RegionHook() {}

    // ------------------------------------------------------------------
    // Хуки (имена и сигнатуры должны совпадать с таблицей в патчере)
    // ------------------------------------------------------------------

    public static String simCountryIso(TelephonyManager tm) {
        RegionData.Entry e = active();
        return e != null ? e.iso.toLowerCase(Locale.ROOT) : tm.getSimCountryIso();
    }

    public static String networkCountryIso(TelephonyManager tm) {
        RegionData.Entry e = active();
        return e != null ? e.iso.toLowerCase(Locale.ROOT) : tm.getNetworkCountryIso();
    }

    public static String networkCountryIsoSlot(TelephonyManager tm, int slot) {
        RegionData.Entry e = active();
        return e != null ? e.iso.toLowerCase(Locale.ROOT) : tm.getNetworkCountryIso(slot);
    }

    public static String simOperator(TelephonyManager tm) {
        RegionData.Entry e = active();
        return e != null ? e.mcc + e.mnc : tm.getSimOperator();
    }

    public static String networkOperator(TelephonyManager tm) {
        RegionData.Entry e = active();
        return e != null ? e.mcc + e.mnc : tm.getNetworkOperator();
    }

    public static String simOperatorName(TelephonyManager tm) {
        RegionData.Entry e = active();
        return e != null ? e.carrier : tm.getSimOperatorName();
    }

    public static String networkOperatorName(TelephonyManager tm) {
        RegionData.Entry e = active();
        return e != null ? e.carrier : tm.getNetworkOperatorName();
    }

    public static String subscriptionCountryIso(SubscriptionInfo si) {
        RegionData.Entry e = active();
        return e != null ? e.iso.toLowerCase(Locale.ROOT) : si.getCountryIso();
    }

    /** Подменяем страну только у текущей локали по умолчанию, остальные Locale не трогаем. */
    public static String localeCountry(Locale l) {
        RegionData.Entry e = active();
        if (e != null && l != null && l.equals(Locale.getDefault())) {
            return e.iso;
        }
        return l.getCountry();
    }

    // ------------------------------------------------------------------
    // Состояние
    // ------------------------------------------------------------------

    private static RegionData.Entry active() {
        try {
            if (!loaded) load();
            if (!uiInstalled) installUi();
        } catch (Throwable ignored) {
        }
        return current;
    }

    static RegionData.Entry getCurrent() {
        return current;
    }

    static synchronized void setRegion(String iso) {
        try {
            File f = file != null ? file : resolveFile();
            if (f == null) return;
            file = f;
            RegionData.Entry e = RegionData.find(iso);
            if (e == null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else {
                FileOutputStream out = new FileOutputStream(f, false);
                try {
                    out.write(e.iso.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }
            current = e;
            loaded = true;
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void load() {
        if (loaded) return;
        try {
            File f = resolveFile();
            if (f == null) return; // процесс ещё не готов, попробуем при следующем вызове
            file = f;
            String iso = null;
            if (f.isFile()) {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), "UTF-8"));
                try {
                    iso = r.readLine();
                } finally {
                    r.close();
                }
            }
            current = RegionData.find(iso);
            loaded = true;
        } catch (Throwable ignored) {
        }
    }

    private static File resolveFile() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentPackageName");
            String pkg = (String) m.invoke(null);
            if (pkg == null) return null;
            int userId = Process.myUid() / 100000;
            File dir = new File("/data/user/" + userId + "/" + pkg + "/files");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                dir = new File("/data/data/" + pkg + "/files");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            return new File(dir, FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized void installUi() {
        if (uiInstalled) return;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Application app = (Application) m.invoke(null);
            if (app == null) return; // попробуем позже
            RegionUi.install(app);
            uiInstalled = true;
        } catch (Throwable ignored) {
        }
    }
}
