package org.example.sporthealth.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 来源文件的同步元数据：{@code size}、{@code mtime} 与内容哈希。
 *
 * <p>单独一张表而不是塞进 {@code run_activity}，是为了表达清楚「一次同步」和「一次活动」的区别：
 * 一个 TCX 文件可能包含多个 Activity（哈希相同、元数据相同），
 * 也可能解析成功但 0 个 Activity——只有这张表能完整表达这两种状态。
 *
 * <p>{@code modified_millis} 存 {@code -1} 表示元数据不可用（例如 classpath 的 jar 内资源），
 * 此时每次都退化为按哈希判断。{@code status} 记录上次同步结果：解析失败的文件内容不变时
 * 不会重试，但失败原因必须一直可见，否则数据缺口会静默消失。
 */
@Component
@Slf4j
public class SourceFileStateStore {

    private final JdbcClient jdbc;
    private final SqliteSchema schema;

    public SourceFileStateStore(JdbcClient jdbc, SqliteSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /** 一行来源文件状态；{@code failed} 为 true 时 {@code errorMessage} 是上次的失败原因。 */
    public record State(String name, long size, long modifiedMillis, String hash,
                        boolean failed, String errorMessage) {

        public boolean sameMetadata(long otherSize, long otherModified) {
            return size >= 0 && modifiedMillis >= 0
                    && size == otherSize && modifiedMillis == otherModified;
        }
    }

    /** 读出全部状态，key 为文件名。 */
    public Map<String, State> loadAll() {
        schema.ensure();
        Map<String, State> states = new LinkedHashMap<>();
        jdbc.sql("SELECT source_file, size, modified_millis, content_hash, status, error_message FROM source_file")
                .query((rs, rowNum) -> new State(
                        rs.getString("source_file"),
                        rs.getLong("size"),
                        rs.getLong("modified_millis"),
                        rs.getString("content_hash"),
                        "FAILED".equalsIgnoreCase(rs.getString("status")),
                        rs.getString("error_message")))
                .list()
                .forEach(state -> states.put(state.name(), state));
        return states;
    }

    /** 写入/覆盖一条状态；null 值表示该维度未知。 */
    public void save(String name, long size, long modifiedMillis, String hash,
                     boolean failed, String errorMessage) {
        schema.ensure();
        jdbc.sql("""
                INSERT INTO source_file (source_file, size, modified_millis, content_hash, status, error_message, synced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_file) DO UPDATE SET
                    size = excluded.size,
                    modified_millis = excluded.modified_millis,
                    content_hash = excluded.content_hash,
                    status = excluded.status,
                    error_message = excluded.error_message,
                    synced_at = excluded.synced_at
                """)
                .param(name)
                .param(size)
                .param(modifiedMillis)
                .param(hash)
                .param(failed ? "FAILED" : "OK")
                .param(errorMessage)
                .param(Instant.now().toString())
                .update();
    }

    /** 删除已不在数据目录里的文件状态，返回删除条数。 */
    public int deleteMissing(Set<String> existingNames) {
        schema.ensure();
        if (existingNames.isEmpty()) {
            // 不接管「目录为空」：那多半是路径配错或挂载点没就绪，清空指纹会让下次同步全量重导
            return 0;
        }
        return jdbc.sql("DELETE FROM source_file WHERE source_file NOT IN (:names)")
                .param("names", existingNames)
                .update();
    }

    /** 来源文件已被删除、但库里仍保留历史活动的文件名。 */
    public List<String> orphanedFiles(Set<String> existingNames) {
        schema.ensure();
        if (existingNames.isEmpty()) {
            return jdbc.sql("SELECT DISTINCT source_file FROM run_activity ORDER BY source_file")
                    .query(String.class).list();
        }
        return jdbc.sql("""
                SELECT DISTINCT source_file FROM run_activity
                WHERE source_file NOT IN (:names)
                ORDER BY source_file
                """)
                .param("names", existingNames)
                .query(String.class)
                .list();
    }
}
