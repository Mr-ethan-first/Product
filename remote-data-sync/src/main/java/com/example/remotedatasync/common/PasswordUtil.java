package com.example.remotedatasync.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * 密码工具：SHA-256 + 随机盐，存储格式 <code>salt:hash</code>。
 * 不依赖额外组件，满足后台管理工具的基础安全需求（生产建议使用 BCrypt/Argon2）。
 *
 * @author 50707
 */
public final class PasswordUtil {

    private PasswordUtil() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

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

    /** 生成可存储的密码：salt:hash */
    public static String encode(String rawPassword) {
        String salt = randomSalt();
        return salt + ":" + sha256(salt + rawPassword);
    }

    /** 校验明文密码与存储值是否匹配 */
    public static boolean matches(String rawPassword, String stored) {
        if (stored == null || rawPassword == null || !stored.contains(":")) {
            return false;
        }
        int idx = stored.indexOf(':');
        String salt = stored.substring(0, idx);
        String hash = stored.substring(idx + 1);
        return sha256(salt + rawPassword).equals(hash);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
