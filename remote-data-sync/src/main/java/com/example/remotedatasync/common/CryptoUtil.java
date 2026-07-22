package com.example.remotedatasync.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 轻量对称加密工具：用于持久化动态映射的数据库密码，避免明文落盘。
 * <p>
 * 主密钥优先取自环境变量 <code>DRPLATFORM_MASTER_KEY</code>（SHA-256 派生 32 字节）；
 * 未配置时回退到内置开发密钥（仅用于本地/测试，生产必须配置该环境变量）。
 * 密文格式：<code>ENC:&lt;base64(iv)&gt;:&lt;base64(ciphertext)&gt;</code>，
 * 历史明文值（不含 ENC: 前缀）在解密时原样返回，向后兼容。
 *
 * @author 50707
 */
public final class CryptoUtil {

    private static final String DEV_KEY = "drplatform-dev-master-key-change-in-prod!";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;
    private static final SecretKeySpec KEY;

    static {
        String env = System.getenv("DRPLATFORM_MASTER_KEY");
        byte[] keyBytes;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            keyBytes = md.digest((env != null && !env.isEmpty() ? env : DEV_KEY).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        KEY = new SecretKeySpec(keyBytes, "AES");
    }

    public static String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return "ENC:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(enc);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public static String decrypt(String enc) {
        if (enc == null) {
            return null;
        }
        if (!enc.startsWith("ENC:")) {
            return enc; // 兼容历史明文
        }
        try {
            String body = enc.substring(4);
            int idx = body.indexOf(':');
            byte[] iv = Base64.getDecoder().decode(body.substring(0, idx));
            byte[] data = Base64.getDecoder().decode(body.substring(idx + 1));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_LEN, iv));
            return new String(c.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }
}
