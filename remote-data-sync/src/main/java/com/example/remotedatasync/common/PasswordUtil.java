package com.example.remotedatasync.common;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

/**
 * 密码工具：采用 PBKDF2WithHmacSHA256（12 万次迭代）加盐哈希，存储格式
 * <code>pbkdf2$iterations$salt$hash</code>。相比原单次 SHA-256，抗暴力破解能力显著增强。
 * 仍兼容历史 <code>salt:hash</code>（SHA-256）格式，已存在的账号可正常登录。
 *
 * @author 50707
 */
public final class PasswordUtil {

    private PasswordUtil() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;

    public static String randomSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return toHex(b);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 生成可存储的密码：pbkdf2$iterations$salt$hash */
    public static String encode(String rawPassword) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt, PBKDF2_ITERATIONS);
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + toHex(salt) + "$" + toHex(hash);
    }

    /** 校验明文密码与存储值是否匹配（兼容 pbkdf2 与历史 salt:hash 两种格式） */
    public static boolean matches(String rawPassword, String stored) {
        if (stored == null || rawPassword == null) {
            return false;
        }
        if (stored.startsWith("pbkdf2$")) {
            String[] p = stored.split("\\$");
            if (p.length != 4) {
                return false;
            }
            int iter = Integer.parseInt(p[1]);
            byte[] salt = fromHex(p[2]);
            byte[] hash = fromHex(p[3]);
            byte[] computed = pbkdf2(rawPassword, salt, iter);
            return slowEquals(computed, hash);
        }
        // 兼容历史 salt:hash（SHA-256）存储格式
        if (stored.contains(":")) {
            int idx = stored.indexOf(':');
            String salt = stored.substring(0, idx);
            String hash = stored.substring(idx + 1);
            return sha256(salt + rawPassword).equals(hash);
        }
        return false;
    }

    private static byte[] pbkdf2(String raw, byte[] salt, int iterations) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_LENGTH_BITS)).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    /** 定长时间比较，降低时序侧信道风险 */
    private static boolean slowEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(2 * i), 16) << 4)
                    | Character.digit(s.charAt(2 * i + 1), 16));
        }
        return out;
    }
}
