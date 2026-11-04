package org.example.sporthealth.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件内容指纹，用于增量同步：
 * <ul>
 *   <li>{@link #of(Path)} 读全文件算 SHA-256，新增/疑似变更时用；</li>
 *   <li>{@link #of(InputStream)} 供已经拿到流的场景复用，避免重复打开文件。</li>
 * </ul>
 *
 * <p>算法固定 {@code SHA-256}，输出 64 位小写十六进制，直接存进 {@code content_hash} 列。
 */
public final class ContentHash {

    /** 摘要长度，写入前校验用。 */
    public static final int HEX_LENGTH = 64;

    private static final int BUFFER_SIZE = 8192;

    private ContentHash() {
    }

    public static String of(Path file) throws IOException {
        try (InputStream input = java.nio.file.Files.newInputStream(file)) {
            return of(input);
        }
    }

    public static String of(InputStream input) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 长度与字符集校验，脏数据来自人工改库时才可能触发。 */
    public static boolean isWellFormed(String hash) {
        return hash != null && hash.length() == HEX_LENGTH;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }
}
