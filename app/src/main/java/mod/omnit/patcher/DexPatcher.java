package mod.omnit.patcher;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Переписывает вызовы системных методов (SIM/сеть/локаль) в dex оригинального приложения
 * на статические методы {@code mod.omnit.RegionHook}.
 *
 * invoke-virtual {obj, ...}, Owner;->method(...)  ->  invoke-static {obj, ...}, RegionHook;->hook(Owner, ...)
 *
 * Число и номера регистров не меняются, формат инструкции тот же (35c/3rc), поэтому смещения
 * переходов и размеры методов остаются прежними.
 */
final class DexPatcher {
    private DexPatcher() {}

    static final String HOOK_CLASS = "Lmod/omnit/RegionHook;";

    private static final String TM = "Landroid/telephony/TelephonyManager;";
    private static final String SI = "Landroid/telephony/SubscriptionInfo;";
    private static final String LOC = "Ljava/util/Locale;";
    private static final String STR = "Ljava/lang/String;";

    private static final Map<String, ImmutableMethodReference> TARGETS = new HashMap<String, ImmutableMethodReference>();
    private static final Set<String> NAMES = new HashSet<String>();

    private static void t(String owner, String name, String[] params, String ret, String hookName) {
        List<String> hookParams = new ArrayList<String>();
        hookParams.add(owner);
        hookParams.addAll(Arrays.asList(params));
        TARGETS.put(key(owner, name, Arrays.asList(params), ret),
                new ImmutableMethodReference(HOOK_CLASS, hookName, hookParams, ret));
        NAMES.add(name);
    }

    static {
        String[] none = new String[0];
        t(TM, "getSimCountryIso", none, STR, "simCountryIso");
        t(TM, "getNetworkCountryIso", none, STR, "networkCountryIso");
        t(TM, "getNetworkCountryIso", new String[] {"I"}, STR, "networkCountryIsoSlot");
        t(TM, "getSimOperator", none, STR, "simOperator");
        t(TM, "getNetworkOperator", none, STR, "networkOperator");
        t(TM, "getSimOperatorName", none, STR, "simOperatorName");
        t(TM, "getNetworkOperatorName", none, STR, "networkOperatorName");
        t(SI, "getCountryIso", none, STR, "subscriptionCountryIso");
        t(LOC, "getCountry", none, STR, "localeCountry");
    }

    private static String key(CharSequence owner, CharSequence name, Iterable<? extends CharSequence> params, CharSequence ret) {
        StringBuilder sb = new StringBuilder(80);
        sb.append(owner).append("->").append(name).append('(');
        for (CharSequence p : params) sb.append(p);
        sb.append(')').append(ret);
        return sb.toString();
    }

    /** Возвращает ссылку на хук-метод, если инструкция — вызов одного из перехватываемых методов. */
    private static ImmutableMethodReference lookup(Instruction ins) {
        Opcode op = ins.getOpcode();
        if (op != Opcode.INVOKE_VIRTUAL && op != Opcode.INVOKE_VIRTUAL_RANGE) return null;
        if (!(ins instanceof ReferenceInstruction)) return null;
        Reference r = ((ReferenceInstruction) ins).getReference();
        if (!(r instanceof MethodReference)) return null;
        MethodReference m = (MethodReference) r;
        if (!NAMES.contains(m.getName())) return null; // быстрый отсев
        return TARGETS.get(key(m.getDefiningClass(), m.getName(), m.getParameterTypes(), m.getReturnType()));
    }

    private static Instruction transform(Instruction ins) {
        ImmutableMethodReference repl = lookup(ins);
        if (repl == null) return ins;
        if (ins instanceof Instruction35c) {
            Instruction35c i = (Instruction35c) ins;
            return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, i.getRegisterCount(),
                    i.getRegisterC(), i.getRegisterD(), i.getRegisterE(), i.getRegisterF(), i.getRegisterG(), repl);
        }
        if (ins instanceof Instruction3rc) {
            Instruction3rc i = (Instruction3rc) ins;
            return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, i.getStartRegister(), i.getRegisterCount(), repl);
        }
        return ins;
    }

    static final class Result {
        final byte[] data;
        final int hooks;

        Result(byte[] data, int hooks) {
            this.data = data;
            this.hooks = hooks;
        }
    }

    /** Считает вызовы, которые будут перехвачены. */
    private static int scan(DexFile dex) {
        int n = 0;
        for (ClassDef c : dex.getClasses()) {
            for (Method m : c.getMethods()) {
                MethodImplementation impl = m.getImplementation();
                if (impl == null) continue;
                for (Instruction i : impl.getInstructions()) {
                    if (lookup(i) != null) n++;
                }
            }
        }
        return n;
    }

    /**
     * @return null, если в этом dex нечего перехватывать (тогда его копируют как есть).
     */
    static Result patch(byte[] dexBytes, File tmpDir) throws IOException {
        DexBackedDexFile dex = new DexBackedDexFile(null, dexBytes);
        int hooks = scan(dex);
        if (hooks == 0) return null;

        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return new Rewriter<Instruction>() {
                    @Override
                    public Instruction rewrite(Instruction value) {
                        return transform(value);
                    }
                };
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(dex);
        return new Result(write(rewritten, tmpDir), hooks);
    }

    /** Сериализует DexFile в байты через временный файл. */
    static byte[] write(DexFile dex, File tmpDir) throws IOException {
        File tmp = File.createTempFile("dex", ".tmp", tmpDir);
        try {
            FileDataStore store = new FileDataStore(tmp);
            try {
                DexPool.writeTo(store, dex);
            } finally {
                try {
                    store.close();
                } catch (IOException ignored) {
                }
            }
            return readFile(tmp);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    static byte[] readFile(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new IOException("Неожиданный конец файла " + f);
                off += n;
            }
        } finally {
            in.close();
        }
        return b;
    }
}
