# sport-health-mcp-server

基于 Spring AI 的华为运动健康 **TCX** 数据 MCP 服务。把导出的跑步记录变成 20 个可调用的 MCP 工具，让 Claude Code / Cursor 等客户端可以直接回答：

- 「我最近跑了哪些？」
- 「这个月一共跑了多少公里？」
- 「10-27 和 11-01 这两次哪次更快？」
- 「我最快的一次配速是多少？」
- 「这次每公里配速多少，有没有跑崩？」
- 「这次里最快的 1 公里 / 5 公里是多少？」
- 「这周比上周多跑了多少？」
- 「这次中途停了几次？」
- 「把 11-01 的轨迹按 200 个点分页给我。」
- 「我现在的水平全马能跑多少？」
- 「最近加量是不是太快，有没有伤病风险？」
- 「这次算轻松跑、节奏跑还是长距离？」
- 「山路实际强度相当于平路什么配速？」
- 「热天我是不是掉速，下雨慢多少？」

> 数据源只读取 `*.tcx`。目录里的其它文件不会被扫描或解析。
>
> 解析结果写入本地 **SQLite**（`data/sport-health.db`）：TCX 是导入源，库是事实源。
> 导出新文件后调 `reload_data` 做增量同步，**删掉源文件也不会丢历史记录**。

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
SQLite 数据库文件: /home/you/sport-health-mcp-server/data/sport-health.db
Registered tools: 20
Netty started on port 8080 (http)
```

首次请求（或调 `reload_data`）时才做同步，日志会多出：

```
SQLite 结构已从版本 0 升级到 1
增量同步完成：扫描 5 个文件、重导 5 个、未变 0 个，库中活动 5 条
快照已装载：5 条活动、5 个来源文件
```

第二次启动只会看到 `重导 0 个、未变 5 个`——未变化的文件连内容都不读。

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

`duplicateTimestampRunIds` 需要逐条读取轨迹，首次调用稍慢，之后命中轨迹缓存。

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
| `describe_data_source` | — | 数据目录、已加载文件、记录数、日期范围、来源、RouteType、解析告警分类、`storage`（库路径与本次同步统计），以及 quality 块（0 距离、缺配速/坐标/海拔、重复 Activity Id、配速离群、重复时间戳） |
| `list_runs` | `startDate?` `endDate?` `sport?` `min/maxDistanceKm?` `min/maxPaceSecondsPerKm?` `weekday?` `sortBy?` `offset?` `limit?` | 区间内的记录（新的在前，分页），含距离/时长/配速/爬升 |
| `get_run_detail` | `runId` `includeTrack?` `trackOffset?` `trackLimit?` | 单次完整详情：起止时间、时长、距离、平均配速/速度、爬升与下降、海拔范围、起终点坐标、该次跑步的天气 `weather`（起跑/结束时刻的气温、体感、湿度、降水、风向风速、天气现象），可选附带轨迹分页 |
| `get_run_route` | `runId` `offset?` `limit?` `format?` `maxPoints?` | 轨迹点分页（时间/纬度/经度/海拔，海拔保留 2 位小数），可输出 GeoJSON；`maxPoints` 降采样后会忽略 `offset`/`limit` |
| `get_running_summary` | `startDate?` `endDate?` | 次数、总距离、总时长、总爬升、平均配速、最快/最长记录 |
| `get_daily_running` | `startDate?` `endDate?` `groupBy?` | 按天/周/月拆分，只含有记录的周期，`day` 时额外返回每天一条 |
| `get_personal_bests` | `startDate?` `endDate?` `category?` | `category`：`all`（默认）/`distance`/`pace`/`duration`/`climb`/`best1k`/`best5k`；后两项为区间内最快的 1 公里、5 公里（需解析轨迹） |
| `compare_runs` | `baseRunId` `otherRunId` `includeSplits?` | 距离/时长/配速差异 + 一句话结论，可选附带两次的分段 |
| `get_run_splits` | `runId` `splitKm?` | 沿轨迹按固定距离（默认 1 公里）分段：配速、用时、本段爬升、净海拔变化、平均坡度、坡度修正配速（GAP）、最快/最慢段、前后半程对比、负分段、配速标准差，并返回 `gpsDistanceKm` 与全程 GAP 汇总 |
| `get_best_efforts` | `runId` `distancesKm?` | 单次跑步内 1K/5K 等目标距离的最佳用时与折算配速（默认 `[1,5]`），附 `gpsDistanceKm`，超出本次距离的目标列入 `unavailableTargetsMeters` |
| `get_training_trend` | `period?` `count?` `endDate?` | 连续周/月趋势（`count` 默认 4、最多 52），含跑量与配速相对上一周期的变化 |
| `get_consistency` | `startDate?` `endDate?` | 打卡天数占比、当前/最长连续天数、平均间隔、日历热力数据；当前连续以最后一次记录为锚点 |
| `get_elevation_profile` | `runId` `bucketMeters?` `smoothingMeters?` | 按距离分桶的海拔剖面（默认 100 米一桶、最小 10；平滑默认 1 米、0 表示不平滑），含累计爬升下降与坡度 |
| `get_stop_points` | `runId` `minStopSeconds?` `maxDisplacementMeters?` | 检测停顿（默认 30 秒、30 米）：相邻轨迹点间隔≥阈值且位移≤阈值，返回时长、位置与总停顿 |
| `predict_race_time` | `startDate?` `endDate?` `distanceKm?` | 用区间内 1K/5K/10K/半马/全马最佳努力做 Riegel 外推（`T2 = T1 × (D2/D1)^1.06`）。不传 `distanceKm` 时输出 5K/10K/半马/全马；有实测距离时标记 `source=ACTUAL`，外推标记 `RIEGEL`，并给出参考记录与 `HIGH/MEDIUM/LOW` 置信度 |
| `get_training_load` | `endDate?` `metric?` | 训练负荷与 ACWR：7 天急性、28 天慢性、`ACWR = 急性 / (慢性/4)`、周跑量增幅、连续训练天数、休息日、单调性与 Strain，并给出调整建议。`metric` 可选 `distance`（默认，公里）或 `duration`（分钟）；数据覆盖不足时明确标注置信度 |
| `classify_run` | `runId` | 识别训练类型：轻松跑、长距离跑、节奏跑、间歇/变速跑、比赛/测试跑、恢复跑。基于距离、时长、配速相对个人中位数与分段波动，返回判定依据与置信度（无 GPS 时降为 LOW） |
| `get_training_mix` | `startDate?` `endDate?` | 区间内的训练类型构成：各类型的次数、距离、距离占比，并逐条列出分类结果。逐条分类会按需解析轨迹，首次调用稍慢 |
| `get_weather_impact` | `startDate?` `endDate?` `minDistanceKm?` | 只读已回填的 `run_weather`，按起跑气温、湿度、风速、有无降水分组，返回每组次数、加权平均配速、相对整体基线的快慢秒数，以及气温与配速的 Pearson 相关系数。不联网，没有天气时提示先 `reload_data` |
| `reload_data` | — | 导出新文件后做增量同步：按 `(文件名, size, mtime, sha256)` 只解析变化的文件，无需重启 |

### list_runs 过滤与排序

- `sport` 与记录的运动类型精确匹配（忽略大小写）；`weekday` 用 ISO 口径，1=周一 … 7=周日。
- 配速过滤单位是秒/公里：`minPaceSecondsPerKm` 是数值下限（排除比它更快的记录，如 330 排除快于 5'30"），
  `maxPaceSecondsPerKm` 是数值上限（排除比它更慢的记录）；带配速条件时，缺配速的记录会被排除。
- `sortBy`：`date`（默认，新的在前）、`distance`、`duration`、`pace`（快的在前）、`climb`；缺值记录排在最后。
- `minDistanceKm > maxDistanceKm` 或 `minPaceSecondsPerKm > maxPaceSecondsPerKm` 会抛参数错误，而不是静默返回空结果。

### 训练分析口径

P0 新增的五项分析都只使用已有的 TCX 数据与已回填的天气，不引入新的数据源、不新增数据库表：

- **比赛预测**：Riegel 公式 `T2 = T1 × (D2/D1)^1.06`。参考成绩取区间内 1K/5K/10K/半马/全马最佳努力中与目标距离最接近的一项；目标距离本身有实测时直接返回实测值并标记 `ACTUAL`。参考距离与目标距离差得越远，`confidence` 越低。
- **训练负荷 / ACWR**：急性窗口 7 天、慢性窗口 28 天，负荷口径默认距离（公里），也可切换为时长（分钟）。`ACWR = 急性负荷 / (慢性负荷 / 4)`；同时返回周增幅、连续训练天数、休息日、7 天单调性（均值/标准差）与 Strain。慢性窗口覆盖不足 21 天或训练日少于 7 天时 `dataSufficient=false`，置信度降为 `LOW`。
- **坡度修正配速 GAP**：用 Minetti 跑步能量代价公式计算坡度代价比 `Cr(grade)/Cr(0)`，`GAP = 实际配速 / 代价比`，坡度夹取到 `[-45%, 45%]`；净海拔变化取每段首尾海拔差。全程 GAP 用等效平路距离加权，而不是对分段 GAP 做算术平均。
- **训练类型识别**：无心率数据，基于距离、时长、配速相对个人中位数与分段波动做启发式判断。长距离阈值取 `max(10 km, 1.5 × 个人中位距离)`；间歇/变速要求同时有至少 2 段明显快、2 段明显慢于中位配速，避免把长距离跑里的一次停顿误判成间歇；无 GPS 轨迹时置信度降为 `LOW`。
- **天气影响**：只读 `run_weather` 中 `status=OK` 的记录，优先使用起跑快照（缺失时退回结束快照）。分组内配速用「总时长 / 总距离」加权；样本少于 3 条时相关系数为 `null`，并在结论文本里提示样本不足。

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
    data-dir: classpath:health-data   # 支持 classpath: 前缀、本地绝对路径或相对路径
    db-path: ./data/sport-health.db   # SQLite 数据库文件，相对路径按进程工作目录解析
    zone-id: Asia/Shanghai            # TCX 内 UTC 时间换算所用时区
    default-page-size: 20             # 列表默认条数
    max-page-size: 200                # 列表最大条数（小于默认值时自动提升）
  weather:
    enabled: true                     # 关闭后完全不碰网络，run_weather 不留行
    forecast-base-url: https://api.open-meteo.com/v1/forecast
    archive-base-url: https://archive-api.open-meteo.com/v1/archive
    recent-window-days: 92            # forecast 端点覆盖范围，更早的走 archive
    archive-lag-days: 5               # forecast 窗口最老的 lag 天交给 archive（应远小于窗口）
    timeout: 10s                      # 单次 HTTP 超时
    retry-failed-after-days: 7        # FAILED 记录的冷却时间
    max-runs-per-sync: 50             # 单轮最多补多少条，防止首次全量导入打光配额
```

指向真实导出目录：

```yaml
sport:
  health:
    data-dir: /home/leishu/health-export
    db-path: /home/leishu/health-export/sport-health.db
```

> `data-dir` 与 `db-path` 不要在同一个目录里互相包含：`*.tcx` 只在 `data-dir` 扫描，
> 库文件放在数据目录里不影响正确性，但备份时容易一起被误删。

---

## 持久化

TCX 文件只在**内容变化**时被解析一次，结果写进 SQLite；之后所有查询都走库和内存快照。

### 四张表

| 表 | 内容 |
| --- | --- |
| `run_activity` | 一次运动一行：摘要字段（时间、距离、配速、爬升、起终点坐标…）+ `source_file` + `content_hash` + `imported_at` |
| `track_point` | 轨迹点，主键 `(run_id, seq)`，`WITHOUT ROWID`，一个活动的点物理连续 |
| `source_file` | 来源文件指纹：`size`、`modified_millis`、`content_hash`，增量同步的比对基准 |
| `run_weather` | 每次跑步的起跑 / 结束两个时刻的天气，主键 `run_id`，由 open-meteo 回填 |

表结构版本记在 `PRAGMA user_version`（当前为 3）。以后加心率、步频等列时，
往 `SqliteSchema.MIGRATIONS` 追加一段脚本并把 `SCHEMA_VERSION` 加一即可，不需要 Flyway；
版本 1 的旧库会在启动时自动补上 `source_file.status` / `error_message` 两列，版本 2 的会补建 `run_weather`，
不需要删库重建。

### 增量规则

启动（首次访问）与 `reload_data` 都会执行同一套同步：

| 情况 | 行为 |
| --- | --- |
| 库里没有这个文件 | 算 SHA-256 → 解析 → 事务内写入 |
| `size` 与 `mtime` 都没变 | **直接跳过，不读文件内容** |
| 元数据变了但 SHA-256 相同（被 touch 过） | 只刷新元数据，不重导 |
| 元数据变了且 SHA-256 不同 | 重新解析，事务内「删旧行 + 插新行」 |
| 元数据不可用（fat jar 内资源） | 退化为每次都算 SHA-256；数据目录放在 jar 里同样可用 |
| 源文件被删除 | **历史记录保留**，只把文件记为缺失 |
| 文件解析失败 | 记 warning 与失败状态并跳过，**不删除库中已有数据**；文件内容不变时不再重试，但每次同步都会把这个原因重新放进 `warnings` |
| 扫描不到任何文件（路径配错、挂载点未就绪） | 只记 warning，**不清空指纹表**，避免恢复后把全部文件重导一遍 |
| 记录 id 已被其它来源文件占用 | 拒绝导入并记 warning，不静默覆盖另一份记录 |

写入以文件为单位放在一个事务里，中途失败整体回滚，不会出现「摘要写进去、轨迹没写完」的半截记录。
`mtime` 取整到秒，规避 ext4 与 FAT 的时间戳精度差异。

### 怎么查

```bash
sqlite3 data/sport-health.db "SELECT id, run_date, round(distance_meters/1000.0,2) km FROM run_activity ORDER BY run_date DESC LIMIT 5;"
sqlite3 data/sport-health.db "SELECT COUNT(*), round(SUM(distance_meters)/1000.0,1) FROM run_activity WHERE run_date LIKE '2026-11%';"
sqlite3 data/sport-health.db "SELECT source_file, size, modified_millis, substr(content_hash,1,12) FROM source_file;"
```

### 天气回填（open-meteo）

每次同步（首次加载、`reload_data`）在**跑步数据全部写完之后**补一轮天气：取起跑和结束两个时刻的气温、体感温度、
相对湿度、降水量、风速、风向、天气代码，写进 `run_weather`。

**为什么只补缺失的**：`OK` 和 `UNAVAILABLE` 都是终态，只有「没有行」和「`FAILED` 且超过冷却期」才会被选中。
所以同步可以反复调，不会重复消耗配额。

| 状态 | 含义 | 是否重试 |
| --- | --- | --- |
| `OK` | 至少一个时刻取到了有效数据 | 否 |
| `UNAVAILABLE` | 接口正常，但覆盖不到这个时段（太早的历史、没有 GPS 坐标） | 否，终态 |
| `FAILED` | 网络、限流、5xx、响应无法解析 | 是，`retry-failed-after-days` 天后 |

**端点分流**：距今 `recent-window-days`（92 天）内走 `forecast`，更早的走 `archive`。
这不是随意选的——archive 端点对最近几天的数据会返回 **200 但 24 个值全为 null**，只能靠 forecast 补。
`archive-lag-days` 做的是另一件事：把 forecast 窗口**最老的** `lag` 天（默认 5 天）改交给 archive，
避开 forecast 92 天上限的边缘。它应远小于 `recent-window-days`；配成不小于窗口时按 0 处理。

> 已知边界：如果自定义的 `recent-window-days` 本身小于 archive 约 5 天的延迟，
> 窗口外最近的日期会落进 archive 的盲区，可能记为 `UNAVAILABLE`。保持默认 92 天即可。

**一天一次请求**：同一 UTC 日期上的所有坐标（多条记录的起终点、跨天记录的另一半）合并进一次请求，
响应按坐标分发回各条记录。配额是「日期数」量级而不是「记录数」；缓存键是「日期 + 坐标」，
点对点跑步起点在北京、终点在上海时，终点用终点坐标，不会复用起点的天气。

**连续失败提前收尾**：端点黑洞（丢包不回 RST）时每次请求都要等满超时，连续 3 次可重试失败后本轮直接返回，
未处理的记录**不落行**，下次同步会重新选中：

```
天气回填本轮提前结束：接口连续失败，8 条留待下次同步
```

**取数精度**：按小时取整天的逐小时数据，然后在起跑 / 结束时刻做线性插值。风速、气温等连续量直接插值；
**风向按最短弧插值**（350° 与 10° 的中点取 0° 而不是 180°）；`weather_code` 是枚举，取最近的整点不插值。
结束后超过最后一个整点 23:00 的记录，容差 1 小时内取 23:00 的值，不向外推。

**在查询里读天气**：`get_run_detail` 的 `weather` 块给出**这次跑步**起跑与结束两个时刻的天气，
不必再自己写 SQL：

```json
"weather": {
  "status": "OK",
  "provider": "open-meteo",
  "fetchedAt": "2026-11-04T02:11:07Z",
  "start": {
    "observedAt": "2026-10-24T22:00:00Z",
    "temperatureC": 8.0, "apparentTemperatureC": 6.2,
    "humidityPercent": 72.0, "precipitationMm": 0.2,
    "windSpeedKmh": 12.0, "windDirectionDegrees": 315.0, "windDirection": "西北",
    "weatherCode": 61, "condition": "小雨"
  },
  "end": { "...": "同上，跑完时刻" }
}
```

口径：`observedAt` 是参与插值的**整点**，不是跑步时刻本身；任一指标缺值一律 `null`，不会当成 0 度；某个时刻整份快照都没有时该时刻直接是 `null`。`status` 除 `OK` 外还有三种：`UNAVAILABLE`（缺坐标、
超出存档覆盖范围）与 `FAILED`（抓取失败待重试）会带上 `errorMessage` 说明原因；还没回填过则是
`MISSING`，提示调用一次 `reload_data` 即可补齐。

**聚合看天气影响**：`get_weather_impact` 只读已经落库的天气行，按起跑气温、湿度、风速、有无降水分组，
返回每组的次数、加权平均配速与相对整体基线的快慢秒数。它不会触发抓取，所以没有数据时
先调 `reload_data`。

**孤儿行清理**：`run_weather` 没有外键，记录被重导删除时天气行会留在表里。导入时删「本次解析后
消失的 id」，以及「id 没变但起跑时刻 / 时长 / 起终点坐标变了」的记录——前者是记录没了，
后者如同名文件被换成另一次跑步，旧天气不能再算到新记录头上。仅距离、爬升等不影响天气查询的字段
变化时会保留已回填的天气（不白花配额）；每轮回填开始前再扫一遍全表兜底。
覆盖率统计一律 `JOIN run_activity`，所以历史遗留的孤儿行也不会让数字超过 100%。

**失败绝不影响跑步数据**：HTTP 调用刻意放在导入事务之外（连接池只有 1 个连接，网络卡顿会占死整个服务），
且异常在 `TcxIncrementalImporter.appendWeather` 里被吞成一条汇总 warning：

```
天气回填失败 12 条（open-meteo 返回 503：），将在下次同步重试
```

查看覆盖率：

```bash
sqlite3 data/sport-health.db "SELECT status, COUNT(*) FROM run_weather GROUP BY status;"
sqlite3 data/sport-health.db "SELECT run_id, status, round(start_temperature_c,1), round(start_apparent_c,1), start_wind_direction, start_weather_code FROM run_weather LIMIT 5;"
```

`describe_data_source` 的 `storage.weather` 也直接给出 `ok` / `unavailable` / `failed` / `missing`
和 `coveragePercent`，不必自己写 SQL。

> 非商业用途下 open-meteo 无需 API Key（限 10000 次/天）。`max-runs-per-sync` 默认 50，
> 首次全量导入几千条历史时会分多轮慢慢补齐，不会一次打光配额。

### 备份与迁移

```bash
# 备份：库 + WAL 一起拷，或先 checkpoint
sqlite3 data/sport-health.db "PRAGMA wal_checkpoint(TRUNCATE);"
cp data/sport-health.db ~/backup/sport-health-$(date +%F).db
```

换机器时只带 `sport-health.db` 过去，`data-dir` 指向空目录也能正常查历史；
日志里 `missing=...` 会列出哪些来源文件不在本地。

### 为什么不用 MySQL / JPA

单用户、本地 MCP、数据量 MB 级。引数据库只是换来部署负担与网络依赖；
JPA 在 WebFlux 上要配 R2DBC 才不阻塞，对只有两张表的场景属于过度设计。
这里用 `spring-boot-starter-jdbc` + `org.xerial:sqlite-jdbc`，
`JdbcClient` 直接写 SQL，连接池上限压到 1 规避 SQLite 单写者限制。

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
tools/SportHealthTools          # 20 个 @McpTool，负责参数校验与统一的 Map<String, Object> 返回结构
        │
service/RunAnalyticsService     # 区间汇总、按天拆分、个人最佳、对比、分段/GAP、最佳努力、海拔、停顿、趋势、比赛预测、ACWR、训练分类、id 解析
        │
repository/HealthDataRepository # 抽象：dataset() / route() / reload()
        └── JdbcHealthDataRepository   # 从 SQLite 装载快照、轨迹直查库（@Primary）
                │
repository/TcxIncrementalImporter  # 增量同步的唯一写入口：比指纹、解析、事务批量写入
        ├── SourceFileStateStore   # source_file 表：size / mtime / sha256
        ├── SqliteSchema           # 建表与 PRAGMA user_version 版本迁移
        └── importer/TcxParser     # TCX → RunActivity + TrackPoint，纯解析无 Spring 依赖

weather/WeatherBackfill         # 导入结束后补一轮天气：挑待补、按 UTC 日期分批、落状态
        ├── OpenMeteoClient        # forecast / archive 两个端点，多坐标合并成一次请求
        ├── WeatherInterpolator    # 整点序列 → 任意时刻（连续量线性、风向最短弧、天气代码取最近）
        ├── WindDirections         # 风向角度 → 8 方位中文（风的来向）
        ├── WeatherCodes           # WMO 天气代码 → 中文（未知代码原样透出）
        └── repository/RunWeatherStore  # run_weather 表：SELECT 待补 / UPSERT / 按记录读回 / 覆盖率 / 清孤儿行

importer/SourceFileScanner      # 扫描 *.tcx，产出带 size/mtime 的 SourceFile
importer/ContentHash            # SHA-256 指纹

support/TrackPath               # 轨迹几何索引：累计距离 ↔ 时间 ↔ 海拔，分段/最佳努力/海拔共用
support/WeatherImpactAnalyzer   # 天气影响分组统计（纯计算，不访问库或网络）
domain/RacePrediction           # 比赛预测结果（参考成绩 + 各目标距离）
domain/TrainingLoad             # 训练负荷 / ACWR 快照
domain/RunClassification        # 单次训练类型识别结果
domain/TrainingMix              # 区间训练类型构成
domain/WeatherImpact            # 天气影响分组结果
```

- **启动阶段**：只建表，不解析。首次访问（或 `reload_data`）触发增量同步，然后把全部摘要装进内存快照。
- **轨迹**：`route(runId)` 直接查 `track_point`，不再重解析 XML；按 id 缓存，`reload_data` 后随旧快照丢弃。
- **内存快照的作用**：区间过滤、按天聚合、趋势、连续性等统计仍在 Java 里跑，SQL 只负责取数，
  避免把 `RunAnalyticsService` 改写成 SQL 拼装器。
- **返回体**：工具方法统一返回 `Map<String, Object>`（`status` + 业务字段），直接交给 MCP 层的 Jackson 3 序列化；`LocalDate` / `LocalDateTime` / `Instant` 输出 ISO-8601 字符串。
- **传输层**：WebFlux（Reactor Netty）+ Streamable-HTTP，端点 `POST /mcp`。
- **旧实现**：`repository/TcxRunRepository` 是纯内存版本，已标 `@Deprecated` 且不再是 Bean，仅保留给既有单测做对照。

技术栈：Java 21、Spring Boot 4.1.2、Spring AI 2.0.2（Jackson 3、MCP Java SDK 2.1.0）、Reactor Netty、
SQLite（`spring-boot-starter-jdbc` + `org.xerial:sqlite-jdbc`，无 ORM）。

> 依赖的是 `spring-ai-starter-mcp-server-webflux`，它已自带 `spring-boot-starter-webflux`。
> 不要再引入 `spring-boot-starter-web`，否则应用会退回 Servlet 栈，MCP 的 reactive handler 不会注册。
>
> `application.yml` 里排除了 `DataSourceAutoConfiguration`：SQLite 没有主机端口，自动配置推断不出 URL，
> 数据源与 `JdbcClient` 都由 `config/SqliteDataSourceConfiguration` 显式创建。

---

## 测试

```bash
JAVA_HOME=/path/to/jdk-21 mvn test
```

共 **126** 个用例（原有 42 + 持久化 19 + 资源形态 2 + 天气 42 + 工具层天气 4 + P0 分析 17）：

- **真实数据**：测试固定读 `src/test/resources/tcx-dataset` 里的 5 个 TCX，而不是
  `src/main/resources/health-data`。生产目录会随导入继续增长（例如新加的骑行文件），
  断言里写死的「5 条记录 / 38.28 km」会跟着一起挂；用独立 fixture 后新增数据不再影响单测。
  断言单次解析（2448s / 7123m / 爬升 10.1m / 1216 个轨迹点）、UTC→本地日期换算、全量汇总
  （38.28 km / 219.8 min / 平均配速 5'45"/km）、区间过滤、按天拆分、最佳成绩、对比与轨迹分页。
- **轨迹分析**：逐公里分段（末段 `partial`、前后半程与负分段）、单次最佳努力、跨记录 1K/5K 最佳、
  海拔剖面、停顿检测与 `describe_data_source` 的 quality 自检。
- **工具层**：quality 块结构、`best1k`/`best5k` 类别，以及 `get_run_route` 两种格式的海拔小数精度。
- **访问日志**：每次调用前后各一条，START 带工具名与入参、END 带 `status` 与耗时，两条用 `callId` 关联；异常时 END 按 `ERROR` 记录并重新抛出。
- **内联 XML 边界用例**：一个 Lap 多个 Track、缺 `Lap@StartTime` 时用 `Activity/Id` 兜底、
  部分 Lap 缺 `TotalTimeSeconds`、缺 `Time`/非法数字/缺经纬度进入 warnings、
  坐标缺失输出 `null`、一个文件多个 `Activity`、未知 `RouteType`、模糊 id 多义检测。
- **持久化**（`JdbcHealthDataRepositoryTest`，用真实 SQLite 文件而不是 mock）：首次全量导入、
  二次同步零重导（幂等）、元数据变化但内容相同不重导、内容变化替换旧行而不产生重复、
  删除源文件后历史与轨迹仍可查、缺坐标写库再读出仍是 `null`、坏文件不破坏已有数据、
  `source_file` 指纹落库。另含 4 个回归用例：
  - 连接池只有 1 条连接时也能建表（建表 DDL 不能包在需要第二条连接的事务里，否则等 10s 超时）；
  - 解析失败的文件在内容未变时仍然回放告警，不会因为「已同步」而被静默跳过；
  - 数据目录扫描不到任何文件时保留指纹表，不把已有记录当成「源文件已删除」；
  - 版本 1 的旧库能原地升级到版本 2（补 `status` / `error_message` 两列），不需要删库。
- **资源形态**（`SourceFileScannerTest`）：同一份扫描逻辑在普通目录与 fat jar 内条目下都能
  拿到文件名、哈希与可读流；jar 形态拿不到 `mtime` 时退化为按内容哈希判断变更。
- **天气回填**（`WeatherBackfillTest`，真实 SQLite + MockWebServer 桩接口）：
  起终点落在同一天时只发一次请求、同一天两条记录共用一个响应、终点快照取终点坐标而不是起点坐标、
  跨 UTC 零点时分别请求两天且结束快照来自次日、全 null 响应落 `UNAVAILABLE` 而不是 `OK`、
  **网络故障不影响跑步数据与轨迹**、`FAILED` 在冷却期内不重发而冷却后重试、
  跨天记录一天 400、另一天 503 时仍落 `FAILED` 而不是永久放弃、
  连续失败达到 3 次后提前收尾且剩余记录不落行、
  重复同步不重复请求也不重复插入、缺坐标直接标 `UNAVAILABLE` 且不发请求、
  关闭开关后不碰网络也不留行、覆盖率统计。
- **天气与查询接口**（`SportHealthToolsWeatherTest`，真实 SQLite）：`get_run_detail` 的 `weather`
  块带上起跑时刻的中文描述与 8 方位风向、结束时刻缺值输出 `null`；还没回填时是 `MISSING` 并带提示；
  `UNAVAILABLE` 行把 `errorMessage` 透出来；内存仓储（无天气表）下详情与 `storage.weather` 都省略。
- **天气行生命周期**（`JdbcHealthDataRepositoryTest`）：文件里删掉一个 Activity 后它的天气行一起删除、
  仅距离变化时保留已回填的天气、同名文件起跑时刻变化时旧天气作废、
  历史遗留的孤儿行在同步时被清理、`stats` 不把孤儿行算进覆盖率或 `lastFetchedAt`。
- **P0 分析**（`RunAnalyticsP0Test` / `SportHealthToolsP0Test` / `SportHealthToolsWeatherImpactTest`）：
  5K/10K 有实测时返回 `ACTUAL`、半马/全马按 Riegel 外推且置信度随距离差下降；自定义目标距离；
  ACWR 在只有 10 天数据时标 `LOW` 置信度并给出高风险提示；逐公里 GAP 与净海拔/坡度字段；
  长距离跑识别与训练类型构成；工具层 5 个新工具的返回结构与 `NO_DATA` 语义；
  真实 SQLite 下种 5 条天气行后温度/湿度/风速/干湿分组、加权配速与相关系数。
- **插值与客户端**（`WeatherInterpolatorTest` / `OpenMeteoClientTest`）：
  线性插值、风向最短弧（350°→10° 取 0°）、`weather_code` 取最近整点、
  序列首尾边界、时间轴里出现无法解析的格子时整行压缩而不是 NPE、
  多坐标合并请求后按入参顺序对齐、429/5xx/404 标记可重试而 400 标记不可重试、
  缺字段保持 null 而不是填 0。
- **端点分流边界**（`WeatherBackfillEndpointTest`）：窗口内走 forecast、窗口外走 archive、
  `archive-lag-days` 把 forecast 窗口最老的几天交给 archive、延迟为 0 时保留原始窗口、
  延迟配得比窗口还大时按 0 处理、基准日期取自传入的 `now` 而非系统时钟。

---

## 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `status: "NO_DATA"` | 日期区间没覆盖到。先调 `describe_data_source` 看 `firstDate` / `lastDate` |
| `status: "AMBIGUOUS"` | `runId` 太短，按返回的 `candidates` 改用完整 id |
| 轨迹点数量比预期少 | 查看 `warnings`，是否有缺 `Time` 被跳过的点 |
| `warnings` 不为空但数据正常 | 字段级问题，通常不影响聚合；按告警类型决定是否修源文件 |
| `describe_data_source` 首次调用很慢 | `duplicateTimestampRunIds` 要逐条读轨迹，首次从库加载后命中缓存；只想看汇总时用其它工具 |
| 启动报 `Port 8080 was already in use` | 旧进程未退出，先停掉再启动 |
| 客户端连上但工具列表为空 | 检查是否误加了 `spring-boot-starter-web`，它会让应用退回 Servlet 栈 |
| 手写 HTTP 返回 400 / 406 | 缺少 `initialize`、`Mcp-Session-Id`，或 `Accept` 少了 `text/event-stream` |
| 启动报 `数据库结构版本 N 高于程序支持的 1` | 库文件被更新版本的程序写过，升级程序或换一个 `db-path` |
| 改了 TCX 内容但结果没变 | 文件 `size`/`mtime` 都没变（例如原地覆盖但时间戳被保留）。touch 一下文件或删库重导 |
| `warnings` 里有「数据目录 … 下没有找到 *.tcx 文件」 | `data-dir` 写错；相对路径会按进程工作目录解析 |
| `warnings` 里有「天气回填失败 N 条」 | 网络不通、被限流或 5xx。跑步数据不受影响，`retry-failed-after-days` 天后同步会自动重试 |
| `warnings` 里有「天气回填本轮提前结束」 | 接口连续 3 次可重试失败后主动收尾，避免一次同步被拖到几分钟。这些记录没落行，下次同步自动重试 |
| 天气一直是 `UNAVAILABLE` | 该时段超出数据源覆盖范围（太早的历史），或是室内跑步没有 GPS 坐标。属终态，不会重试 |
| 天气温度和体感差很多 | 正常。`start_apparent_c` 是体感温度，风速大、湿度高时与气温差异明显 |
| 想彻底重建 | 停服务，删 `db-path` 指向的文件（含 `-wal`/`-shm`），重启后自动全量导入 |

---

## 扩展点

1. **其它运动类型**：解析阶段不按运动类型过滤，新增骑行/游泳 TCX 会直接出现在结果里，可用 `sport` 参数筛选。工具名当前是 `list_runs` 等，如需通用可改为 `list_activities`。
2. **心率 / 步频**：若导出文件出现 `Trackpoint/HeartRateBpm`、`Cadence`，在 `TcxParser.parseTrackpoints` 增加字段并扩展 `TrackPoint`，
   同时给 `track_point` 加列——`SqliteSchema.MIGRATIONS` 追加一段脚本、`SCHEMA_VERSION` 加一，同步时按版本号自动升级。
3. **其它数据源**：新增 `XxxRepository implements HealthDataRepository`，组装 `RunDataset` 后工具层零改动。
4. **不用内存快照**：数据量涨到 GB 级后，可把 `RunAnalyticsService` 的过滤下推到 SQL，
   `dataset()` 退出主路径；当前体量（1000 次跑步 ≈ 120 万轨迹点，几十 MB）没必要。
5. **stdio 模式**：换成 `spring-ai-starter-mcp-server`，设置 `spring.ai.mcp.server.stdio: true` 与 `spring.main.web-application-type: none`，客户端用 `command` 启动 `java -jar`。
6. **鉴权**：HTTP 传输默认无鉴权，对外暴露前需加 Spring Security 或网关（WebFlux 用 `SecurityWebFilterChain`）。
