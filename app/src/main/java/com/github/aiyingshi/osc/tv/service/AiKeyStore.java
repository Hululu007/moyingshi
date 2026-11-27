package com.github.aiyingshi.osc.tv.service;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Base64;

import com.github.aiyingshi.osc.tv.App;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * BYOK 的 API key 加密存储：照抄 {@code SmbCredentialStore} 的模式，只换 alias 与字段。
 *
 * <p>密文以 Base64 存进 {@code ai_config} JSON 的 {@code apiKey} 字段，Keystore 加密的带
 * {@link #ENC_PREFIX} 前缀，本地文件密钥退路加密的带 {@link #LOCAL_PREFIX} 前缀；
 * 无前缀即老版本留下的明文，读时按明文对待并由 {@code Setting} 一次性迁移回写。
 *
 * <p>没有 AndroidKeyStore 的 provider 时（部分模拟器/精简 ROM）退回到
 * {@code noBackupFilesDir} 下的随机文件密钥，并用 {@link #LOCAL_PREFIX} 标记：这保证可用性，
 * 不等价于硬件级密钥保护。
 *
 * <p>只负责 BYOK 的 {@code apiKey}。中转设备 token 的存储是下一步的活，不走这里。
 */
public final class AiKeyStore {

    /** Keystore AES/GCM 密文前缀：与明文区分，也与本地文件密钥退路区分。 */
    public static final String ENC_PREFIX = "enc-v1:";
    /** 本地文件密钥退路的密文前缀：只防备份文件直读，不等价于硬件级保护。 */
    public static final String LOCAL_PREFIX = "local-v1:";

    private static final String ALIAS = "webhtv_ai_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String LOCAL_KEY_FILE = "ai_key.key";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private AiKeyStore() {
    }

    /** 是否已是加密存储的形态（任一前缀都算）。无前缀的即老明文。 */
    public static boolean isEncrypted(String value) {
        if (TextUtils.isEmpty(value)) return false;
        return value.startsWith(ENC_PREFIX) || value.startsWith(LOCAL_PREFIX);
    }

    /** 明文加密成带前缀的存储形态；空值原样返回空串。 */
    public static String encrypt(String plain) throws Exception {
        if (TextUtils.isEmpty(plain)) return "";
        return encrypt(App.get(), plain);
    }

    /**
     * 解开带前缀的存储形态，还发明文；解不开（换了手机/清了 Keystore/密钥损坏）时抛异常，
     * 调用方按“key 已失效，请重新填写”处理，不崩溃。
     */
    public static String decrypt(String stored) throws Exception {
        if (TextUtils.isEmpty(stored)) return "";
        if (!isEncrypted(stored)) throw new IllegalArgumentException("AI key 不是加密存储形态");
        return decrypt(App.get(), stored);
    }

    static String encrypt(Context context, String text) throws Exception {
        SecretKey secret;
        String prefix = ENC_PREFIX;
        try {
            secret = key();
        } catch (KeyStoreException error) {
            // 缺少 OEM provider 与“系统密钥存在但不可用”是两件事：后者必须照旧报错。
            if (Security.getProvider(ANDROID_KEYSTORE) != null) throw error;
            secret = localKey(context, true);
            prefix = LOCAL_PREFIX;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return prefix + Base64.encodeToString(out, Base64.NO_WRAP);
    }

    static String decrypt(Context context, String value) throws Exception {
        boolean local = value.startsWith(LOCAL_PREFIX);
        String body = local ? value.substring(LOCAL_PREFIX.length()) : value.substring(ENC_PREFIX.length());
        byte[] input = Base64.decode(body, Base64.DEFAULT);
        if (input.length <= IV_BYTES) return "";
        byte[] iv = new byte[IV_BYTES];
        byte[] rest = new byte[input.length - IV_BYTES];
        System.arraycopy(input, 0, iv, 0, IV_BYTES);
        System.arraycopy(input, IV_BYTES, rest, 0, rest.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, local ? localKey(context, false) : key(), new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(rest), StandardCharsets.UTF_8);
    }

    private static synchronized SecretKey localKey(Context context, boolean create) throws Exception {
        File path = new File(context.getNoBackupFilesDir(), LOCAL_KEY_FILE);
        AtomicFile file = new AtomicFile(path);
        byte[] bytes;
        try {
            bytes = file.readFully();
        } catch (FileNotFoundException error) {
            if (!create || path.exists() || new File(path + ".bak").exists()) {
                throw new KeyStoreException("AI key 本地密钥不可用，请重新填写 key", error);
            }
            bytes = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            FileOutputStream output = file.startWrite();
            try {
                output.write(bytes);
                output.getFD().sync();
                file.finishWrite(output);
            } catch (Exception failure) {
                file.failWrite(output);
                throw failure;
            }
            // 只有确认密钥真的落盘，才允许后续写入 key。
            if (!Arrays.equals(bytes, file.readFully())) throw new KeyStoreException("AI key 本地密钥保存失败");
        }
        if (bytes.length != KEY_BYTES) throw new KeyStoreException("AI key 本地密钥损坏，请重新填写 key");
        return new SecretKeySpec(bytes, "AES");
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
