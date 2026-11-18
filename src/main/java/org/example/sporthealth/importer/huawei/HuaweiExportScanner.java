package org.example.sporthealth.importer.huawei;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.importer.SourceFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 递归扫描数据目录，挑出华为导出里需要导入的 JSON 文件。
 *
 * <p>为什么不复用 {@code SourceFileScanner}：那个类只扫一层 {@code *.tcx}，
 * 而华为导出是「一级子目录 + 每个目录里几十个文件」的树形结构，必须用 {@code **} 递归。
 * 指纹与增量判断仍复用 {@link SourceFile}，两套数据源共用 {@code source_file} 表。
 *
 * <p>空文件（导出工具生成的 0 字节占位 JSON）直接跳过：它们解析必然失败，
 * 记成失败只会让告警列表长期挂着噪声。
 */
@Component
@Slf4j
public class HuaweiExportScanner {

    private static final String JSON_PATTERN = "**/*.json";

    private final ResourcePatternResolver resolver;

    public HuaweiExportScanner() {
        this(new PathMatchingResourcePatternResolver());
    }

    /** 测试用：允许注入自定义解析器。 */
    HuaweiExportScanner(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @param file    可读的 JSON 文件及其指纹
     * @param kind    文件类别，决定用哪个解析器
     * @param source  原始资源，用来打开输入流
     */
    public record ExportFile(SourceFile file, HuaweiExportKind kind) {

        public Resource resource() {
            return file.resource();
        }
    }

    /**
     * @param files    扫描到的文件，按文件名升序，保证导入顺序稳定
     * @param problems 扫描阶段的问题，直接进 warnings
     */
    public record ScanResult(List<ExportFile> files, List<String> problems) {
    }

    public ScanResult scan(String location) {
        String pattern = jsonPattern(location);
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("扫描华为导出目录失败 {}", pattern, e);
            return new ScanResult(List.of(), List.of("扫描华为导出目录失败 " + pattern + ": " + e.getMessage()));
        }

        List<ExportFile> files = new ArrayList<>();
        for (Resource resource : resources) {
            SourceFile file = SourceFile.of(resource);
            if (file.name() == null || file.name().isBlank() || !resource.isReadable()) {
                continue;
            }
            HuaweiExportKind kind = HuaweiExportKind.of(file.name());
            if (kind == null) {
                continue;
            }
            if (file.size() == 0) {
                log.debug("跳过空的华为导出文件 {}", file.name());
                continue;
            }
            files.add(new ExportFile(file, kind));
        }
        files.sort(Comparator.comparing(entry -> entry.file().name()));
        return new ScanResult(List.copyOf(files), List.of());
    }

    /** 与 {@code SourceFileScanner} 一致：无协议前缀的路径按文件系统处理，否则会被当成 classpath。 */
    private String jsonPattern(String location) {
        String dir = location == null || location.isBlank() ? "" : location.trim();
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        if (dir.startsWith("/")) {
            dir = "file:" + dir;
        } else if (!dir.contains(":") || isWindowsDrive(dir)) {
            dir = "file:" + dir;
        }
        return dir + "/" + JSON_PATTERN;
    }

    private static boolean isWindowsDrive(String path) {
        return path.length() > 1 && path.charAt(1) == ':';
    }
}
