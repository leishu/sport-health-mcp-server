package org.example.sporthealth.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * 创建指向单个 SQLite 文件的 {@link javax.sql.DataSource}。
 *
 * <p>为什么手工建而不是用 Spring Boot 自动配置：
 * <ul>
 *   <li>SQLite 是单文件嵌入式库，没有主机端口，自动配置推断不出 URL，必须由我们给出；</li>
 *   <li>库文件的父目录在这里一并创建，避免首次启动因为 {@code ./data} 不存在而失败；</li>
 *   <li>连接池上限压到 1：写入串行化，绕开 SQLite 单写者限制，也省掉 busy 重试。</li>
 * </ul>
 *
 * <p>{@code application.yml} 里排除了数据源自动配置，所以这里的 Bean 是唯一来源。
 * 注意本类的包名在 {@code config} 下，不受自动配置包扫描顺序影响。
 */
@Configuration
@Slf4j
public class SqliteDataSourceConfiguration {

    /** SQLite 单个连接即可满足本服务的读多写少场景，并发由上层内存快照承担。 */
    private static final int POOL_SIZE = 1;

    /** 建库时的 pragma：WAL 提升读写并发，按 4KB 页缓存 8MB。 */
    private static final Map<SQLiteConfig.Pragma, String> PRAGMAS = Map.of(
            SQLiteConfig.Pragma.JOURNAL_MODE, "WAL",
            SQLiteConfig.Pragma.SYNCHRONOUS, "NORMAL",
            SQLiteConfig.Pragma.TEMP_STORE, "MEMORY");

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(HealthDataProperties properties) {
        Path dbFile = Paths.get(properties.dbPath()).toAbsolutePath().normalize();
        Path parent = dbFile.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("无法创建数据库目录 " + parent, e);
            }
        }

        SQLiteConfig config = new SQLiteConfig();
        PRAGMAS.forEach(config::setPragma);
        config.setBusyTimeout(5_000);

        SQLiteDataSource sqlite = new SQLiteDataSource(config);
        sqlite.setUrl("jdbc:sqlite:" + dbFile);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("sqlite-sport-health");
        hikari.setDataSource(sqlite);
        hikari.setMaximumPoolSize(POOL_SIZE);
        hikari.setMinimumIdle(POOL_SIZE);
        // SQLite 连接是内存级开销，常驻一个即可，避免频繁开关 WAL 连接
        hikari.setConnectionTimeout(10_000);

        log.info("SQLite 数据库文件: {}", dbFile);
        return new HikariDataSource(hikari);
    }

    /**
     * 显式提供 {@link JdbcClient}。
     *
     * <p>{@code JdbcClientAutoConfiguration} 依赖自动配置出来的 DataSource，这里已经手工接管，
     * 所以顺手把客户端也建出来，避免仓储层拿不到 bean。
     */
    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
