# sport-health-mcp-server

基于 Spring AI 的华为运动健康 **TCX** 数据 MCP 服务。把导出的跑步记录与穿戴数据变成 37 个可调用的 MCP 工具，让 Claude Code / Cursor 等客户端可以直接回答：

- 「我最近跑了哪些？」
- 「这个月一共跑了多少公里？」
- 「10-27 和 11-01 这两次哪次更快？」
- 「我最快的一次配速是多少？」
- 「这次每公里配速多少，有没有跑崩？」
- 「这次里最快的 1 公里 / 5 公里是多少？」
- 「这周比上周多跑了多少？」
- 「最近每天走了多少步，活动量够不够？」
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
Registered tools: 24
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
- **轨迹点输出**：`get_run_route` / `get_run_detail(includeTrack=true)` 返回的海拔与累计距离保留 2 位小数，经纬度保持原精度；累计爬升、海拔剖面等统计仍基于原始值计算。逐点心率、步频、累计距离来自华为导出，按时间就近匹配到轨迹点，TCX 来源为 `null`。
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
| `clean` | 全部检查项都为空且 `warningCount = 0` 时为 `true`：告警、0 距离、缺配速、缺起跑坐标、缺海拔、重复 Activity Id、配速离群、重复时间戳，一项非空即 `false` |

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
| `describe_data_source` | — | 数据目录；跑步记录数 `runningCount`（只含 RUN）与全部运动记录数 `allWorkoutCount`（含步行等其它项目）；跑步记录的来源文件 `runSourceFileCount` / `runSourceFiles`（去重文件名，不等于磁盘文件数）；日期范围、来源、RouteType、解析告警分类、`storage`（库路径、`scannedTcxFiles`/`importedTcxFiles`/`unchangedTcxFiles` 本次 TCX 同步统计、`health` 华为导出规模），以及 quality 块（0 距离、缺配速/坐标/海拔、重复 Activity Id、配速离群、重复时间戳） |
| `list_runs` | `startDate?` `endDate?` `sport?` `min/maxDistanceKm?` `min/maxPaceSecondsPerKm?` `weekday?` `sortBy?` `offset?` `limit?` | 区间内的记录（新的在前，分页），含距离/时长/配速/爬升；华为来源的记录另带 `avgHeartRate`/`maxHeartRate`/`minHeartRate`/`avgCadence`/`totalSteps`/`totalCaloriesKcal`/`recordId`/`sportTypeCode`/`subType`（TCX 来源为 `null`，不返回 `summaryData`） |
| `get_run_detail` | `runId` `includeTrack?` `trackOffset?` `trackLimit?` | 单次完整详情：起止时间、时长、距离、平均配速/速度、爬升与下降、海拔范围、起终点坐标、该次跑步的天气 `weather`（起跑/结束时刻的气温、体感、湿度、降水、风向风速、天气现象）；华为来源另带心率/步频/步数/热量/华为运动类型码，以及 `summaryData` 的可用性（`available`/`bytes`，原文交给 `get_run_summary_data`，不再把 2.5 KB 转义 JSON 塞进详情），可选附带轨迹分页 |
| `get_run_summary_data` | `runId` `fields?` `decode?` | 把华为 `summaryData` 原文摊开并换算单位：时长/距离/热量（原文毫秒、卡）、平均与最快配速、心率与步频、单公里分段 `kmSplits`（段配速、本段用时、累计用时、是否末段不足一公里）、心率区间分布、跑姿汇总（触地时间/垂直振幅/左右平衡）、训练效果与恢复时间、手机自报天气 `deviceWeather`。未建模字段用 `fields` 原样取，逗号分隔、支持 `a.b` 与 `a.*`（例如 `fields="bestPace,wearSportData.recovery_time"`），没匹配到的路径列入 `missingFields`。`-1`/`-101`/`3.4e38` 这类占位值转成缺省并在 `notes` 说明；`decode=false` 只返回原始字段。TCX 记录返回 `NO_DATA` |
| `get_run_streams` | `runId` `type?` `offset?` `limit?` | 读 `track_stream`：华为导出里没进轨迹点的原始流。不传 `type` 返回目录（每类的行数、语义、单位、数值范围、首行样例），因为一次的 `rs` 可能有两千多行；传了 `type` 再分页取行（默认 200、最多 5000），数值字段转成数字，`rs` 额外给 `speedKmh`/`paceText`，`pm2` 给 `endDistanceMeters`/`paceText`。`pm2`↔`p-m`、`bpm2`↔`b-p-m` 是同一份采样的两种写法，传任一都会命中存在的那个（`type` 是实际取到的类型，两者不同时另给 `requestedType`） |
| `get_running_dynamics` | `runId?` `startDate?` `endDate?` `groupBy?` `splitKm?` `offset?` `limit?` | 跑步动态（跑姿）：触地时间、垂直振幅、触地平衡（左右平衡）、步幅、步频。传 `runId` 看单次：`summary` + 按 `splitKm`（默认 1，最小 0.1）分段 `segments` + `halves`（前后半程与 `delta`，后半程减前半程）；不传 `runId` 按区间汇总：`coverage`（`dynamicsRuns` 多少条真带跑姿数据、`strideRuns`、覆盖率与一句读法）、`summary`（各项 `count`/`average`/`min`/`max`）、`trend`（`groupBy=month` 默认，可选 `week`，`none` 不出）、逐次记录分页。跑姿三项只有连接跑步动态传感器的记录才有；步幅由「距离 ÷ 步数」折算。`runId` 与日期互斥、`splitKm` 只用于单次、`groupBy` 只用于区间，混用返回 `INVALID_ARGUMENT` |
| `get_run_route` | `runId` `offset?` `limit?` `format?` `maxPoints?` `includeSensors?` | 轨迹点分页（时间/纬度/经度/海拔，海拔与累计距离保留 2 位小数），每点另带 `heartRate`/`cadence`/`distanceMeters`（华为来源才有，TCX 为 `null`；`includeSensors=false` 时省略这三个键）；可输出 GeoJSON，此时传感器放在与 `geometry.coordinates` 等长同序的 `sensors` 平行数组里；`maxPoints` 降采样后会忽略 `offset`/`limit` |
| `get_running_summary` | `startDate?` `endDate?` | 次数、总距离、总时长、总爬升、平均配速、最快/最长记录 |
| `get_daily_running` | `startDate?` `endDate?` `groupBy?` | 按天/周/月拆分，只含有记录的周期，`day` 时额外返回每天一条 |
| `get_personal_bests` | `startDate?` `endDate?` `category?` `distancesKm?` | `category`：`all`（默认）/`distance`/`pace`/`duration`/`climb`，或距离类别 `best1k`/`best3k`/`best5k`/`best10k`/`half`/`marathon`（别名：`5k`、`半马`、`全马`…）。`distancesKm` 直接指定最佳努力的目标距离，默认 `[1,5]`，例如 `[1,5,10,21.0975,42.195]`；结果按 `best1k`/`best10k`/`bestHalf`/`bestMarathon` 命名，区间内跑不到的目标列在 `unavailableDistancesKm` |
| `compare_runs` | `baseRunId` `otherRunId` `includeSplits?` `splitKm?` | 距离/时长/配速差异 + 一句话结论，可选附带两次的分段（`splitKm` 默认 1，与 `get_run_splits` 同口径；缺 GPS 的那次会列在 `splits.noGpsRunIds`） |
| `get_run_splits` | `runId` `splitKm?` | 沿轨迹按固定距离（默认 1 公里）分段：配速、用时、本段爬升、净海拔变化、平均坡度、坡度修正配速（GAP）、最快/最慢段、前后半程对比、负分段、配速标准差，并返回 `gpsDistanceKm` 与全程 GAP 汇总。华为来源的记录另带 `vendorSplits`（华为官方单公里配速，来自 `track_stream` 的 `pm2`）与 `splitCrossCheck`（两边按段对齐后的平均/最大配速差与结论） |
| `get_official_splits` | `runId?` `startDate?` `endDate?` `unit?` `offset?` `limit?` | 华为官方分段配速与 GPS 分段的逐段对比。`unit=km`（默认）读 `pm2` 的单公里分段，`unit=mile` 读 `bpm2` 的单英里分段（英制，1 英里 = 1609.344 米），`unit=both` 两套并列（只用于单次）。传 `runId`：`units.{km\|mile}` 内给官方每段的距离/用时/配速、同段 GPS 配速与 `deltaSecondsPerKm`（官方 − GPS，正数=官方更慢）、累计差值 `cumulativeDeltaSeconds`、`fastest`/`slowest`，以及 `comparison`（`comparedSplits`、`meanAbsDeltaSecondsPerKm`、`maxAbsDeltaSecondsPerKm`、`maxDeltaAtKm`、`signedMeanDeltaSecondsPerKm`、`within5/15SplitCount`、`verdict`）与 `fastestComparison`（官方最快段 vs GPS 最快段）。不传 `runId`：`coverage`（多少条带官方分段）+ `summary`（各次平均差的中位数/P90/方向）+ 逐次记录分页。华为没有「最佳配速」字段，最快段由官方分段取最小值 |
| `get_best_efforts` | `runId` `distancesKm?` | 单次跑步内 1K/5K 等目标距离的最佳用时与折算配速（默认 `[1,5]`），附 `gpsDistanceKm`，超出本次距离的目标列入 `unavailableTargetsMeters` |
| `get_training_trend` | `period?` `count?` `endDate?` | 连续周/月趋势（`count` 默认 4、最多 52），含跑量与配速相对上一周期的变化 |
| `get_consistency` | `startDate?` `endDate?` | 打卡天数占比、当前/最长连续天数、平均间隔、日历热力数据；当前连续以最后一次记录为锚点 |
| `get_elevation_profile` | `runId` `bucketMeters?` `smoothingMeters?` | 按距离分桶的海拔剖面（默认 100 米一桶、最小 10；平滑默认 1 米、0 表示不平滑），含累计爬升下降与坡度 |
| `get_stop_points` | `runId` `minStopSeconds?` `maxDisplacementMeters?` | 检测停顿（默认 30 秒、30 米）：相邻轨迹点间隔≥阈值且位移≤阈值，返回时长、位置与总停顿 |
| `predict_race_time` | `startDate?` `endDate?` `distanceKm?` `includeOfficial?` | 用区间内 1K/5K/10K/半马/全马最佳努力做 Riegel 外推（`T2 = T1 × (D2/D1)^1.06`）。不传 `distanceKm` 时输出 5K/10K/半马/全马；有实测距离时标记 `source=ACTUAL`，外推标记 `RIEGEL`，并给出参考记录与 `HIGH/MEDIUM/LOW` 置信度。`includeOfficial=true` 时额外返回 `official` 块：华为官方跑力给出的同距离成绩（`source=HUAWEI_OFFICIAL`）、逐行的 `officialSource`/`riegelSource`/`deltaSeconds`/`deltaText`，以及快照日期与时效（`withinRange`/`staleDays`）；官方没有对应档位的目标距离列在 `unavailableTargetsKm` |
| `get_run_level` | `date?` `includeHistory?` `historyDays?` | 华为官方跑力（`run_level` 每日快照）：跑力值 `runLevel`、A/E/I/M/R 五档配速（华为原文是 km/h，这里换算成秒/公里与 `4'35"/km` 文本）、官方给的 1/3/5/10 公里与半马/全马成绩。不传 `date` 取最新一天，传了取该日或之前最近一天（`note` 说明回退）；`includeHistory=true` 附跑力趋势，`historyDays` 默认 90、最大 3650 |
| `get_training_load` | `endDate?` `metric?` | 训练负荷与 ACWR：7 天急性、28 天慢性、`ACWR = 急性 / (慢性/4)`、周跑量增幅、连续训练天数、休息日、单调性与 Strain，并给出调整建议。`metric` 可选 `distance`（默认，公里）或 `duration`（分钟）；数据覆盖不足时明确标注置信度 |
| `classify_run` | `runId` | 识别训练类型：轻松跑、长距离跑、节奏跑、间歇/变速跑、比赛/测试跑、恢复跑。基于距离、时长、配速相对个人中位数与分段波动，并用平均心率所在区间（Z1~Z5）交叉验证，返回判定依据、心率区间与置信度（无 GPS 时降为 LOW） |
| `get_training_mix` | `startDate?` `endDate?` | 区间内的训练类型构成：各类型的次数、距离、距离占比，并逐条列出分类结果。逐条分类会按需解析轨迹，首次调用稍慢 |
| `get_sleep_summary` | `startDate?` `endDate?` `groupBy?` | 睡眠汇总。`groupBy=day`（默认）返回逐夜 `nights`，`week`/`month` 返回按周期聚合的 `periods`；两者都带 `summary`（平均睡着/卧床/清醒时长、醒来次数、潜伏期、深睡与 REM 占比、评分、效率、HRV、血氧）与 `coverage`（`nights`/`sessionDays`/`reportDays`/`stageOnlyNights`/`scoredNights`/`spo2Nights`/`note`）。评分、效率、HRV、血氧只有 `sleep_report` 才有（2026-06-13 起），更早的夜晚是 `null` 而不是 0；血氧只有个别夜晚有值。醒来次数由分期里的连续清醒段推算（详见「睡眠口径」） |
| `get_sleep_detail` | `date?` `startDate?` `endDate?` `includeTimeline?` | 睡眠详情（一夜或最多 14 夜）。每夜给：合并后的分期时间线 `timeline`（深睡/浅睡/快速眼动/清醒，相邻同阶段已合并，`timelineSegments` 是真实段数）、每一次醒来的起止 `wakeEpisodes`、睡眠碎片 `fragments`（设备认定的连续睡眠块 `items` 与块间间隙 `gaps`，来自 `sample_sequence` type=700008）、各阶段时长与占比 `stages`、报告补充指标 `report`（平均心率/呼吸率/打鼾指数/上床·睡着·起床时刻）、入睡前的白天午睡 `daytimeNap`。`date` 与区间参数互斥；都不传取最新一夜。基础字段与 `get_sleep_summary` 完全一致（同一套算法），缺来源时 `stages`/`report` 为 `null`、`fragments.available=false`，并用 `coverage` 的 `stageNights`/`reportNights`/`fragmentNights` 说明（详见「睡眠口径」） |
| `get_sleep_trend` | `startDate?` `endDate?` `groupBy?` `metric?` | 睡眠趋势：按 `week`（默认）或 `month` 切周期，每个周期给 `values`（平均睡着时长、评分、效率、HRV、醒来次数、深睡占比、潜伏期）与相对上一周期的 `deltas`，再用 `trend` 对 `metric` 指定的指标给结论（`slopePerPeriod`、`r2`、`direction`、`assessment`、`first`/`last`/`best`/`worst`）。`metric` 可选 `asleep`（默认）/`score`/`efficiency`/`hrv`/`wakeCount`/`deep`/`latency`；`groupBy=day` 会被拒（按天是 `get_sleep_summary` 的活）。只用完整周期拟合，被区间截断的首末周期标 `complete=false` 且不参与；`direction` 按区间累计变化判定，`assessment` 已折算好「越高越好/越低越好」。周粒度最多 78 个周期、月粒度 36 个（详见「睡眠口径」） |
| `get_daily_vitals` | `startDate?` `endDate?` `groupBy?` | 每日体征：静息心率、HRV（RMSSD）、压力均值/峰值、最大摄氧量，以及全天自动心率的均值/最低/最高。`groupBy=day`（默认）逐日 `days`，`week`/`month` 按周期聚合 `periods`（首末被区间截断的周期标 `complete=false`）；都带 `coverage`（每个指标各自的有值天数）、`availability`（各指标在全库的可用范围）、`metrics`（区间均值/极值/最新值、28 天基线 `baseline`、相对基线的 `deltaVsBaseline`、首末 7 天对比 `change` 与 `direction`/`assessment`）、`heartRate` 与 `notes`。静息心率从 2025-11-08、HRV 与压力从 2026-06-12 才有，最大摄氧量只有 104 天且 2025-09 之后断档 14 个月；缺数据的指标是 `null`，整段没有时干脆不出现在 `metrics` 里（详见「体征口径」） |
| `get_hrv_trend` | `startDate?` `endDate?` `period?` `includeNights?` | 心率变异性（RMSSD，毫秒）的长期趋势，`period` 取 `week`（默认）/`month`。**口径是睡眠 HRV**：每晚取睡眠会话窗口内所有样本的均值，按起床日归属。返回周期均值/最低/最高、`direction`（数值涨跌）、`assessment`（好坏，HRV 升 = improving）、`slopePerPeriod`、`r2`、首末 `change`，以及逐夜明细（`includeNights=false` 可关）。**不要拿 `get_daily_vitals` 的 `hrv` 来校验** —— 那是日历日全天均值，日间样本多时高出 15~20 ms（详见「HRV 口径」） |
| `get_daily_activity` | `startDate?` `endDate?` `groupBy?` | 日常活动量：每天步数（含走路 / 跑步拆分）、距离、**活动热量**、中高强度分钟、**站立次数**。`groupBy=day`（默认）逐日 `days`，`week`/`month` 按周期聚合 `periods`（首末截断的周期标 `complete=false`）；都带 `coverage`（`recordedDays`/`missingDays`/`sparseDays`）、`summary`（合计、日均、**中位数**、最高/最低的一天、久坐日数）、`goals`（步数 8000 / 中高强度 30 分钟 / 站立 12 次三个日目标的达成天数 + 末尾 7 天中高强度合计是否够 WHO 每周 150 分钟，含 `weeklyActiveMinutesDays` 说明窗口够不够 7 天）、`trends`（最近 7 天 vs 前 7 天的日均变化）与 `notes`。日均只按有记录的天算；站立是**事件条数**不是分钟数（详见「日常活动口径」） |
| `get_activity_minutes` | `date?` `type?` | **单日**分钟级明细：每一分钟的运动类型（走路 / 跑步 / 站立事件）、这一分钟的步数、距离、活动热量、是否中高强度、来源设备。`type` 取 `activity`（默认，不含站立事件）/`all`/`walk`/`run`/`stand`。除 `minutes`（逐分钟）外还给 `types`（各类型多少分钟/步数/距离/热量）、`blocks`（同类型且前后相差 ≤2 分钟拼成一段，如「06:02~07:04 跑步 62 分钟」）与 `summary`（当天合计，与 `get_daily_activity` 同源、同一天必须相等）。同一分钟被两台设备各记一条时只留步数大的那条，`summary.rawRows - summary.records` 就是合并掉的条数（详见「分钟级口径」） |
| `get_stress_series` | `date?` `resolution?` | **单日**压力曲线（华为压力分 0~100）：样本数、最低/平均/最高、首末测量时刻、采样间隔中位数、按 `resolution`（`30min`/`hour` 默认）分桶的曲线、各档位样本数、最高与最低那一次测量。压力是约 30 分钟一次的离散测量；**最低分恒为 15 是算法下限**，全库最大值只有 53，`high` 档永远不会出现（详见「压力与血氧口径」）。多日压力水平用 `get_daily_vitals` |
| `get_blood_oxygen` | `startDate?` `endDate?` | 血氧饱和度（SpO₂）的**记录与覆盖**：按天汇总（样本数、最低/平均/最高、低于 95% 的条数）、明细样本、设备下发的低氧提醒（阈值 90%）。**刻意不给趋势** —— 全库只有 12 天有数据（2026-06-12~17 与 2026-08-03~08），之后设备再没上报；两段之间隔一个半月，拟合出的任何斜率都只是「有没有开连续血氧」。没有记录 ≠ 血氧正常 |
| `get_heart_rate_series` | `date?` `resolution?` | **单日**自动心率曲线（`health_metric` 的 `DYNAMIC_HEART_RATE`）：`stats`（采样点数、最低/平均/最高、首次与末次采样时刻、跨度、采样间隔中位数与最长空档）、`peak`/`lowest`（心率与时刻，外加 `sustainedMinutes` 连续维持时长）、按 `resolution`（`minute`/`5min`（默认）/`15min`/`hour`）聚合的 `buckets`、当天运动时段 `activities`、口径提示 `notes`。采样密度不是固定的：早期每分钟一条、近期静息 10 分钟一条、运动时加密，看 `medianGapMinutes` 而不是点数多少；单点极值可能只是翻身蹭到传感器（详见「心率曲线口径」） |
| `get_body_composition` | `startDate?` `endDate?` `gender?` `heightCm?` | 体脂秤数据（体重、BMI、体脂率、脂肪量、肌肉量、蛋白质、体水分、骨盐量、内脏脂肪等级、腰臀比、基础代谢、体年龄、体成分评分、称重心率）。**同一台秤上有多个用户**，所以分两种模式：不给 `gender` + `heightCm` 时只返回 `configs`（每套配置的条数、日期范围、体重区间、带体成分的条数）并要求补参数，**不会给出任何合并后的体重统计**；两个都给才返回 `measurements`（按时间升序，同日多次称重逐条保留）、`trends`（体重/BMI/体脂率/肌肉量/内脏脂肪/评分的首末值与 `change`）、`byMonth`（按月均值，看长期趋势用它）。默认区间是**这套配置自己最后一次称重往前 90 天**；明细上限 240 条（详见「体成分口径」） |
| `get_weather_impact` | `startDate?` `endDate?` `minDistanceKm?` | 只读已回填的 `run_weather`，按起跑气温、湿度、风速、有无降水分组，返回每组次数、加权平均配速、相对整体基线的快慢秒数，以及气温与配速的 Pearson 相关系数。另带 `weatherCoverage`：区间内跑步总数、拿到天气的条数、缺失条数与原因拆分（`failed`/`unavailable`/`notFetched`）、覆盖率与一句结论。不联网，缺天气时提示调 `sync_weather` |
| `sync_weather` | `retryFailed?` | 只补天气：为还没有天气的跑步记录联网抓取起跑/结束时刻的天气，**不重导数据、不重建快照**。返回本轮 `ok`/`unavailable`/`failed`/`noLocation`/`deferred` 条数、失败原因 `problems` 与同步后的整体覆盖率 `coverage`。`retryFailed` 默认 `true`，即忽略 `retry-failed-after-days` 冷却、立刻重试上次失败的记录；`sport.weather.enabled=false` 时返回 `DISABLED` |
| `reload_data` | `weather?` | 增量同步：按 `(文件名, size, mtime, sha256)` 只解析变化的 TCX 与华为导出文件（JSON / xls / xlsx），无需重启。`weather` 默认 `true`，写完顺带补一轮天气；传 `false` 时整段同步不发起任何网络请求，天气留给 `sync_weather` |

### 睡眠口径

- **两套来源**：分期（`sleep_session` + 同批 `health_metric` 区间）覆盖全部历史，能算卧床/睡着时长与深睡/浅睡/快速眼动；
  官方报告（`sleep_report`）只有 2026-06-13 之后才有，评分、效率、HRV、血氧、入睡潜伏期都只在这里。
  所以先看 `coverage`：`sessionDays` 有分期的夜数、`reportDays` 有报告的夜数、`stageOnlyNights` 只有分期、
  `spo2Nights` 真上报了血氧的夜数（设备常年不测，全库 355 夜里只有 10 夜有值）。
- **`null` 表示缺数据，不是 0**：没有报告的夜晚 `score`/`efficiencyPercent`/`hrvMs`/`spo2Percent`/`sleepLatencyMinutes` 都是 `null`；
  `summary` 里每个平均值都配自己的分母（`reportNights`/`hrvNights`/`spo2Nights`/`stageNights`）。
  报告里的哨兵值统一折成 `null`：`sleep_efficiency = 255`、`avg_oxygen_saturation = -1`、`deep_part = 255`。
- **醒来次数**：报告的 `wakeCount` 恒为 0（设备没上报），只能用分期还原——把间隔 ≤1 分钟的清醒分钟粘成一次醒来，
  去掉贴着会话首尾的两段（开头那段就是报告的入睡潜伏期，结尾那段是起床前的清醒），剩余 ≥2 分钟的段才计数。
- **`awakeMinutes` 是 WASO 口径**（睡着之后、起床之前的清醒），所以 `timeInBed ≥ asleepMinutes + awakeMinutes`，
  差额是潜伏期与起床前清醒；有报告的夜晚这个差额与 `sleepLatencyMinutes` 对得上。
- **午睡不纳入**：华为把午睡单独标成 `PROFESSIONAL_SLEEP_NOON`，还经常和夜睡粘成一段，
  混进夜睡会让「睡了多久」凭空变大，所以只报夜睡（06-17 这类「夜睡被标成 NAP 会话」的夜晚改用报告窗口重新框分期）。
  详情里的 `daytimeNap` 是例外：只取「结束早于当晚入睡、距今 24 小时内、08:00~20:00 开始」的段，
  窗口外的直接丢（库里确实存在 01:00、23:00 起点的 NOON 段，那是夜睡被标错了），相邻段合并后再给时长。
- 默认区间取**睡眠数据自身**的覆盖范围，不是跑步数据的（睡眠比跑步早开始几个月）。

**详情的三处额外口径**（`get_sleep_detail`）：

- **睡眠碎片**（`sample_sequence` type=700008）：`detail_data` 是华为私有二进制——开头 4 字节版本号 1、
  接着「名字长度 + `sleep_fragment`」、最后 16 字节里的前 8 字节是两个小端 uint32 的 epoch 秒（碎片起止），
  尾部 8 字节未解读；749 条记录长度全是 72 字节。解码做三重校验（块名、起止为正、结束落在记录时刻 ±24 小时内），
  不满足就当没有数据。碎片是**设备眼中的连续睡眠块**，块与块之间的 `gaps` 才是设备口径的清醒；
  它与 `wakeEpisodes`（分期口径）在约 1/3 的夜晚差 1 次，差的全是 1~2 分钟的空隙，两份都给出、不取平均。
  碎片只有 2026-06-12 之后的 152 天有，更早的夜晚 `fragments.available=false`。
- **时间线**：`health_metric` 是逐分钟写一行，直接输出一夜会有近 500 行；`timeline` 把相邻同阶段合并
  （实测中位数 43 段、最多 75 段），上限 240 段，超过时 `timelineTruncated=true` 且 `timelineSegments` 仍是真实段数。
  `stages.wakeMinutes` 含入睡前那段，所以会比 `awakeMinutes`（WASO）大，差额就是潜伏期加起床前清醒。
- **报告时刻**：`sleep_report` 不存时区，里面的 epoch 秒按 +08:00 还原成 `bedTime`/`fallAsleepTime`/`risingTime`；
  写成 `systemDefault()` 反而会在服务器跑在 UTC 时整体挪 8 小时。`deepPart`、`rdi`、`validData`、`*DayToBaseline`
  的单位与含义都没确认（`rdi` 全库恒为 0、`validData` 是浮点位模式），故不输出。
- 详情一次最多 14 夜（带时间线约 3.3 KB/夜）：更长区间先用 `get_sleep_trend` 或 `get_sleep_summary` 看变化，再挑夜里细看。

**趋势的四处额外口径**（`get_sleep_trend`）：

- **`complete` 与 `nights` 是两件事**：`complete=false` 表示这个周期被查询区间截断（区间从周三开始，那一周只剩 5 天），
  此时 `expectedNights` 已按区间折算；`nights < expectedNights` 则是「区间覆盖了，但设备那几天没记录」。
  把两者混起来，会把「没戴表」误读成「区间没覆盖」。
- **只用完整周期拟合**：被截断的首末周期、以及该指标没有值的周期（2026-06-13 之前的评分/HRV/潜伏期）都排除，
  `periodsUsed` / `periodsExcluded` 把这件事写在明面上；完整周期不足 2 个时退回用现有周期，`method` 会变成
  `leastSquaresOverAvailablePeriods`、`direction` 为 `unknown`。
- **方向按区间累计变化判，不按每周期斜率**：月粒度的 5 分钟/月等于一年 60 分钟，按每周期比会把一整年的下滑
  说成「平稳」。实现上用 `slopePerPeriod × (n-1)` 与指标的最小有意义变化比较——睡着 5 分钟、评分 1 分、
  效率 1%、HRV 2 ms、醒来 0.3 次、深睡 1%、潜伏期 3 分钟。
- **`direction` 与 `assessment` 分开**：前者是数值方向（`up`/`down`/`flat`），后者是价值方向
  （`improving`/`worsening`/`flat`）——醒来次数下降是 `improving`，调用方不必自己换算。
  `r2` 说明这条拟合线有多可信，`r2` 偏低时 `notes` 会明确写「波动大于趋势，别当成稳定趋势」。
- 周粒度最多 78 个周期（约 1.5 年、约 46 KB）、月粒度 36 个（约 3 年、约 21 KB），超出返回 `INVALID_ARGUMENT`
  并提示缩短区间或换粒度；单个周期约 0.6 KB（`values` + `deltas` 共 14 个数值）。

### 日常活动口径

`get_daily_activity` 只读 `daily_activity`（368 天，2025-11-08 ~ 2026-11-10），它由 `activity_minute`
（每分钟运动数据，7 万行）按天聚合而来，和 `daily_vitals` 不同，**没有指标级稀疏问题**：只要有分钟记录，
这一天就有行。所以这里只区分「整天没记录」(`missingDays`) 与「记录过少」(`sparseDays`)。

- **站立是事件条数，不是分钟数**。华为把站立记成整点的 `sportType=13` 事件（`duration` 恒为 0），
  一天 3~22 条，对应 App 里的「站立 18/12 次」。v9 之前这里求的是 `is_stand=1` 的分钟数之和，
  因为时长恒为 0，得到的其实是「走路分钟里被标了站立的那些」，全库均值只有 2.0；
  v9 迁移改成 `stand_events` = 事件条数（全库 5 222 条 / 368 天，均值 14.2）。
- **热量是活动热量**：`activity_minute.calorie`（卡 ÷ 1000）逐分钟求和，跑步约 11 kcal/min、
  走路约 2.9 kcal/min，**不含基础代谢**，不等于「今天一共消耗多少千卡」。
- **同一分钟只算一次**（v10）。`activity_minute` 的主键是 `(start_time, sport_type)`，看着一分钟一行，
  实际全库 2 208 个分钟存了两条 —— **无一例外是两台设备同时记的同一分钟**（第三台设备还把走路报成
  `sport_type=2`，另两台报 5）。v10 之前逐行相加，步数虚高 3.45%（2026-06-14 那天多算 20%），
  现在按「步数最大的那条」去重后再聚合；`walkSteps` 把 2 与 5 都算走路，只认 5 会让换设备后的
  14.4 万步凭空消失。中高强度取两台设备的**并集**（该列大量 NULL，NULL 是「没上报」不是「否」）。
- **目标值是外生的**：步数 8 000/天（华为默认，不是流传更广的 10 000）、中高强度 30 分钟/天
  （WHO 每周 150 分钟摊到七天）、站立 12 次/天（华为站立目标）。工具不猜目标，也不从数据里反推。
- **所有均值只在有记录的天上算**（分母是 `summary.days`），缺的天不是 0。
  `averageSteps` 与 `medianSteps` 并列：步数分布很偏，一次 3.5 万步的长距离就能把日均拉高两千步。
  `lowActivityDays`（< 5 000 步的久坐日）**已排除 `sparseDays`**，否则「导出那天只记了 69 步」
  会被当成久坐。
- **周线锚在末尾 7 天**，不是自然周：`weeklyActiveMinutes` 是区间最后一个有记录的日子往前 7 天的
  中高强度合计，只有 `weeklyActiveMinutesDays = 7` 时 `weeklyActiveMinutesMet` 才能当结论看
  —— 否则「查单日得到 40 分钟未达标」这种话没有意义。
- **趋势是最近 7 天 vs 前 7 天的日均**，方向按 ±5% 判：步数逐日波动主要来自「今天有没有跑步」，
  日粒度看趋势只会得到锯齿。任一侧没有记录时 `direction=unknown`（看不出，不是没变化）。
- **默认区间是「数据最新一天往前 30 天」**；`groupBy=day` 最多 92 天，`week`/`month` 放宽到约 3 年 / 5 年。
  周期边界自己算（周一到周日 / 1 号到月末），被区间截断的首末周期标 `complete=false`。

### 分钟级口径

`get_activity_minutes` 只读 `activity_minute`（70 521 行 / 368 天，正常一天 120~500 条），
回答的是「这一天每一分钟在做什么」，和 `get_daily_activity` 的多天视角互补。

- **一条记录 ≈ 一分钟**，`start_time` 是本地墙上时间（+08:00）、秒恒为 0，所以返回值里的
  `time`/`endTime` 只到 `HH:mm`。没有跨零点的记录：凌晨活动落在当天的 `00:xx`。
- **运动类型只有三个码**：4 = 跑步、5 = 走路、13 = 站立事件；`2` 是第三台设备对走路的上报码，
  归一成 `walk`。`types[].sportTypes` 会把归到同一类型的原始码列出来。
- **同一分钟只留一条**（2 208 个分钟原本有两条，全是跨设备重复），按「步数 → 距离 → 热量」降序挑；
  中高强度取并集。被合并的条数就是 `summary.rawRows - summary.records`，
  `minutes[].sourceRows = 2` 标出具体是哪一分钟。
- **站立事件不是「站了 N 分钟」**：`sport_type=13` 是整点触发的标记（`duration=0`、0 步），
  所以默认 `type=activity` 不把它混进分钟列表，只在 `summary.standEvents` 与 `types` 里出现。
  要单看就 `type=stand`。注意 `is_stand` 列**不是**站立事件（634 条走路、91 条跑步也被标了），
  不能用它替代。
- **`blocks` 允许 ≤2 分钟空档**：手表会漏记个别分钟，严格相邻会把一次跑步拆成七八段；
  空档不计入 `minutes`，所以 `summary.activityMinutes` 可能小于 `from`~`to` 的跨度。
- **默认日期是「数据最新一天」，不是今天**；导出当天（2026-11-10）只有 6 条记录，
  `sparse=true` 并用 `notes` 提示别把 69 步当成整天。
- **日合计与 `get_daily_activity` 同源**（同一份去重后的分钟），同一天的
  `summary.steps` / `distanceKm` / `caloriesKcal` / `activeMinutes` / `standEvents` / `records`
  必须逐项相等 —— 对不上说明派生表没重建。

### 体征口径

`get_daily_vitals` 只读 `daily_vitals`（一天一行），四类指标的覆盖率差得很远，所以**所有统计都按指标各自算分母**：

- **可用起点**：静息心率与全天心率 2025-11-08 起 368 天、中间无缺口；HRV 与压力 2026-06-12 起 152 天；
  最大摄氧量只有 104 天，且 2025-09-21 之后断到 2026-11-01 才有下一条——那 14 个月里跑了 185 次却没有一条
  VO2MAX，是设备停止上报，不是没运动。区间早于这些起点时对应字段是 `null`（缺数据，不是 0），
  整段都没有时该指标不出现在 `metrics` 里，`notes` 会说明它全库的范围。
- **日值的聚合口径在派生表里定死，工具层不再重算**：静息心率取当天多条里的**最小值**（华为当日静息心率
  就是睡眠期最低的稳定心率，实测一天 2~8 条，取均值会偏高 2~4 bpm）；HRV 与压力取当天采样的**均值**
  （HRV 一天约 65 条、压力约 32 条）；VO2max 取当天最大值。
- **`heart_rate_samples = 0` 的行只有 VO2max**（早期 100 天），三个心率列全为 `null`；
  判断「当天有没有心率」看这一列，不能看 `heart_date` 是否存在，否则心率覆盖率会虚高。
- **三个对比量各管一件事**：`baseline` 是区间末端往前 28 天的均值（含最近 7 天，始终取库里真实数据、
  不受查询区间限制）；`deltaVsBaseline` 是最近 7 天相对它的偏离；`change` 是区间内部的首末 7 天对比。
- **首末窗口锚在该指标自己的有值区间**，不是查询区间的端点：查一年时 HRV 只有后半年的数据，
  用区间首端取窗口会全空。有值跨度不足 14 天（两窗口重叠）或有值天数不足 7 天时不做对比，
  `change` 为 `null`、`direction` 为 `unknown`——那是「看不出变化」，不是「没变化」。
- **`direction` 与 `assessment` 分开**：前者是数值方向，后者是价值方向。静息心率下降是 `improving`、
  HRV 下降是 `worsening`，看 `metrics.<指标>.higherIsBetter` 就知道该往哪边读。
  阈值按各自噪声定：静息心率 2 bpm、HRV 4 ms、压力 2 分、VO2max 1 ml/kg/min、全天心率 2 bpm。
- **`heartRate.average` 是全天自动采样的均值，含睡眠段**，比「日间平均心率」低约 10 bpm；
  `heartRate.highest` 只是采样峰值，不是实验室最大心率，别拿它做区间划分。
- **默认区间是「数据最新一天往前 30 天」**，不是「今天往前 30 天」：库通常落后于当天，
  用今天会把尾巴留成空白。`groupBy=day` 最多 92 天，`week`/`month` 分别放宽到约 3 年 / 5 年。

### 体成分口径

`get_body_composition` 只读 `body_measurement`（308 条），它最大的特点是**一台秤、多个人**：

- **7 组配置，必须分开**：`186/男` 141 条（带全套体成分，2025-12 起）、`168/女` 132 条、`171/女` 25 条、
  `193/男` 3 条，另有 7 条 `gender` 为 NULL（其中 1 条连身高都没有）。全表体重 55.85~85.35 kg
  是四五个人拼出来的，**任何「平均体重 / 体重变化」都必须先落到一套配置上**。
  所以工具强制「不传 `gender` + `heightCm` 只能看配置汇总」，不会自动挑条数最多的那套去算。
- **`gender` 在库里是 0/1/NULL**（1 = 男，0 = 女），对外统一成 `male` / `female` / `unknown`；
  `heightCm` 用 `ROUND(height_cm)` 匹配，避免浮点尾数把同一个人拆成两组。
- **同一身高可能有「有性别」与「性别未知」两组**（如 168cm 下有 `女` 132 条和 `性别未知` 5 条，
  体重区间 67.25~71.5 与 67.85~70.35 高度重叠）。这很可能是同一个人的部分记录没带性别字段，
  但工具**不会自动合并** —— 只在 `notes` 里点明，让调用方决定。
- **体成分字段不是每条都有**：142 条带体脂率，其余只称了体重。`trends` 里各指标的 `count`
  是「该指标有值的条数」，所以会比明细条数少；只称体重的配置，`trends` 里就只有 `weight` 一个键。
- **`bodyFatKg` 有 13 条虽有体脂率却没这个字段**，这里保持 `null` 不做换算（定义上是
  `体重 × 体脂率 / 100`，需要时调用方自己算），`notes` 会点明条数。
- **同一天可以称多次**（141 条里 29 条与上一条相隔不到 1 天，如同一天 05:08 与 05:09）。
  明细逐条保留，`byMonth` 的 `measurements` 与 `days` 分开给，均值把每次称重都算进去。
- **两个默认区间不一样，是刻意的**：配置汇总默认看**全库范围**（只开 90 天会漏掉很久没上秤的那套，
  调用方就永远不知道该传哪个 `heightCm`）；明细默认锚在**这套配置自己的最后一次称重**往前 90 天
  （用全库最新日当锚点，查 186cm/男 会常常是空的 —— 他从 2026-11 起就称得稀了）。
- **明细上限 240 条**（约 70 KB），超了报 `INVALID_ARGUMENT` 并提示缩短区间。

### HRV 口径

`get_hrv_trend` 只读 `health_metric` 的 `HEART_RATE_VARIABILITY`（9 885 点 / 152 天，约 10 分钟一条）。

- **只有睡眠口径，不用全天均值**。实测全库日间（06:00–22:00）均值 68.7 ms、夜间 48.2 ms，差 20 ms
  —— 比任何真实变化都大。而全天均值还取决于「那天白天测了几条」：2026-11-10 只有 1 条日间样本，
  全天均值 55.0 几乎等于夜间值；2026-11-04 有 26 条，全天均值被拉到 63.6。
  对 `daily_vitals.hrv_rmssd` 拟合斜率，量到的是「白天测得多不多」。
- **一夜不等于一个日历日**。一晚跨越两天（22:12 睡、05:05 起），按 `sleep_session.sleep_date`
  （起床日）归属，窗口取「会话 start ≤ 样本 ≤ 会话 end」。按 `substr(start_time,1,10)` 切会把
  一夜劈成两半、每半还混着别的一夜的碎片 —— 这是最容易写错的地方，单测专门守了这条。
- **同一日期两套数字并存，都对**：本工具的 2026-11-09 夜 = 48.9（11-08 21:29~11-09 05:25 窗口内），
  `get_daily_vitals` 的 2026-11-09 = 59.2（11-09 日历日全天）。工具会在 `notes` 里说明，
  **不要互相校验**。
- **每夜至少 10 条样本才算有效**（实测每夜 24~49 条）。日间均值另有 `daySamples`，
  少于 5 条时 `dayRmssd` 给 `null` —— 实测有一条 126 ms 的孤立样本，直接平均会让那一周出现 100+ 的假高点。
- **`r2` 在只有 2 个周期时必然接近 1**（两点定一条直线）。`periodsUsed == 2` 时 `notes` 会明说，
  别把它当成「趋势很稳」的证据。
- 方向判据与睡眠趋势一致：按 **`slope × (周期数−1)`** 的区间累计变化跟 4 ms 阈值比，
  不按每周期斜率 —— 周粒度 0.52 ms/周 = 一年 27 ms，按每周期比会判成 flat。

### 压力与血氧口径

两者都是 `health_metric` 里的**稀疏单点**，不是曲线：

- **压力约 30 分钟一个点**（4 837 点 / 152 天），所以分桶最小 30 分钟，再细没有意义。
- **15 分是算法下限**：全库 19.4% 的样本正好是 15，真实值域只有 15~53。从没到过 60，
  所以「中等 / 高压」两档在这份数据里不会出现 —— **没到高压不代表那天不累**，
  别用它否定主观感受。
- **夜间压力比白天高**（22–05 时 27~30 分，10–17 时 20~23 分），与「睡觉时压力该低」的直觉相反。
  设备测的是夜间静息时的自主神经张力，不是主观紧张度，别拿夜间高分推断睡眠差。
- `levels` 给的是**样本数**不是时长：压力是离散测量，中间有大量空隙，换算成分钟会凭空造出时长。
- **血氧只有 12 天 / 558 条**，2026-06-12~17 与 2026-08-03~08 两段，之后设备不再上报。
  所以 `get_blood_oxygen` **不做趋势**，只给记录与覆盖。没有记录 ≠ 血氧正常。
- **低氧判断看 `lowEvents`**（`LOW_BLOOD_OXYGEN`，阈值 90%），不要用「最低值」自己下结论；
  `below95` 只是「低于 95% 的样本数」，一个持续 30 分钟的低氧会变成 3 条样本而不是 1 次事件。
- 血氧默认区间锚在**数据自己的最后一天**（2026-08-08），不是今天 —— 用今天当锚点会整段落空。

### 心率曲线口径

`get_heart_rate_series` 读 `health_metric` 里 `metric_key='DYNAMIC_HEART_RATE'` 的 349 483 个采样点（368 天），
和 `get_daily_vitals` 读的 `daily_vitals` 是同一份数据的两个粒度：

- **两个工具的数字必须相等**：`stats.samples` 就是 `daily_vitals.heart_rate_samples`，`stats.min/max/average`
  就是 `heartRate.lowest/highest/average`（实测逐日完全相同）。哪天对不上，就是派生表没重建。
- **采样密度会变，点数少不等于没戴表**：2025-11~2026-06 那台设备每分钟一条（一天 1398~1439 点）；
  换设备后静息时拉长到 10 分钟一条、运动时才加密回 1 分钟（一天 238~320 点）。
  所以判断「那天戴没戴表」看 `stats.medianGapMinutes` 与 `firstSample`/`lastSample`，
  不要看采样点数——同一个 `samples=250` 既可能是稀疏采样，也可能是只戴了半天。
- **运动时段会明显加密**：跑步那一小时能有 50 条，是同一天其它小时的 5 倍；曲线上的高台基本都在这些时段。
  `activities` 用 `run_activity` 的 `start_time`/`end_time` 标出时段，`notes` 会把「最高心率落在哪次运动里」写出来。
- **单点极值不能直接当峰值**：`peak.sustainedMinutes` 是「围绕这一点、连续保持在 ±5 bpm 以内的分钟数」。
  跑步顶上去的 153 会连着 4~20 分钟；翻身蹭到传感器的 94 只有孤立 1 分钟。`sustainedMinutes <= 1`
  且不在任何运动时段内时，`notes` 会明说「更像噪声」。`lowest` 同理，只是低值段在睡眠期天然很长。
- **空桶不补 `null`**：只输出有采样的时间桶。一天 288 个 5 分钟桶里可能只有 180 个有数据，
  中间的缺口由 `maxGapMinutes` 与 `notes` 说明，避免曲线被一半的 `null` 撑散。
- **只做单日**：多日概览是 `get_daily_vitals` 的职责（它已经给了每天的均值/最低/最高）。
  在这里再开一个「多日 × 分桶」的口径，只会让两边对同一天的描述打架。
- **时间是本地时间**：`start_time` 存的已是 `+08:00` 的墙上时间（`time_zone` 恒为 `+0800`），
  不要再做时区换算。查询用 `start_time >= 当日 AND < 次日`，字符串字典序就是时间序。
  `resolution=minute` 会返回当天全部原始点（密集日 1400+ 个桶、约 100 KB），只在需要逐分钟细节时用。

### list_runs 过滤与排序

- `sport` 与记录的运动类型精确匹配（忽略大小写）；`weekday` 用 ISO 口径，1=周一 … 7=周日。
- 配速过滤单位是秒/公里：`minPaceSecondsPerKm` 是数值下限（排除比它更快的记录，如 330 排除快于 5'30"），
  `maxPaceSecondsPerKm` 是数值上限（排除比它更慢的记录）；带配速条件时，缺配速的记录会被排除。
- `sortBy`：`date`（默认，新的在前）、`distance`、`duration`、`pace`（快的在前）、`climb`；缺值记录排在最后。
- `minDistanceKm > maxDistanceKm` 或 `minPaceSecondsPerKm > maxPaceSecondsPerKm` 会抛参数错误，而不是静默返回空结果。

### 训练分析口径

P0 新增的五项分析都只使用已有的 TCX 数据与已回填的天气，不引入新的数据源、不新增数据库表：

- **比赛预测**：Riegel 公式 `T2 = T1 × (D2/D1)^1.06`。参考成绩取区间内 1K/5K/10K/半马/全马最佳努力中与目标距离最接近的一项；目标距离本身有实测时直接返回实测值并标记 `ACTUAL`。参考距离与目标距离差得越远，`confidence` 越低。
- **官方跑力（与 Riegel 并列，不互相替代）**：`run_level` 是华为每日一行的官方快照，含 `currentRunLevel`、A/E/I/M/R 五档速度（km/h）与 1/3/5/10 公里、半马、全马成绩。`get_run_level` 原样读出并换算配速；`predict_race_time(includeOfficial=true)` 按「区间结束日或之前最近一天」取快照，与 Riegel 逐距离并列，每行都带 `officialSource=HUAWEI_OFFICIAL` 与 `riegelSource=ACTUAL/RIEGEL`，差值为正表示官方更慢；相差 30 秒以内算「基本一致」，不作快慢判断。快照不在区间内时 `withinRange=false` 并给出 `staleDays`。
- **训练负荷 / ACWR**：急性窗口 7 天、慢性窗口 28 天，负荷口径默认距离（公里），也可切换为时长（分钟）。`ACWR = 急性负荷 / (慢性负荷 / 4)`；同时返回周增幅、连续训练天数、休息日、7 天单调性（均值/标准差）与 Strain。慢性窗口覆盖不足 21 天或训练日少于 7 天时 `dataSufficient=false`，置信度降为 `LOW`。
- **坡度修正配速 GAP**：用 Minetti 跑步能量代价公式计算坡度代价比 `Cr(grade)/Cr(0)`，`GAP = 实际配速 / 代价比`，坡度夹取到 `[-45%, 45%]`；净海拔变化取每段首尾海拔差。全程 GAP 用等效平路距离加权，而不是对分段 GAP 做算术平均。
- **训练类型识别**：主判据是距离、时长、配速相对个人中位数与分段波动。长距离阈值取 `max(10 km, 1.5 × 个人中位距离)`；间歇/变速要求同时有至少 2 段明显快、2 段明显慢于中位配速，避免把长距离跑里的一次停顿误判成间歇；无 GPS 轨迹时置信度降为 `LOW`。华为来源的记录还有平均心率，会换算成区间（Z1~Z5）做交叉验证：静息心率取 `daily_vitals` 近 28 天均值，最大心率取数据集里观察到的最快值（只会低估，偏保守），两者差 ≥ 60 bpm 时用储备心率（Karvonen）百分比，否则退回占最大心率百分比；区间与配速一致则置信度上调一格，冲突则下调一格，配速像轻松跑但心率已进 `Z4` 时改判节奏跑。
- **天气影响**：只读 `run_weather` 中 `status=OK` 的记录，优先使用起跑快照（缺失时退回结束快照）。分组内配速用「总时长 / 总距离」加权；样本少于 3 条时相关系数为 `null`，并在结论文本里提示样本不足。
- **跑步动态（跑姿）**：触地时间 / 垂直振幅 / 触地平衡来自华为 `summaryData` 的跑姿块（`mAvgGroundContactTime`、`mExtendTrackDataMap.avg_v_osc`、`avg_gc_tb`），**只有连接过跑步动态传感器的记录才有**——本机数据里 154 次跑步有 63 次（40.9%），所以 `get_running_dynamics` 一定先报 `coverage`，否则「这半年没戴传感器」会被误读成「跑姿变好了」。单次的分段与前后半程必须用 `track_stream` 的 `rp`（每 5 秒一个采样，只有 `k` 与 `gct/vo/gtb`）：`k` 实测是**相对起跑的秒数**（63 次记录里 `max(k) / 总时长` 都在 0.998~1.0），不是轨迹点序号（轨迹点每 2 秒一个，序号只到时长的一半），所以要用 `TrackPath.distanceAtTime` 落回距离轴才能按公里切段。`rp` 里其余字段（腾空时间、摆臂、冲击加速度等）实测恒为 `-1`/`-101`，不做建模。步幅没有原始字段：整次用「总距离 ÷ 总步数」，分段/半程用「本段速度 × 60 ÷ 本段平均步频」（分段没有单独步数）。区间汇总是**每条记录一个样本**（先算每次的均值再统计），与单次里的采样均值可能差 0.1 左右；左右平衡以 50% 为均衡，但华为没公开这个数值对应哪只脚。

### 返回状态

所有工具都通过 `status` 字段区分结果：

| `status` | 含义 | 建议处理 |
| --- | --- | --- |
| `OK` | 正常返回 | — |
| `NO_DATA` | 区间内没有记录，或该记录缺少所需字段（如无海拔） | 放宽日期，或先调 `list_runs` 看全部数据 |
| `NOT_FOUND` | `runId` 不存在 | 先用 `list_runs` 获取可用 id |
| `NO_GPS` | 没有可用轨迹：轨迹点为空，或轨迹类分析所需的有效坐标点不足 2 个 | 分段、最佳努力、路线等轨迹类分析无法进行，改用 `list_runs` 的汇总字段 |
| `AMBIGUOUS` | `runId` 匹配到多条 | 返回体里带 `candidates`，改用更完整的 id |
| `INVALID_ARGUMENT` | 参数值本身非法（非正数、`NaN`、超出取值范围、认不出的枚举值） | 数据没问题，要改的是参数。返回体带 `param` / `value` / 可选 `allowedValues`，按提示改正后重试 |

**参数校验口径**：不传 = 用默认值；**传了但值非法一律返回 `INVALID_ARGUMENT`，不会静默替换成默认值**。
具体到容易踩的几处：

| 参数 | 非法值 | 过去的行为 |
| --- | --- | --- |
| `splitKm`（`get_run_splits` / `compare_runs`） | `≤ 0`、`NaN`、小于 `0.1` | 静默按 1 公里切，返回的分段与请求不符 |
| `distancesKm`（`get_best_efforts` / `get_personal_bests`） | 任一项 `≤ 0` 或非数值 | 过滤成空目标，再用「目标距离超过轨迹距离」解释 |
| `category`（`get_personal_bests`） | 认不出的类别 | 静默变成 `all`，返回一堆没被请求的字段 |
| `distanceKm`（`predict_race_time`） | `≤ 0` | 静默退回「5K/10K/半马/全马」四档 |
| `groupBy`（`get_daily_running`） | 认不出的粒度 | 静默按 `day` 分组 |
| `period`（`get_training_trend`） | 认不出的粒度 | 除 `month` 外一律当 `week` |
| `sortBy` / `weekday` / `min*Km` / `min*Pace*`（`list_runs`） | 认不出的排序、`weekday` 超出 1~7、下限大于上限 | 排序静默按 `date`、`weekday` 越界等于不过滤、下限大于上限直接抛异常 |
| `unit`（`get_official_splits`） | 认不出的段单位；区间汇总时传 `both` | 静默按公里算，英里口径的请求会拿到公里数据 |

刻意保留的宽容行为只有分页：`offset` 负数按 0、`limit` 非正数按默认值，且生效值一定在返回体里回显。

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
    auto-sync:
      enabled: true                   # 关闭后只有首次访问或手动 reload_data 才同步
      interval: 5m                    # 两次同步的间隔（上一次结束后计时），支持 30s / 5m / PT10M
      initial-delay: 30s              # 启动后第一次同步的延迟
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

### 表清单

| 表 | 内容 |
| --- | --- |
| `run_activity` | 一次运动一行：摘要字段（时间、距离、配速、爬升、起终点坐标…）+ `source_file` + `content_hash` + `imported_at`；v8 起附带 `summary_data` 原文 |
| `track_point` | 轨迹点，主键 `(run_id, seq)`，`WITHOUT ROWID`，一个活动的点物理连续 |
| `track_stream` | 未进入轨迹点的原始流：`rs` 每 5 秒速度、`rp` 跑姿（每 5 秒一个的 `gct`/`vo`/`gtb`）、`pm2` 单公里配速、`evt` 事件等，主键 `(run_id, stream_type, seq)`；`rs`/`rp` 的 `k` 是相对起跑的秒数，`pm2` 的是终点轨迹点序号 |
| `source_file` | 来源文件指纹：`size`、`modified_millis`、`content_hash`，增量同步的比对基准 |
| `run_weather` | 每次跑步的起跑 / 结束两个时刻的天气，主键 `run_id`，由 open-meteo 回填 |
| `health_metric` | 华为健康明细，一行一个采样点：动态心率、睡眠分期、体重、血氧、压力… |
| `activity_minute` | 华为每分钟运动数据：步数、距离、热量、站立（`sportType` 4=跑步 / 5=走路 / 13=站立 / 2=其它） |
| `health_metric_type` | 指标字典，把 `type_code + metric_key` 翻成中文名与口径 |
| `sample_sequence` | 华为「Sample sequence data」原始记录：HRV RRI、心律失常、睡眠报告、睡眠碎片（type=700008，二进制 `detail_data`）、ECG 波形等；`summary/detail/extend` 三段原文完整保留 |
| `excel_sheet` | Excel 工作表清单与清洗后的表头（SportsHealth xls + HUAWEI_Research xlsx） |
| `excel_sheet_row` | Excel 原始数据行，一行一个非空行，空单元格不写入 JSON |
| `body_measurement` | 一次称重一行：体重、体脂率、BMI、骨骼肌量…（由 `health_metric` 派生） |
| `sleep_session` | 一次睡眠或午睡一行：入/离床时刻、深睡 / 浅睡 / REM / 清醒分钟（派生） |
| `daily_activity` | 一天一行：步数（含走路 / 跑步拆分）、距离、活动热量、中高强度分钟、站立次数（派生） |
| `daily_vitals` | 一天一行：静息心率、HRV、动态心率区间、压力、最大摄氧量（派生） |
| `sleep_report` | 夜间睡眠报告：评分、效率、平均心率 / HRV / 血氧、呼吸紊乱指数（派生自 `sample_sequence`） |
| `ecg_record` | ECG 记录元数据（派生自 `sample_sequence`，波形仍在原始表） |
| `daily_sport_stat` | 每日运动统计（派生自 `user sport daily statistics.xls`） |
| `sport_daily_dimen_stat` | 按运动类型拆分的每日统计（派生自 `sport daily dimen statistics.xls`） |
| `daily_health_stat` | 每日健康统计（派生自 `user health daily statistics.xls`） |
| `daily_health_samplestat` | 每日健康样本统计（派生自 `user health daily samplestat.xls`） |
| `run_level` | 华为官方跑力：跑力值、A/E/I/M/R 五档速度（km/h）与 1/3/5/10 公里、半马、全马成绩（由 `get_run_level` 读取，单位换算在工具层完成） |
| `medal` | 勋章获得记录（同一类型 / 等级可多次获得） |

前 11 张是原始表，后 12 张是**派生表**：内容完全由 `health_metric` / `activity_minute` /
`sample_sequence` / `excel_sheet_row` 决定，每次真有文件重导后由 `DerivedTables` 用
`INSERT … SELECT` 整表重建，升级到 v7 / v8 的旧库在下一次同步时一次性回填。
字段级说明见 [`docs/数据字典.md`](docs/数据字典.md)。

表结构版本记在 `PRAGMA user_version`（当前为 8）。以后加列或加表时，
往 `SqliteSchema.MIGRATIONS` 追加一段脚本并把 `SCHEMA_VERSION` 加一即可，不需要 Flyway；
版本 1 的旧库会在启动时自动补上 `source_file.status` / `error_message` 两列，版本 2 的会补建 `run_weather`，
v4/v6 补健康明细与分钟活动表，v7 补四张业务派生表，v8 补样本序列、Excel 原始行、轨迹附加流
以及另外八张派生表，不需要删库重建。

### 增量规则

首次访问、手动 `reload_data` 与后台自动同步都会执行同一套同步：

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

### 自动同步

默认每 **5 分钟**在后台执行一次增量同步，启动后延迟 30 秒开始。自动同步复用
`HealthDataRepository.reload()`，与手动 `reload_data` 走完全相同的路径：扫描 TCX 目录 →
按指纹只解析变化的文件 → 重建内存快照 → 导入结束后补一轮天气。没有新文件时只做一次目录扫描，
不会重复解析，也不会重复请求天气。

```yaml
sport:
  health:
    auto-sync:
      enabled: true
      interval: 5m
      initial-delay: 30s
```

- **fixed-delay**：以上一次同步结束为起点计时，而不是固定频率；上一轮包含天气 HTTP 调用时
  不会出现两轮重叠。同步方法本身在仓储里也加了锁，后台任务与手动 `reload_data` 互斥。
- **失败不退出**：单次同步抛异常只记录日志，保留上一份快照，下一次触发继续重试。
- **启动即用**：即使没有 MCP 请求，第一次延迟后也会主动装载数据；有请求时仍可按需触发首次装载。
- **关闭**：`enabled: false` 后调度器不注册任务，只保留首次访问与手动 `reload_data`。
  不想让服务在后台访问 open-meteo 时，可以同时设置 `sport.weather.enabled: false`。
- 多实例部署时每个实例都会各自同步自己的 SQLite；本项目的定位是单机服务，不处理跨实例协调。

### 怎么查

```bash
sqlite3 data/sport-health.db "SELECT id, run_date, round(distance_meters/1000.0,2) km FROM run_activity ORDER BY run_date DESC LIMIT 5;"
sqlite3 data/sport-health.db "SELECT COUNT(*), round(SUM(distance_meters)/1000.0,1) FROM run_activity WHERE run_date LIKE '2026-11%';"
sqlite3 data/sport-health.db "SELECT source_file, size, modified_millis, substr(content_hash,1,12) FROM source_file;"
```

### 天气回填（open-meteo）

每次同步（首次加载、`reload_data` 或后台自动同步）在**跑步数据全部写完之后**补一轮天气：取起跑和结束两个时刻的气温、体感温度、
相对湿度、降水量、风速、风向、天气代码，写进 `run_weather`。只处理 `sport_family='RUN'` 的记录——
`run_activity` 里还有步行等其它运动，它们既不该消耗接口配额，也不该混进覆盖率的分母。
`reload_data(weather=false)` 会跳过这一步，把「同步数据」和「补天气」彻底解耦。

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
  "fetchedAt": "2026-11-04 02:11:07",
  "start": {
    "observedAt": "2026-10-24 22:00:00",
    "temperatureC": 8.0, "apparentTemperatureC": 6.2,
    "humidityPercent": 72.0, "precipitationMm": 0.2,
    "windSpeedKmh": 12.0, "windDirectionDegrees": 315.0, "windDirection": "西北",
    "weatherCode": 61, "condition": "小雨"
  },
  "end": { "...": "同上，跑完时刻" }
}
```

返回体里的时间统一为 `yyyy-MM-dd HH:mm:ss`：本地时间按配置时区输出，`*Utc` / `fetchedAt` 等 UTC 字段仍按 UTC 输出；只有日期的字段（如 `date`）保持 `yyyy-MM-dd`。

口径：`observedAt` 是参与插值的**整点**，不是跑步时刻本身；任一指标缺值一律 `null`，不会当成 0 度；某个时刻整份快照都没有时该时刻直接是 `null`。`status` 除 `OK` 外还有三种：`UNAVAILABLE`（缺坐标、
超出存档覆盖范围）与 `FAILED`（抓取失败待重试）会带上 `errorMessage` 说明原因；还没回填过则是
`MISSING`，提示调用一次 `sync_weather` 即可补齐。

**单独补天气**：`sync_weather` 只做「补齐天气」这一件事——不重导数据、不重建快照，可以反复调。
它存在的理由是把两件事拆开：`reload_data` 把「全量扫描 + 天气 HTTP」捆在一起，端点不可达时一次调用要等满多个超时，
MCP 客户端大概率先断开；而失败的记录又需要尽快重试（默认冷却 `retry-failed-after-days` 天）。

```json
{
  "status": "OK", "retriedFailed": true,
  "ok": 3, "unavailable": 0, "failed": 0, "noLocation": 0, "deferred": 0,
  "attempted": 3, "total": 3, "problems": [], "retryableProblems": [],
  "elapsedMillis": 1240,
  "coverage": { "totalRuns": 154, "ok": 154, "missing": 0, "coveragePercent": 100.0, "...": "..." }
}
```

- `retryFailed` 默认 `true`：手动调用本身就不该被 7 天冷却挡回去，否则用户看到 `FAILED` 后点一次「补天气」却什么都没发生。
- `total = 0` 表示**没有候选**（全部记录都是 `OK`/`UNAVAILABLE` 终态），不是失败。
- 关闭开关（`sport.weather.enabled=false`）时返回 `status = DISABLED`，而不是假装成功。
- 只处理 `sport_family = 'RUN'` 的记录：`run_activity` 里的步行等运动不消耗配额，也不进覆盖率的分母。

**聚合看天气影响**：`get_weather_impact` 只读已经落库的天气行，按起跑气温、湿度、风速、有无降水分组，
返回每组的次数、加权平均配速与相对整体基线的快慢秒数。它不会触发抓取，所以输出里带 `weatherCoverage`：
区间内跑步总数、拿到天气的条数、缺失条数与原因拆分（`failed` 可重试 / `unavailable` 数据源无该时段 /
`notFetched` 还没回填）、覆盖率与一句结论。样本数明显小于总数时，看这一块就知道该不该补数据：

```json
"weatherCoverage": {
  "totalRuns": 154, "ok": 151, "missing": 3,
  "failed": 3, "unavailable": 0, "notFetched": 0,
  "coveragePercent": 98.1,
  "hint": "区间内 154 次跑步有 151 次拿到天气，3 次缺失（失败 3、无数据 0、未回填 0）。失败的记录可用 sync_weather 立刻重试；UNAVAILABLE 表示数据源没有该时段数据，重试无效。"
}
```

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
| `status` | 仅 `END`：返回体里的 `status`：`OK` / `NO_DATA` / `NOT_FOUND` / `NO_GPS` / `AMBIGUOUS` / `INVALID_ARGUMENT`；抛异常时为 `ERROR` |
| `durationMs` | 仅 `END`：工具方法执行耗时（毫秒），不含日志自身开销 |

- 使用独立 logger `org.example.sporthealth.config.ToolAccessLogAspect`（即切面类名，日志里按 `%logger{36}` 缩写为 `o.e.s.config.ToolAccessLogAspect`），默认 `INFO`，与其它日志一样写入控制台和 `logs/tender-service.log`。
- 调整级别 / 关闭：`-DLOG_LEVEL_TOOL_ACCESS=DEBUG`、`-DLOG_LEVEL_TOOL_ACCESS=OFF`。
- 异常时 `phase=END` 按 `ERROR` 记录并附完整堆栈（同时写入 `logs/tender-service-error.log`），异常原样抛给客户端，返回内容不受影响。
- 按注解切面、不限定包，新增工具只要标注 `@McpTool` 就自动纳入，无需在工具方法里埋点。

---

## 架构

```
tools/SportHealthTools          # 25 个 @McpTool，负责参数校验与统一的 Map<String, Object> 返回结构
        │
service/RunAnalyticsService     # 区间汇总、按天拆分、个人最佳、对比、分段/GAP、最佳努力、海拔、停顿、趋势、比赛预测、ACWR、训练分类、id 解析
service/RunLevelService         # 华为官方跑力：每日快照、五档配速、趋势、官方 vs Riegel 对照
        └── repository/RunLevelStore   # run_level 表：最近一天 / 指定日或之前 / 区间内；km/h → 配速在仓储层换算
service/RunningDynamicsService  # 跑姿与步幅：summaryData 跑姿块做区间汇总，rp 流 + 轨迹做分段与前后半程
service/AutoSyncScheduler       # 后台自动同步执行器：调用 reload()，失败只记日志、保留上一份快照
config/AutoSyncConfiguration    # 注册 fixed-delay 任务；enabled=false 时不注册
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

support/TrackPath               # 轨迹几何索引：累计距离 ↔ 时间 ↔ 海拔（+ 步频），分段/最佳努力/海拔/跑姿共用
support/WeatherImpactAnalyzer   # 天气影响分组统计（纯计算，不访问库或网络）
domain/RacePrediction           # 比赛预测结果（参考成绩 + 各目标距离）
domain/TrainingLoad             # 训练负荷 / ACWR 快照
domain/RunClassification        # 单次训练类型识别结果
domain/TrainingMix              # 区间训练类型构成
domain/WeatherImpact            # 天气影响分组结果
domain/RunningDynamics          # 单次跑姿/步幅汇总；DynamicsSegment 分段、RunningDynamicsDetail 详情、DynamicsSummary 区间统计

tools/ActivityHealthTools       # get_daily_activity：daily_activity 一天一行（步数/距离/活动热量/中高强度/站立次数）
        ├── service/DailyActivityService + repository/DailyActivityStore
        └── get_activity_minutes：activity_minute 一分钟一行（类型/步数/距离/热量）→ service/ActivityMinuteService
            + repository/ActivityMinuteStore（MERGED_MINUTE_SQL 同时被派生表复用）
tools/VitalsHealthTools         # get_daily_vitals + get_body_composition：daily_vitals / body_measurement
tools/WellnessTools             # get_hrv_trend / get_stress_series / get_blood_oxygen：health_metric 的稀疏单点
tools/SleepHealthTools          # get_sleep_summary / get_sleep_trend / get_sleep_detail：sleep_session / sleep_report / sample_sequence
tools/HeartRateHealthTools      # get_heart_rate_series：单日动态心率曲线（health_metric 逐分钟采样）
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

共 **351** 个用例（原有 42 + 持久化 19 + 资源形态 2 + 天气 42 + 工具层天气 4 + P0 分析 17 + 自动同步 7 + 睡眠 23 + 体征 9 + 心率曲线 11 + 体成分 13 + HRV/压力/血氧 18 + 日常活动 9 + 分钟明细 8 + 其余工具/参数校验用例）：

- **真实数据**：测试固定读 `src/test/resources/tcx-dataset` 里的 5 个 TCX，而不是
  `src/main/resources/health-data`。生产目录会随导入继续增长（例如新加的骑行文件），
  断言里写死的「5 条记录 / 38.28 km」会跟着一起挂；用独立 fixture 后新增数据不再影响单测。
  断言单次解析（2448s / 7123m / 爬升 10.1m / 1216 个轨迹点）、UTC→本地日期换算、全量汇总
  （38.28 km / 219.8 min / 平均配速 5'45"/km）、区间过滤、按天拆分、最佳成绩、对比与轨迹分页。
- **轨迹分析**：逐公里分段（末段 `partial`、前后半程与负分段）、单次最佳努力、跨记录 1K/5K 最佳、
  海拔剖面、停顿检测与 `describe_data_source` 的 quality 自检。
- **工具层**：quality 块结构、任意 `distancesKm` 的目标距离最佳（含 `best10k`/`bestHalf` 命名与不可用距离），
  华为官方分段与 GPS 分段的交叉校验（`get_official_splits` 另有独立用例：km/mile 两套口径、
  `p-m`/`b-p-m` 扁平写法兜底、区间覆盖率与差值统计），以及 `get_run_route` 两种格式的海拔小数精度。
- **睡眠**（`SleepHealthToolsTest` / `SleepDetailToolsTest`，真实 SQLite 临时库）：两套来源合并成一夜一行、
  哨兵值（`255` / `-1` / `score=0` 的午睡行）折成 `null`、覆盖计数、醒来次数去掉首尾两段且 ≥2 分钟才计数；
  详情侧另测相邻同阶段合并、碎片二进制解码（块名不符的块必须拒读、时间戳与记录时刻相差过大也拒读）、
  块间间隙与 `wakeGapCount`、午睡的时段筛选与相邻段合并、`includeTimeline=false` 仍返回真实段数、
  详情与汇总同一夜晚数字一致、`date` 与区间互斥以及 14 夜上限。
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
- **跑步动态**（`RunningDynamicsServiceTest` / `SportHealthToolsRunningDynamicsTest`）：
  `rp` 的 `k` 按秒换算成累计距离再分段（同一份采样放在 500 米切点前后会落到不同的段）、
  前后半程差值为后半程减前半程、分段步幅按「速度 × 60 ÷ 步频」折算、
  区间汇总按记录数而不是采样行数平均、缺项不计入统计（不返回 0 值）、周/月趋势的周期边界、
  没有 summaryData 跑姿块时用采样均值兜底、没有轨迹时保留整次汇总但不给分段；
  工具层覆盖度（1/3 带跑姿）、分页、`groupBy=none`、无跑姿记录的 `message`、
  以及 `runId` 与日期、`splitKm`、`groupBy` 混用时的 `INVALID_ARGUMENT`。
- **自动同步**（`AutoSyncSchedulerTest` / `AutoSyncPropertiesTest` / `AutoSyncConfigurationTest`）：
  执行器确实走仓储的 `reload()` 并读取新快照、同步失败只记日志不抛给调度线程；
  配置默认开启、Duration 格式绑定、非正间隔回落到默认值、`enabled=false` 可关闭；
  开启时注册一个 fixed-delay 任务且间隔/首次延迟与配置一致，关闭时不注册任务。
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
| `status: "INVALID_ARGUMENT"` | 参数值非法。看返回体的 `param` / `value` / `allowedValues`，按 `message` 改正后重试；数据本身没问题 |
| 轨迹点数量比预期少 | 查看 `warnings`，是否有缺 `Time` 被跳过的点 |
| `warnings` 不为空但数据正常 | 字段级问题，通常不影响聚合；按告警类型决定是否修源文件 |
| `describe_data_source` 首次调用很慢 | `duplicateTimestampRunIds` 要逐条读轨迹，首次从库加载后命中缓存；只想看汇总时用其它工具 |
| 启动报 `Port 8080 was already in use` | 旧进程未退出，先停掉再启动 |
| 客户端连上但工具列表为空 | 检查是否误加了 `spring-boot-starter-web`，它会让应用退回 Servlet 栈 |
| 手写 HTTP 返回 400 / 406 | 缺少 `initialize`、`Mcp-Session-Id`，或 `Accept` 少了 `text/event-stream` |
| 启动报 `数据库结构版本 N 高于程序支持的 1` | 库文件被更新版本的程序写过，升级程序或换一个 `db-path` |
| 改了 TCX 内容但结果没变 | 文件 `size`/`mtime` 都没变（例如原地覆盖但时间戳被保留）。touch 一下文件或删库重导 |
| 自动同步没有生效 | 检查 `sport.health.auto-sync.enabled` 是否被设为 `false`；启动日志应有「自动同步已启用」。`interval` 为非正数时会回落到默认 5 分钟 |
| `warnings` 里有「数据目录 … 下没有找到 *.tcx 文件」 | `data-dir` 写错；相对路径会按进程工作目录解析 |
| `warnings` 里有「天气回填失败 N 条」 | 网络不通、被限流或 5xx。跑步数据不受影响，`retry-failed-after-days` 天后同步会自动重试 |
| `warnings` 里有「天气回填本轮提前结束」 | 接口连续 3 次可重试失败后主动收尾，避免一次同步被拖到几分钟。这些记录没落行，下次同步自动重试 |
| 天气一直是 `UNAVAILABLE` | 该时段超出数据源覆盖范围（太早的历史），或是室内跑步没有 GPS 坐标。属终态，不会重试 |
| 天气温度和体感差很多 | 正常。`start_apparent_c` 是体感温度，风速大、湿度高时与气温差异明显 |
| `get_running_dynamics` 里触地时间/垂直振幅/左右平衡是 `null` | 该次运动没连跑步动态传感器（心率带/跑姿传感器），华为不会补算。看 `coverage.dynamicsRuns` 确认是「没戴」还是「数据没导入」；步幅与步频不受影响 |
| 跑姿分段里的 `sampleCount` 明显少于「本段用时 ÷ 5」 | `rp` 采样只在传感器连接期间产生（常见是开跑十几秒后才开始），前段会少几个点；整次均值仍以华为 `summaryData` 为准 |
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
