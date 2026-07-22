package com.example.remotedatasync.security;

import com.example.remotedatasync.common.CryptoUtil;
import com.example.remotedatasync.common.PasswordUtil;
import com.example.remotedatasync.service.DatabaseMetadataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全加固单测（纯单元，不依赖数据库 / Spring 上下文）：
 *  - 缺陷① SQL 注入：库名/表名标识符白名单必须拦截注入串；
 *  - 缺陷⑤ 明文密码落盘：CryptoUtil 持久化密文必须为 ENC: 前缀的 AES-GCM 密文，且可还原、向后兼容明文；
 *  - 缺陷⑥ 弱哈希：PasswordUtil 必须采用 PBKDF2 加盐哈希（pbkdf2$iter$salt$hash），并兼容历史 SHA-256。
 *
 * @author 50707
 */
class SecurityHardeningTest {

    // ===================== 缺陷①：SQL 注入标识符白名单 =====================

    @Test
    void assertSafeIdentifier_acceptsValidNames() {
        // 合法标识符（业务实际使用的库名/表名）不应被拒绝
        for (String ok : new String[]{"geo_source", "biz_source", "t_order", "t_user",
                "t_customer", "a", "A1", "x$y", "_tmp_2026"}) {
            DatabaseMetadataService.assertSafeIdentifier(ok);
        }
    }

    @Test
    void assertSafeIdentifier_rejectsInjectionPayloads() {
        // 任何含 SQL 元字符 / 非法字符的标识符必须被拒绝（注入闸门）
        String[] evil = {
                "x'; DROP TABLE y; --",
                "x`y",
                "geo_source\" OR \"1\"=\"1",
                "../../etc",
                "a b",
                "a-b",
                "a.b",
                "",
                "a".repeat(65),          // 超过 64 长度上限
                "x/*comment*/y",
        };
        for (String payload : evil) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> DatabaseMetadataService.assertSafeIdentifier(payload),
                    "应拦截非法标识符: " + payload);
            assertTrue(ex.getMessage().contains("Illegal"), "异常信息应说明非法标识符: " + ex.getMessage());
        }
    }

    // ===================== 缺陷⑤：明文密码落盘 =====================

    @Test
    void cryptoUtil_encryptProducesEncPrefixAndRoundTrips() {
        String plain = "S3cr3t@123456";
        String enc = CryptoUtil.encrypt(plain);
        assertNotNull(enc);
        assertTrue(enc.startsWith("ENC:"), "持久化密文必须以 ENC: 前缀，避免明文落盘: " + enc);
        // 密文中不得出现原始明文
        assertFalse(enc.contains(plain), "密文不应包含明文密码");
        // 可还原
        assertEquals(plain, CryptoUtil.decrypt(enc), "ENC: 密文应能还原为原明文");
    }

    @Test
    void cryptoUtil_randomIvMakesCiphertextsDistinct() {
        String plain = "repeat-me";
        String a = CryptoUtil.encrypt(plain);
        String b = CryptoUtil.encrypt(plain);
        assertTrue(a.startsWith("ENC:") && b.startsWith("ENC:"));
        assertNotEquals(a, b, "相同明文每次加密应因随机 IV 而不同（避免可预测的密文）");
        assertEquals(plain, CryptoUtil.decrypt(a));
        assertEquals(plain, CryptoUtil.decrypt(b));
    }

    @Test
    void cryptoUtil_backwardCompatPlaintext() {
        // 历史明文值（无 ENC: 前缀）应原样返回，保证旧数据可加载
        assertEquals("legacy-plain", CryptoUtil.decrypt("legacy-plain"));
        // null 安全
        assertEquals(null, CryptoUtil.decrypt(null));
    }

    // ===================== 缺陷⑥：弱哈希 =====================

    @Test
    void passwordUtil_encodeUsesPbkdf2Format() {
        String hash = PasswordUtil.encode("admin123");
        assertTrue(hash.startsWith("pbkdf2$"), "存储格式应为 pbkdf2$iter$salt$hash: " + hash);
        String[] parts = hash.split("\\$");
        assertEquals(4, parts.length, "pbkdf2$ 格式应含 4 段");
        assertEquals("120000", parts[1], "迭代次数应为 12 万");
    }

    @Test
    void passwordUtil_matchesCorrectAndWrong() {
        String hash = PasswordUtil.encode("Str0ng#Pass");
        assertTrue(PasswordUtil.matches("Str0ng#Pass", hash), "正确口令应匹配");
        assertFalse(PasswordUtil.matches("wrong-pass", hash), "错误口令不应匹配");
        assertFalse(PasswordUtil.matches(null, hash), "null 明文不应匹配");
        assertFalse(PasswordUtil.matches("x", null), "null 存储值不应匹配");
    }

    @Test
    void passwordUtil_notDeterministic() {
        String a = PasswordUtil.encode("same");
        String b = PasswordUtil.encode("same");
        assertNotEquals(a, b, "相同口令两次编码应因随机盐而不同（抗彩虹表）");
        assertTrue(PasswordUtil.matches("same", a));
        assertTrue(PasswordUtil.matches("same", b));
    }

    @Test
    void passwordUtil_legacySha256StillVerifies() {
        // 兼容历史 salt:hash（SHA-256）账号，已存在账号可正常登录
        String salt = PasswordUtil.randomSalt();
        String legacy = salt + ":" + PasswordUtil.sha256(salt + "legacyPw");
        assertTrue(PasswordUtil.matches("legacyPw", legacy), "历史 SHA-256 格式口令应可验证");
        assertFalse(PasswordUtil.matches("other", legacy), "历史格式错误口令应不匹配");
    }
}
