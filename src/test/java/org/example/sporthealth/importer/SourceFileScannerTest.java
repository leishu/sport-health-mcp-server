package org.example.sporthealth.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 资源形态回归：数据目录既可能是普通目录，也可能是 fat jar 内的条目。
 *
 * <p>jar 形态曾经产出 {@code path = null} 的 {@link SourceFile}，随后 {@code sha256()}
 * 与 {@code open()} 直接 NPE，结果打包部署时一条记录都导不进来，只有 warnings 里一句看不懂的日志。
 */
class SourceFileScannerTest {

    private static final String XML = """
            <TrainingCenterDatabase>
              <Activities/>
            </TrainingCenterDatabase>
            """;

    @TempDir
    Path tempDir;

    @Test
    void readsTcxInsideJarWithoutFileSystemPath() throws IOException {
        Path jar = tempDir.resolve("health.jar");
        writeJar(jar, "health-data/sample.tcx", XML);

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            SourceFileScanner scanner = new SourceFileScanner(new PathMatchingResourcePatternResolver(loader));
            List<SourceFile> files = scanner.scan("classpath:health-data").files();

            assertEquals(1, files.size());
            SourceFile file = files.get(0);
            assertEquals("sample.tcx", file.name());
            assertFalse(file.hasMetadata(), "jar 条目拿不到 mtime，应按哈希判断");
            assertEquals(ContentHash.HEX_LENGTH, file.sha256().length());
            try (InputStream input = file.open()) {
                assertTrue(input.read() >= 0, "jar 内资源必须能打开");
            }
        }
    }

    @Test
    void readsTcxFromPlainDirectory() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("tcx"));
        Files.writeString(dir.resolve("sample.tcx"), XML);

        SourceFileScanner scanner = new SourceFileScanner(new PathMatchingResourcePatternResolver());
        List<SourceFile> files = scanner.scan(dir.toString()).files();

        assertEquals(1, files.size());
        SourceFile file = files.get(0);
        assertEquals("sample.tcx", file.name());
        assertTrue(file.hasMetadata(), "普通目录应拿到 size 与 mtime");
        assertEquals(ContentHash.HEX_LENGTH, file.sha256().length());
    }

    private static void writeJar(Path jar, String entryName, String content) throws IOException {
        int slash = entryName.lastIndexOf('/');
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            if (slash > 0) {
                // Maven 打出来的 jar 带目录条目，URLClassLoader 靠它才能定位 classpath 目录
                jarOut.putNextEntry(new JarEntry(entryName.substring(0, slash + 1)));
                jarOut.closeEntry();
            }
            jarOut.putNextEntry(new JarEntry(entryName));
            jarOut.write(content.getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
    }
}
