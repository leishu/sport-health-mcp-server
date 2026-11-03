# sport-health-mcp-server

基于 Spring AI 的华为运动健康 **TCX** 数据 MCP 服务。把导出的跑步记录变成 15 个可调用的 MCP 工具，让 Claude Code / Cursor 等客户端可以直接回答：

- 「我最近跑了哪些？」
- 「这个月一共跑了多少公里？」
- 「10-27 和 11-01 这两次哪次更快？」
- 「我最快的一次配速是多少？」
- 「这次每公里配速多少，有没有跑崩？」
- 「这次里最快的 1 公里 / 5 公里是多少？」
- 「这周比上周多跑了多少？」
- 「这次中途停了几次？」
- 「把 11-01 的轨迹按 200 个点分页给我。」

> 数据源只读取 `*.tcx`。目录里的其它文件不会被扫描或解析。

---

## 快速开始

环境要求：**Java 21**（项目使用 Spring Boot 4.1、Spring AI 2.0）。

```bash
# 开发模式
JAVA_HOME=/path/to/jdk-21 mvn spring-boot:run

# 或先打包再运行
JAVA_HOME=/path/to/jdk-21 mvn -DskipTests package
JAVA_HOME=/path/to/jdk-21 java -jar target/sport-health-mcp-server-1.0.0.jar
```

启动后：

| 地址 | 用途 |
| --- | --- |
| `http://localhost:8080/mcp` | MCP 端点（Streamable-HTTP，`POST`） |
| `http://localhost:8080/actuator/health` | 健康检查 |

正常启动日志会包含：

```
Registered tools: 15
Netty started on port 8080 (http)
已加载 5 个 TCX 文件、5 条跑步记录（轨迹按需加载）
```

---

## 数据格式

数据源是华为运动健康导出的 **TCX（Training Center Database v2）**，目录下每个 `*.tcx` 通常对应一次运动（一个文件包含多个 `Activity` 也能正确拆分）：

```xml
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
                        creator="Health" version="1.0">
  <Activities>
    <Activity Sport="户外跑步">
      <Id>2026-10-24T22:56:16.433Z</Id>
      <Lap StartTime="2026-10-24T22:56:16.433Z">
        <TotalTimeSeconds>2448.0</TotalTimeSeconds>
        <DistanceMeters>7123.0</DistanceMeters>
        <Extensions><RouteType>0</RouteType></Extensions>
        <Track>
          <Trackpoint>
            <Time>2026-10-24T22:56:16.433Z</Time>
            <Position>
              <LatitudeDegrees>39.902286</LatitudeDegrees>
              <LongitudeDegrees>116.18434</LongitudeDegrees>
            </Position>
            <AltitudeMeters>45.9</AltitudeMeters>
          </Trackpoint>
          <!-- 一次跑步通常 800 ~ 2000 个 Trackpoint -->
        </Track>
        <CumulativeClimb>10.1</CumulativeClimb>
        <CumulativeDecrease>13.5</CumulativeDecrease>
      </Lap>
    </Activity>
  </Activities>
</TrainingCenterDatabase>
```

### 字段识别范围

华为导出的字段是标准 TCX 的一个极简子集。当前解析器对样本文件的字段覆盖如下：

| 元素 / 属性 | 去向 |
| --- | --- |
| `TrainingCenterDatabase@creator` | `RunActivity.creator` |
| `Activity@Sport` | `RunActivity.sport` |
| `Activity/Id` | `RunActivity.activityId`，并作为开始时间的兜底 |
| `Lap@StartTime` | `startTimeUtc` → `startTime` / `date` |
| `Lap/TotalTimeSeconds` | `durationSeconds` |
| `Lap/DistanceMeters` | `distanceMeters` |
| `Lap/CumulativeClimb` | `climbMeters` |
| `Lap/CumulativeDecrease` | `descentMeters` |
| `Lap/Extensions/RouteType` | `routeType` + `routeTypeText` |
| `Trackpoint/Time` | `TrackPoint.timestamp` |
| `Trackpoint/Position/*` | `TrackPoint.latitude/longitude` |
| `Trackpoint/AltitudeMeters` | `TrackPoint.altitudeMeters` |

标准 TCX 中可能存在、但当前导出里没有的字段（`Calories`、`HeartRateBpm`、`Cadence`、逐点 `DistanceMeters` 等）**不会被解析**。需要时按「扩展点」一节补充即可。

`RouteType` 是华为的数字扩展字段，映射规则：

| 原始值 | 语义 |
| --- | --- |
| `0` | `OUTDOOR` |
| `1` | `INDOOR` |
| 其它 | `UNKNOWN(原值)`，不丢数据 |

> 映射依据：样本中 5 个户外跑步文件全部为 `0`。如果后续拿到官方定义，改 `support/RouteTypes` 一处即可。

### 解析规则

- **命名空间**：按元素 `localName` 匹配，不关心前缀，兼容不同导出工具。
- **时间是 UTC**：`Id` / `StartTime` / `Trackpoint/Time` 都是 `Z` 结尾，按 `zone-id`（默认 `Asia/Shanghai`）换算成本地时间。例如 `2026-10-31T23:12:42Z` 属于 **11-01**，与文件名 `20261101户外跑步.tcx` 一致。
- **记录 id**：取文件名去掉扩展名（如 `20261025户外跑步`），便于口头引用；同一文件多个 `Activity` 时追加 `#1`、`#2`。
- **多 Lap / 多 Track**：一个 `Lap` 下的多个 `Track` 会全部读取；多个 `Lap` 的时长、距离、爬升、下降、轨迹都会累加。
- **开始时间**：`首个 Lap@StartTime` → `Activity/Id` → 首个轨迹点。后续 Lap 的时间不会覆盖活动开始时间。
- **缺字段兜底**：
  - 只有**全部** Lap 都有 `TotalTimeSeconds` 时才求和，否则用轨迹首末时间差；
  - 距离同理，缺失时用 haversine 对有效坐标累积；
  - 缺经纬度的轨迹点仍保留（时间和海拔有用），坐标输出 `null`。
- **容错**：单个文件解析失败只记入 `warnings` 并跳过，不影响其它文件。
- **安全**：DOM 解析关闭 DTD、外部实体与外部 Schema（XXE 防护）。
- **轨迹点输出**：`get_run_route` / `get_run_detail(includeTrack=true)` 返回的海拔保留 2 位小数，经纬度保持原精度；累计爬升、海拔剖面等统计仍基于原始值计算。
- **距离口径**：分段、最佳努力、海拔剖面、路线等轨迹类分析使用有效坐标上的 haversine 累计距离，返回里的 `gpsDistanceKm` 可能与 `Lap/DistanceMeters` 略有差异（采样与汇总来源不同）；缺坐标的点不推进距离，只保留时间与海拔。

### 解析告警

字段级问题不会静默丢弃，而是汇总到 `RunDataset.warnings`，可通过 `describe_data_source` 查看，格式为 `文件名: 告警`：

| 告警类型 | 触发条件 |
| --- | --- |
| `N 个 Trackpoint 缺少 Time，已跳过` | 轨迹点没有时间 |
| `N 个 Trackpoint 的 Time 无法解析，已跳过` | 时间格式非法 |
| `N 个 Trackpoint 缺少经纬度，已保留时间与海拔` | 没有 `Position` 或坐标字段缺失 |
| `N 个 Trackpoint 坐标为 (0,0)` | 坐标无效，按无坐标处理 |
| `N 个 Trackpoint 缺少 AltitudeMeters` | 没有海拔 |
| `N 个 Trackpoint 的数字字段不是合法值` | `NaN` / 非数字 / `Infinity` |
| `Lap 的 <字段> 不是合法数字，已忽略: 原值` | `TotalTimeSeconds` / `DistanceMeters` / `Cumulative*` 非法 |
| `Lap@StartTime` / `Activity/Id` 无法解析，已忽略: 原值 | 时间格式非法，已改用后续兜底来源 |
| `部分 Lap 缺少 TotalTimeSeconds...` | 总时长已回退到轨迹时间差 |
| `部分 Lap 缺少 DistanceMeters...` | 总距离已回退到轨迹累积 |
| `读取失败 …` / `解析 TCX 文件失败 …` / `没有 Activity 节点` / `Activity 没有 Lap 节点` / `无法确定开始时间` | 整个文件被跳过 |
| `数据目录 … 下没有找到 *.tcx 文件` | 目录为空或路径配错 |

`describe_data_source` 的 `quality.warningCategories` 把这些告警归并成计数，便于判断问题集中在哪类字段：
`missing-time`、`bad-time`、`missing-position`、`zero-position`、`missing-altitude`、`invalid-number`、
`missing-lap-duration`、`missing-lap-distance`、`parse-error`、`no-files`、`other`。

### 数据质量自检

`describe_data_source` 返回的 `quality` 块把“数据看起来不对”的情况集中列出：

| 字段 | 含义 |
| --- | --- |
| `warningCount` / `warningCategories` | 解析告警总数与分类计数 |
| `zeroDistanceRunIds` | 距离为 0 的记录 |
| `missingPaceRunIds` / `noGpsRunIds` / `missingAltitudeRunIds` | 缺配速 / 缺起跑坐标 / 缺海拔 |
| `duplicateActivityIds` | TCX `Activity/Id` 重复，可能是同一活动被导出多次 |
| `paceOutliers` | 与区间中位配速偏差 ≥25% 的记录，附 `deviationPercent`；少于 3 条记录不判定 |
| `duplicateTimestampRunIds` | 同一记录内存在重复时间戳，会让分段/最佳努力的插值失真 |
| `clean` | 告警、0 距离、缺配速、缺起跑坐标、配速离群、重复时间戳全部为空时为 `true`；缺海拔与重复 Activity Id 只作提示，不参与判定 |

`duplicateTimestampRunIds` 需要逐条解析轨迹，首次调用稍慢，之后命中轨迹缓存。

---

## 接入客户端

Cursor / Claude Code / OpenCode 等客户端均可接入，下面是项目根目录 `.mcp.json` 的写法（文件需自行创建），也可以写进客户端全局配置：

```json
{
  "mcpServers": {
    "sport-health": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

### 用 MCP Inspector 调试

```bash
# Web UI（默认 http://127.0.0.1:6274，启动时会打印带 token 的地址）
npx @modelcontextprotocol/inspector --web --transport http \
  --server-url http://localhost:8080/mcp

# CLI：直接调用某个工具
npx @modelcontextprotocol/inspector --cli http://localhost:8080/mcp \
  --method tools/call --tool-name list_runs --tool-arg limit=3
```

> Streamable-HTTP 需要先 `initialize` 并携带 `Mcp-Session-Id`；手写 `curl` 时别忘了同时发送
> `Accept: application/json, text/event-stream`。

---

## 工具清单

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `describe_data_source` | — | 数据目录、已加载文件、记录数、日期范围、来源、RouteType、解析告警分类，以及 quality 块（0 距离、缺配速/坐标/海拔、重复 Activity Id、配速离群、重复时间戳） |
| `list_runs` | `startDate?` `endDate?` `sport?` `min/maxDistanceKm?` `min/maxPaceSecondsPerKm?` `weekday?` `sortBy?` `offset?` `limit?` | 区间内的记录（新的在前，分页），含距离/时长/配速/爬升 |
| `get_run_detail` | `runId` `includeTrack?` `trackOffset?` `trackLimit?` | 单次完整详情：起止时间、时长、距离、平均配速/速度、爬升与下降、海拔范围、起终点坐标，可选附带轨迹分页 |
| `get_run_route` | `runId` `offset?` `limit?` `format?` `maxPoints?` | 轨迹点分页（时间/纬度/经度/海拔，海拔保留 2 位小数），可输出 GeoJSON；`maxPoints` 降采样后会忽略 `offset`/`limit` |
| `get_running_summary` | `startDate?` `endDate?` | 次数、总距离、总时长、总爬升、平均配速、最快/最长记录 |
| `get_daily_running` | `startDate?` `endDate?` `groupBy?` | 按天/周/月拆分，只含有记录的周期，`day` 时额外返回每天一条 |
| `get_personal_bests` | `startDate?` `endDate?` `category?` | `category`：`all`（默认）/`distance`/`pace`/`duration`/`climb`/`best1k`/`best5k`；后两项为区间内最快的 1 公里、5 公里（需解析轨迹） |
| `compare_runs` | `baseRunId` `otherRunId` `includeSplits?` | 距离/时长/配速差异 + 一句话结论，可选附带两次的分段 |
| `get_run_splits` | `runId` `splitKm?` | 沿轨迹按固定距离（默认 1 公里）分段：配速、用时、本段爬升、最快/最慢段、前后半程对比、负分段、配速标准差，并返回 `gpsDistanceKm` |
| `get_best_efforts` | `runId` `distancesKm?` | 单次跑步内 1K/5K 等目标距离的最佳用时与折算配速（默认 `[1,5]`），附 `gpsDistanceKm`，超出本次距离的目标列入 `unavailableTargetsMeters` |
| `get_training_trend` | `period?` `count?` `endDate?` | 连续周/月趋势（`count` 默认 4、最多 52），含跑量与配速相对上一周期的变化 |
| `get_consistency` | `startDate?` `endDate?` | 打卡天数占比、当前/最长连续天数、平均间隔、日历热力数据；当前连续以最后一次记录为锚点 |
| `get_elevation_profile` | `runId` `bucketMeters?` `smoothingMeters?` | 按距离分桶的海拔剖面（默认 100 米一桶、最小 10；平滑默认 1 米、0 表示不平滑），含累计爬升下降与坡度 |
| `get_stop_points` | `runId` `minStopSeconds?` `maxDisplacementMeters?` | 检测停顿（默认 30 秒、30 米）：相邻轨迹点间隔≥阈值且位移≤阈值，返回时长、位置与总停顿 |
| `reload_data` | — | 导出新文件后重新扫描，无需重启 |

### list_runs 过滤与排序

- `sport` 与记录的运动类型精确匹配（忽略大小写）；`weekday` 用 ISO 口径，1=周一 … 7=周日。
- 配速过滤单位是秒/公里：`minPaceSecondsPerKm` 是数值下限（排除比它更快的记录，如 330 排除快于 5'30"），
  `maxPaceSecondsPerKm` 是数值上限（排除比它更慢的记录）；带配速条件时，缺配速的记录会被排除。
- `sortBy`：`date`（默认，新的在前）、`distance`、`duration`、`pace`（快的在前）、`climb`；缺值记录排在最后。
- `minDistanceKm > maxDistanceKm` 或 `minPaceSecondsPerKm > maxPaceSecondsPerKm` 会抛参数错误，而不是静默返回空结果。

### 返回状态

所有工具都通过 `status` 字段区分结果：

| `status` | 含义 | 建议处理 |
| --- | --- | --- |
| `OK` | 正常返回 | — |
| `NO_DATA` | 区间内没有记录，或该记录缺少所需字段（如无海拔） | 放宽日期，或先调 `list_runs` 看全部数据 |
| `NOT_FOUND` | `runId` 不存在 | 先用 `list_runs` 获取可用 id |
| `NO_GPS` | 没有可用轨迹：轨迹点为空，或轨迹类分析所需的有效坐标点不足 2 个 | 分段、最佳努力、路线等轨迹类分析无法进行，改用 `list_runs` 的汇总字段 |
| `AMBIGUOUS` | `runId` 匹配到多条 | 返回体里带 `candidates`，改用更完整的 id |

### 日期与分页

- 日期参数统一 `yyyy-MM-dd`，也兼容 `yyyy/M/d`、`yyyyMMdd`；带时间的写法（如 `2026-11-01 07:12:30`）同样可用，只取日期部分。
- **不传日期时默认覆盖数据集自身的最早到最后一天**，避免默认 7 天窗口把历史记录挡在外面。
- 分页使用 `offset` / `limit`：
  - 列表默认 20、上限 200（对应 `default-page-size` / `max-page-size`）；
  - 轨迹默认 200、上限 5000。

### runId 匹配规则

按以下顺序解析，命中多条时返回 `AMBIGUOUS`，不会随机挑一条：

1. 完整 id（如 `20261025户外跑步`）
2. 文件名（如 `20261025户外跑步.tcx`）
3. TCX `Activity/Id`（如 `2026-10-24T22:56:16.433Z`）
4. 唯一前缀 / 包含匹配（如 `20261101`）

---

## 配置

```yaml
sport:
  health:
    data-dir: classpath:health-data   # 支持 classpath: 前缀或本地绝对路径
    zone-id: Asia/Shanghai            # TCX 内 UTC 时间换算所用时区
    default-page-size: 20             # 列表默认条数
    max-page-size: 200                # 列表最大条数（小于默认值时自动提升）
```

指向真实导出目录：

```yaml
sport:
  health:
    data-dir: /home/leishu/health-export
```

---

## 访问日志

所有标注 `@McpTool` 的工具方法都由 `config/ToolAccessLogAspect` 统一记录，每次调用前后各一条：

```
2026-11-03 15:54:46.069 INFO  [boundedElastic-1] [-] [o.e.s.config.ToolAccessLogAspect] - phase=START tool=list_runs callId=call-1 args={"startDate":"2026-10-01",...,"limit":2}
2026-11-03 15:54:46.148 INFO  [boundedElastic-1] [-] [o.e.s.config.ToolAccessLogAspect] - phase=END tool=list_runs callId=call-1 status=OK durationMs=79
```

| 字段 | 含义 |
| --- | --- |
| `phase` | `START`：执行前；`END`：执行后 |
| `callId` | 本次调用序号，START / END 靠它关联（并发调用也能配对） |
| `tool` | `@McpTool.name`，即客户端调用的工具名 |
| `args` | 仅 `START`：入参 JSON（参数名来自编译期 `-parameters`，未传的参数记 `null`）；超过 512 字符截断 |
| `status` | 仅 `END`：返回体里的 `status`：`OK` / `NO_DATA` / `NOT_FOUND` / `NO_GPS` / `AMBIGUOUS`；抛异常时为 `ERROR` |
| `durationMs` | 仅 `END`：工具方法执行耗时（毫秒），不含日志自身开销 |

- 使用独立 logger `org.example.sporthealth.config.ToolAccessLogAspect`（即切面类名，日志里按 `%logger{36}` 缩写为 `o.e.s.config.ToolAccessLogAspect`），默认 `INFO`，与其它日志一样写入控制台和 `logs/tender-service.log`。
- 调整级别 / 关闭：`-DLOG_LEVEL_TOOL_ACCESS=DEBUG`、`-DLOG_LEVEL_TOOL_ACCESS=OFF`。
- 异常时 `phase=END` 按 `ERROR` 记录并附完整堆栈（同时写入 `logs/tender-service-error.log`），异常原样抛给客户端，返回内容不受影响。
- 按注解切面、不限定包，新增工具只要标注 `@McpTool` 就自动纳入，无需在工具方法里埋点。

---

## 架构

```
tools/SportHealthTools          # 15 个 @McpTool，负责参数校验与统一的 Map<String, Object> 返回结构
        │
service/RunAnalyticsService     # 区间汇总、按天拆分、个人最佳、对比、分段、最佳努力、海拔、停顿、趋势、id 解析
        │
repository/HealthDataRepository # 抽象：dataset() / route() / reload()
        └── TcxRunRepository    # 扫描目录、懒加载轨迹、缓存快照
                │
importer/TcxParser              # TCX → RunActivity + TrackPoint，纯解析无 Spring 依赖

support/TrackPath               # 轨迹几何索引：累计距离 ↔ 时间 ↔ 海拔，分段/最佳努力/海拔共用
```

- **启动阶段**：只解析摘要，轨迹不入内存。
- **首次请求轨迹**：按记录 id 找到来源文件，重新解析并缓存；`reload_data` 后缓存随旧快照一起丢弃。
- **返回体**：工具方法统一返回 `Map<String, Object>`（`status` + 业务字段），不再做多余的 `convertValue` 预转换，直接交给 MCP 层的 Jackson 3 序列化；`LocalDate` / `LocalDateTime` / `Instant` 输出 ISO-8601 字符串。
- **传输层**：WebFlux（Reactor Netty）+ Streamable-HTTP，端点 `POST /mcp`。

技术栈：Java 21、Spring Boot 4.1.2、Spring AI 2.0.2（Jackson 3、MCP Java SDK 2.1.0）、Reactor Netty。

> 依赖的是 `spring-ai-starter-mcp-server-webflux`，它已自带 `spring-boot-starter-webflux`。
> 不要再引入 `spring-boot-starter-web`，否则应用会退回 Servlet 栈，MCP 的 reactive handler 不会注册。

---

## 测试

```bash
JAVA_HOME=/path/to/jdk-21 mvn test
```

共 **42** 个用例：

- **真实数据**：直接用 `src/main/resources/health-data` 里的 5 个 TCX，断言单次解析
  （2448s / 7123m / 爬升 10.1m / 1216 个轨迹点）、UTC→本地日期换算、全量汇总
  （38.28 km / 219.8 min / 平均配速 5'45"/km）、区间过滤、按天拆分、最佳成绩、对比与轨迹分页。
- **轨迹分析**：逐公里分段（末段 `partial`、前后半程与负分段）、单次最佳努力、跨记录 1K/5K 最佳、
  海拔剖面、停顿检测与 `describe_data_source` 的 quality 自检。
- **工具层**：quality 块结构、`best1k`/`best5k` 类别，以及 `get_run_route` 两种格式的海拔小数精度。
- **访问日志**：每次调用前后各一条，START 带工具名与入参、END 带 `status` 与耗时，两条用 `callId` 关联；异常时 END 按 `ERROR` 记录并重新抛出。
- **内联 XML 边界用例**：一个 Lap 多个 Track、缺 `Lap@StartTime` 时用 `Activity/Id` 兜底、
  部分 Lap 缺 `TotalTimeSeconds`、缺 `Time`/非法数字/缺经纬度进入 warnings、
  坐标缺失输出 `null`、一个文件多个 `Activity`、未知 `RouteType`、模糊 id 多义检测。

---

## 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `status: "NO_DATA"` | 日期区间没覆盖到。先调 `describe_data_source` 看 `firstDate` / `lastDate` |
| `status: "AMBIGUOUS"` | `runId` 太短，按返回的 `candidates` 改用完整 id |
| 轨迹点数量比预期少 | 查看 `warnings`，是否有缺 `Time` 被跳过的点 |
| `warnings` 不为空但数据正常 | 字段级问题，通常不影响聚合；按告警类型决定是否修源文件 |
| `describe_data_source` 首次调用很慢 | `duplicateTimestampRunIds` 要逐条解析轨迹，之后命中缓存；只想看汇总时用其它工具 |
| 启动报 `Port 8080 was already in use` | 旧进程未退出，先停掉再启动 |
| 客户端连上但工具列表为空 | 检查是否误加了 `spring-boot-starter-web`，它会让应用退回 Servlet 栈 |
| 手写 HTTP 返回 400 / 406 | 缺少 `initialize`、`Mcp-Session-Id`，或 `Accept` 少了 `text/event-stream` |

---

## 扩展点

1. **其它运动类型**：解析阶段不按运动类型过滤，新增骑行/游泳 TCX 会直接出现在结果里，可用 `sport` 参数筛选。工具名当前是 `list_runs` 等，如需通用可改为 `list_activities`。
2. **心率 / 步频**：若导出文件出现 `Trackpoint/HeartRateBpm`、`Cadence`，在 `TcxParser.parseTrackpoints` 增加字段并扩展 `TrackPoint` 即可，聚合层无需改动。
3. **其它数据源**：新增 `XxxRepository implements HealthDataRepository`，组装 `RunDataset` 后工具层零改动。
4. **stdio 模式**：换成 `spring-ai-starter-mcp-server`，设置 `spring.ai.mcp.server.stdio: true` 与 `spring.main.web-application-type: none`，客户端用 `command` 启动 `java -jar`。
5. **鉴权**：HTTP 传输默认无鉴权，对外暴露前需加 Spring Security 或网关（WebFlux 用 `SecurityWebFilterChain`）。
