package org.example.sporthealth.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.Resource;

/**
 * 一个待同步的 TCX 文件及其指纹。
 *
 * <p>持有 {@link Resource} 而不是 {@code Path}：数据目录既可能是普通目录，也可能是
 * fat jar 里的 {@code BOOT-INF/classes/health-data}。后者没有文件系统路径，只能经
 * {@link Resource#getInputStream()} 读取；统一走资源接口，两种形态行为一致。
 *
 * <p>{@code size} 与 {@code modifiedMillis} 用来做零 I/O 的快速判断：两者与库中记录一致，
 * 就认为文件没变，连 SHA-256 都不用算。只有元数据对不上时才调用 {@link #sha256()}
 * 算内容哈希，再决定跳过还是重导。
 *
 * @param resource       实际读取来源，目录文件与 jar 条目都适用
 * @param name           文件名（含扩展名），写入 {@code source_file}
 * @param size           字节数，-1 表示不可用
 * @param modifiedMillis 最后修改时间（毫秒），-1 表示不可用（如 jar 内资源）；取整到秒，规避不同文件系统的时间戳精度差异
 */
public record SourceFile(Resource resource, String name, long size, long modifiedMillis) {

    /** 元数据是否完整；缺任一项就只能退回到按哈希判断。 */
    public boolean hasMetadata() {
        return size >= 0 && modifiedMillis >= 0;
    }

    /** 计算内容哈希；每次调用都会重新读一遍资源。 */
    public String sha256() throws IOException {
        try (InputStream input = open()) {
            return ContentHash.of(input);
        }
    }

    /** 打开资源流；调用方负责关闭。 */
    public InputStream open() throws IOException {
        return resource.getInputStream();
    }

    /** 从 Spring 资源读取指纹，读不到元数据时降级为 {@code -1}。 */
    public static SourceFile of(Resource resource) {
        String name = resource.getFilename();
        if (name == null || name.isBlank()) {
            name = resource.getDescription();
        }
        long size = -1;
        long modified = -1;
        try {
            if (resource.isFile()) {
                Path path = resource.getFile().toPath();
                size = Files.size(path);
                // 精确到秒：ext4 是纳秒、FAT 是 2 秒，秒级既够用又能跨文件系统比对上
                modified = Files.getLastModifiedTime(path).toInstant().getEpochSecond() * 1000L;
            } else {
                // jar 内条目没有可靠的 mtime，size 一般可用；缺任一项都会退化为按哈希判断
                size = resource.contentLength();
            }
        } catch (IOException ignored) {
            // 大小/时间读不到不算错误，后续按哈希判断即可
        }
        return new SourceFile(resource, name, size, modified);
    }
}
