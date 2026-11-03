package org.example.sporthealth.repository;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.importer.ParsedRun;
import org.example.sporthealth.importer.TcxDataException;
import org.example.sporthealth.importer.TcxParser;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Repository;

/**
 * 扫描配置目录下的 {@code *.tcx} 并解析为运动记录。
 *
 * <p>加载策略：
 * <ul>
 *   <li>启动首次访问时扫描目录、解析每个文件，结果存为一份不可变 {@link Loaded} 快照；</li>
 *   <li>单个文件解析失败只记入 warnings 并跳过，不影响其它数据；</li>
 *   <li>摘要中的字段级告警会以 {@code 文件名: 告警} 的形式汇总进 {@link RunDataset#warnings()}；</li>
 *   <li>轨迹不随启动加载：{@link #route(String)} 首次调用时才解析对应文件并缓存，
 *       {@link #reload()} 会整体替换快照与缓存，旧数据可被回收。</li>
 * </ul>
 *
 * <p>并发：{@code loaded} 为 volatile，首次加载与 {@code reload} 用双重检查/方法锁串行化；
 * 轨迹缓存是快照内的 {@link ConcurrentHashMap}，同一记录可能被并发解析一次，但结果一致。
 */
@Repository
@Slf4j
public class TcxRunRepository implements HealthDataRepository {

    private static final String TCX_PATTERN = "*.tcx";

    private final HealthDataProperties properties;
    private final TcxParser parser;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private volatile Loaded loaded;

    public TcxRunRepository(HealthDataProperties properties, TcxParser parser) {
        this.properties = properties;
        this.parser = parser;
    }

    @Override
    public RunDataset dataset() {
        return current().dataset();
    }

    /**
     * 返回指定记录的完整轨迹；首次访问时解析来源文件并缓存，找不到返回空列表。
     * 只接受记录 id，不做模糊匹配（匹配逻辑在 service 层）。
     */
    @Override
    public List<TrackPoint> route(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        Loaded snapshot = current();
        String key = runId.trim();

        List<TrackPoint> cached = snapshot.routeCache().get(key);
        if (cached != null) {
            return cached;
        }
        RouteSource source = snapshot.routeSources().get(key);
        if (source == null) {
            return List.of();
        }

        List<TrackPoint> points = loadRoute(source);
        List<TrackPoint> existing = snapshot.routeCache().putIfAbsent(key, points);
        return existing != null ? existing : points;
    }

    @Override
    public synchronized void reload() {
        this.loaded = load();
    }

    private Loaded current() {
        Loaded value = loaded;
        if (value == null) {
            synchronized (this) {
                value = loaded;
                if (value == null) {
                    value = load();
                    loaded = value;
                }
            }
        }
        return value;
    }

    private Loaded load() {
        List<String> sourceFiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<RunActivity> activities = new ArrayList<>();
        Map<String, RouteSource> routeSources = new LinkedHashMap<>();

        List<Resource> resources = discover(warnings);
        if (resources.isEmpty()) {
            warnings.add("数据目录 " + properties.dataDir() + " 下没有找到 " + TCX_PATTERN + " 文件");
        }

        for (Resource resource : resources) {
            String fileName = resource.getFilename() == null ? resource.getDescription() : resource.getFilename();
            try (InputStream input = resource.getInputStream()) {
                List<ParsedRun> runs = parser.parse(fileName, input);
                sourceFiles.add(fileName);
                for (ParsedRun run : runs) {
                    activities.add(run.activity());
                    routeSources.put(run.activity().id(), new RouteSource(resource, fileName, run.activity().id()));
                    run.warnings().forEach(warning -> warnings.add(fileName + ": " + warning));
                }
            } catch (TcxDataException e) {
                warnings.add(fileName + ": " + e.getMessage());
                log.warn("跳过无法解析的 TCX 文件 {}: {}", fileName, e.getMessage());
            } catch (IOException e) {
                warnings.add(fileName + ": 读取失败 " + e.getMessage());
                log.warn("读取 TCX 文件失败 {}", fileName, e);
            }
        }

        activities.sort(Comparator.comparing(RunActivity::startTime).reversed());
        log.info("已加载 {} 个 TCX 文件、{} 条跑步记录（轨迹按需加载）", sourceFiles.size(), activities.size());

        RunDataset dataset = new RunDataset(
                Instant.now(),
                properties.dataDir(),
                List.copyOf(sourceFiles),
                List.copyOf(activities),
                List.copyOf(warnings));
        return new Loaded(dataset, Map.copyOf(routeSources), new ConcurrentHashMap<>());
    }

    /** 重新解析来源文件，按 id 取出对应 Activity 的轨迹；任何问题都降级为空列表。 */
    private List<TrackPoint> loadRoute(RouteSource source) {
        try (InputStream input = source.resource().getInputStream()) {
            return parser.parse(source.fileName(), input).stream()
                    .filter(run -> run.activity().id().equals(source.runId()))
                    .findFirst()
                    .map(ParsedRun::trackPoints)
                    .orElseGet(() -> List.of());
        } catch (TcxDataException e) {
            log.warn("按需加载轨迹失败 {}: {}", source.fileName(), e.getMessage());
            return List.of();
        } catch (IOException e) {
            log.warn("按需加载轨迹读取失败 {}", source.fileName(), e);
            return List.of();
        }
    }

    private List<Resource> discover(List<String> warnings) {
        String pattern = locationPattern();
        try {
            Resource[] resources = resolver.getResources(pattern);
            return Arrays.stream(resources)
                    .filter(Resource::isReadable)
                    .sorted(Comparator.comparing(resource -> resource.getFilename() == null ? "" : resource.getFilename()))
                    .toList();
        } catch (IOException e) {
            warnings.add("扫描目录失败 " + pattern + ": " + e.getMessage());
            log.warn("扫描 TCX 目录失败 {}", pattern, e);
            return List.of();
        }
    }

    private String locationPattern() {
        String dir = properties.dataDir();
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        if (dir.startsWith("/")) {
            dir = "file:" + dir;
        }
        return dir + "/" + TCX_PATTERN;
    }

    private record RouteSource(Resource resource, String fileName, String runId) {
    }

    private record Loaded(RunDataset dataset,
                          Map<String, RouteSource> routeSources,
                          ConcurrentHashMap<String, List<TrackPoint>> routeCache) {
    }
}
