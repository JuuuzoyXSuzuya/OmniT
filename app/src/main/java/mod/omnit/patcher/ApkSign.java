package mod.omnit.patcher;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * Подпись APK схемами v2+v3. Ключ создаётся один раз в Android Keystore (RSA-2048) и остаётся на
 * устройстве; из патчера он не вынимается. Если очистить данные патчера или удалить его, ключ пропадёт —
 * тогда новую версию патченного TikTok поверх старой поставить не получится, нужно сначала удалить старую.
 */
final class ApkSign {
    private static final String ALIAS = "omnit-signing-key";

    private final ApkSigner.SignerConfig config;
    private final X509Certificate cert;

    private ApkSign(ApkSigner.SignerConfig config, X509Certificate cert) {
        this.config = config;
        this.cert = cert;
    }

    static ApkSign create() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar notAfter = Calendar.getInstance();
            notAfter.set(2060, Calendar.JANUARY, 1);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            kpg.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=OmniT Patcher"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(new Date(System.currentTimeMillis() - 24L * 3600 * 1000))
                    .setCertificateNotAfter(notAfter.getTime())
                    .build());
            kpg.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        if (key == null || cert == null) throw new IllegalStateException("Не удалось получить ключ подписи из Keystore");
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                "omnit", key, Collections.singletonList(cert)).build();
        return new ApkSign(cfg, cert);
    }

    String describe() {
        return cert.getSubjectX500Principal().getName() + ", до " + cert.getNotAfter();
    }

    void sign(File in, File out) throws Exception {
        if (out.exists()) //noinspection ResultOfMethodCallIgnored
            out.delete();
        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(false) // Android 7+ (API 24+) проверяет v2/v3
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(24)
                .build()
                .sign();
    }
}
