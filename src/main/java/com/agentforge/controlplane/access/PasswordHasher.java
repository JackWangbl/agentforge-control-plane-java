package com.agentforge.controlplane.access;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 和 Python 版 app/access/auth.py 二进制兼容，两版共用同一个 users 表也能互相登录：
 * PBKDF2-HMAC-SHA256，120000 轮，salt 是 16 字节的十六进制串（按 UTF-8 取字节参与运算），
 * 存储格式 "{salt}${digestHex}"。
 */
public final class PasswordHasher {

    private static final int ROUNDS = 120_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return salt + "$" + HexFormat.of().formatHex(derive(password, salt));
    }

    public static boolean verify(String password, String stored) {
        if (stored == null || !stored.contains("$")) {
            return false;
        }
        int cut = stored.indexOf('$');
        String salt = stored.substring(0, cut);
        String expected = stored.substring(cut + 1);
        String actual = HexFormat.of().formatHex(derive(password, salt));
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derive(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8),
                    ROUNDS,
                    KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令哈希计算失败", e);
        }
    }
}
