# 岩层滴算台

石笋 U-Th 年代建模本地服务：沿生长轴组合 U-Th 年代锦标、其不确定度、生长间断与高分辨
代理指标，构建**不跨越间断的单调年代模型**，并把年龄不确定性传播到规则时间网格上的代理
序列。Java 17+ / Spring Boot 4 / SQLite / 原生 SVG，无需前端构建。

## 安装与演示

```bash
# 构建（跳过测试）
mvn -q -DskipTests package

# 自动化测试 + 启动（默认 5537 端口）
mvn -q test
mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5537
# 打开
open http://127.0.0.1:5537
```

也可直接运行打好的可执行 jar：

```bash
java -jar target/stalagmite-drip-calculator-1.0.0.jar --server.port=5537
```

页面标题为 **岩层滴算台**。首次启动若数据库为空，自动载入仓库内置固定 fixture。
SQLite 文件默认在 `./data/dripcalc.db`，可用环境变量覆盖：`DRIP_DB=/其它/路径.db`。

## 数据口径（本服务如何处理年代学证据）

- **深度方向**：深度自石笋顶向下增大（mm），年龄随深度单调增大（ka）。
- **生长段与间断**：间断 `[topDepthMm, bottomDepthMm]` 把剖面切成若干生长段。
  每个生长段**独立建模**；间断两侧的锦标**绝不做线性插值连接**，间断也不会被当作
  “极慢生长段”。走廊网格在间断两侧及无年代支持处保留为显式缺失。
- **边界开闭**：用户可分别设置间断上/下侧为开放或闭合。
  - 闭合：用**同一生长段内**锦标做最小二乘线性拟合，投影到边界深度，统计“投影间断
    时长”；
  - 开放：不向边界投影年龄，投影值保留缺失，只报告由最近邻锦标得到的**最小间断时长**。
- **相关误差（碎屑铍校正）**：同相关组 `corrGroup` 的锦标共享一个校正参数抽样
  `g_group ~ N(0,1)`。单枚锦标 `a_i = μ_i + sharedSigma_i·g_group + ε_i·√(σ_i²-sharedSigma_i²)`，
  同组锦标差不确定度为 `√(σa²+σb²-2·shared_a·shared_b)`（正协方差使差更精确）。
- **段内单调化**：每个 Monte Carlo 抽样在每个生长段内做加权 PAVA（保序回归），
  保证深度-年龄非递减；这是对“年代必须有序”的约束，不是平滑曲线。
- **同深度冲突**：同一深度（±0.05 mm）存在年龄区间不相容的锦标时（默认阈值
  `|Δage| > 2·σ_Δ`），返回**冲突证据**（年龄差、差不确定度、z 分数），各锦标
  **各自保留为独立年代分支**。系统不会把多个分支平均成一条假曲线，也**不会统一放大
  所有误差**去强行通过。
- **可复现抽样**：使用内置固定种子 LCG + Box-Muller（不依赖 JDK 随机数实现），
  走廊、生长率、间断、代理网格共用同一批抽样；相同输入与种子结果逐位一致。
- **时间网格与覆盖率**：代理指标被重采样到规则年龄网格（默认 0.5 ka）。每个格点记录
  有年代支持的抽样比例 `coverage` 与 2.5/50/97.5 分位、均值、标准差。落在间断时间窗
  或无年代支持区域的格点保留为**缺失（missing=true, coverage=0）**，不插值填充。
- **生长率**：仅由同一生长段内相邻锦标计算 `(Δmm)/(Δka·1000)` → mm/yr。

缺失数值在 JSON 中序列化为 `null`；是否缺失由 `unsupported` / `missing` 标志判定。

## 固定 fixture

`com.drip.data.Fixtures`（随代码交付，确定性）：

- 生长段一 50–300 mm，锦标 D1(50mm, 2.0±0.15 ka)、D2(250mm, 6.5±0.22 ka)
- 生长间断 H1：300–350 mm（默认两侧闭合，可在页面调整端点与开闭）
- 生长段二 350–600 mm，锦标 D3(400mm, 12.8±0.28 ka)、D4(600mm, 17.5±0.20 ka)
- D2、D3 同属相关组 `G1`，共享碎屑校正 σ 分别为 0.10 / 0.12 ka
- 代理序列：50–600 mm 每 10 mm 一点（跳过间断深度），为固定合成序列

## 操作与 API（页面均有对应按钮）

| 能力 | 方法与路径 |
| --- | --- |
| 当前数据 | `GET /api/state` |
| 排除/恢复某枚锦标 | `POST /api/dates/{id}/excluded?excluded=true|false` |
| 调整间断端点/开闭 | `PUT /api/hiatuses/{id}` |
| 运行年代模型（分支/走廊/生长率/间断/代理网格） | `POST /api/run` |
| 制造同深度冲突（不落库，返回证据+多分支） | `POST /api/demo/conflict` |
| 运行记录列表/详情 | `GET /api/runs`、`GET /api/runs/{id}` |
| 导出运行记录（含输入快照、配置、结果、SHA-256 指纹） | `GET /api/runs/{id}/export` |
| 仅复核导出包（重算并比对指纹） | `POST /api/runs/verify` |
| 复核后导入（`replaceData=true` 可覆盖当前数据） | `POST /api/runs/import?replaceData=false|true` |
| 清空数据库 / 恢复 fixture | `POST /api/data/clear`、`POST /api/fixtures/reset` |

## 运行记录导出、清空后重导复核

每次运行都会把**输入快照**（当时使用的锦标/间断/代理）、模型配置、完整结果和
`fingerprint = sha256(规范 JSON 结果)` 存入 SQLite 的 `run_record` 表。

重放/复核方式：

1. 页面“导出最新运行 JSON”或 `GET /api/runs/{id}/export` 保存导出包；
2. `POST /api/data/clear` 清空数据库（或删除 `./data/dripcalc.db` 后重启）；
3. 页面选择导出包并“复核并替换数据”，或：

```bash
curl -X POST 'http://127.0.0.1:5537/api/runs/import?replaceData=true' \
  -H 'Content-Type: application/json' --data-binary @dripcalc-run-1.json
```

服务会用导出包中的输入快照**重新运行模型**并比对 SHA-256：一致才入库/替换数据；
导出包被篡改时复核失败，导入返回 `400` 且拒绝写入。因此“清空数据库后重新导入”
得到的是经过独立重算复核的数据，而非盲目还原旧结果。

## 验收要点对应的自动化测试

`mvn -q test` 共 22 个测试（JUnit 6 + Spring Boot Test + MockMvc + 临时 SQLite）：

- `AgeModelEngineTest`
  - `corridorDoesNotCrossHiatusAndMarksUnsupportedNodes`：间断内无走廊节点、两侧缺失、
    年龄走廊在间断处断开；
  - `adjustingHiatusEndpointsKeepsNoInterpolation`：调整间断端点后仍不跨间断插值；
  - `sameDepthConflictReturnsEvidenceAndKeepsBranchesWithoutInflatingErrors`：同深度冲突
    产生两个分支，返回 z 分数证据，成员 σ 仍为原始值；
  - `proxyTimeGridKeepsMissingPointsOverGapWithCoverage`：间断时间窗格点缺失且覆盖率 0；
  - 另有相关组协方差、PAVA 单调化、排除锦标、开放边界、生长率仅来自段内、同种子可重放。
- `RepositoryTest`：fixture 播种/清空/运行记录持久化（临时 SQLite 文件）。
- `ApiIntegrationTest`：页面标题、fixture 状态、运行、冲突演示、排除锦标、
  导出→清空→重导复核一致、篡改包被拒（400）、重置 fixture。

## 目录结构

```
src/main/java/com/drip
├── DripCalcApplication.java
├── model/        # DateSample / HiatusSpec / ProxyPoint / ModelConfig / ModelResult
├── engine/       # AgeModelEngine（PAVA、相关抽样、分支、走廊、网格）
├── data/         # SQLite(JdbcTemplate)、固定 fixture、初始化、JSON 配置
└── web/          # REST API、首页、运行/指纹/导入复核服务
src/main/resources
├── application.properties
└── static/       # index.html（SVG 操作页）、app.js
src/test/java/... # 引擎 / 持久层 / 全栈集成测试
```
