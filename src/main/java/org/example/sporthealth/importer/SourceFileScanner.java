package org.example.sporthealth.importer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 扫描数据目录下的 {@code *.tcx}，产出带元数据的 {@link SourceFile}。
 *
 * <p>两种目录形态走同一条路径：
 * <ul>
 *   <li><b>普通目录</b>（{@code file:} / 本地绝对路径）：能拿到 size 与 mtime，
 *       增量同步可以对这些文件做零 I/O 的快速判断；</li>
 *   <li><b>classpath</b>（开发期默认的 {@code classpath:health-data}）：解压目录与
 *       fat jar 内的条目都由 {@link Resource} 统一读取。jar 条目没有可靠的 mtime，
 *       元数据返回 {@code -1}，降级为每次算 SHA-256（数据量小，代价可接受）。</li>
 * </ul>
 *
 * <p>按文件名排序，保证导入顺序稳定；不可读的文件直接忽略。
 */
@Component
@Slf4j
public class SourceFileScanner {

    private static final String TCX_PATTERN = "*.tcx";

    private final ResourcePatternResolver resolver;

    public SourceFileScanner() {
        this(new PathMatchingResourcePatternResolver());
    }

    /** 测试用：允许指定 classloader，覆盖 fat jar 内的资源形态。 */
    SourceFileScanner(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @param files    扫描到的文件，按文件名升序
     * @param problems 扫描阶段的问题（目录不可读等），直接进 {@code RunDataset.warnings}
     */
    public record ScanResult(List<SourceFile> files, List<String> problems) {
    }

    public ScanResult scan(String location) {
        String pattern = tcxPattern(location);
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("扫描 TCX 目录失败 {}", pattern, e);
            return new ScanResult(List.of(), List.of("扫描目录失败 " + pattern + ": " + e.getMessage()));
        }

        List<SourceFile> files = new ArrayList<>(resources.length);
        for (Resource resource : resources) {
            SourceFile file = toSourceFile(resource);
            if (file != null) {
                files.add(file);
            }
        }
        files.sort(Comparator.comparing(SourceFile::name));
        return new ScanResult(List.copyOf(files), List.of());
    }

    /** 资源转换成可读文件；不可读时返回 null。 */
    private SourceFile toSourceFile(Resource resource) {
        if (!resource.isReadable()) {
            return null;
        }
        SourceFile file = SourceFile.of(resource);
        if (file.name() == null || file.name().isBlank()) {
            return null;
        }
        return file;
    }

    /**
     * 与旧实现保持一致：绝对路径补 {@code file:} 前缀，其余原样拼接。
     *
     * <p>相对路径也要显式补 {@code file:}：{@code PathMatchingResourcePatternResolver} 会把
     * 没有前缀的路径当成 classpath 资源，找不到时直接抛异常，扫描结果静默为空。
     */
    private String tcxPattern(String location) {
        String dir = location == null || location.isBlank() ? "" : location.trim();
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        if (dir.startsWith("/")) {
            dir = "file:" + dir;
        } else if (!dir.contains(":") || isWindowsDrive(dir)) {
            // 没有协议前缀的相对路径（含 Windows 盘符）统一按文件系统处理
            dir = "file:" + dir;
        }
        return dir + "/" + TCX_PATTERN;
    }

    /** {@code C:/data} 这类盘符不是 URL 协议，需要单独识别。 */
    private static boolean isWindowsDrive(String path) {
        return path.length() > 1 && path.charAt(1) == ':';
    }
}
