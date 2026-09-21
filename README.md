# 岩层滴算台

“岩层滴算台”是一个本地运行的石笋年代模型工作台，用于沿生长轴组织 U-Th 年代锦标、年龄不确定性、生长间断和高分辨率代理指标。系统将两个生长段分开建模，绝不跨间断线性插值，也不把间断解释为极慢生长段。

## 技术栈

- Java 17+、Spring Boot 3.3
- SQLite（默认数据库文件：`rocklayer.db`，可用 `ROCKLAYER_DB` 覆盖）
- 原生 HTML/CSS/JavaScript 与内联 SVG
- 固定 fixture 与 Spring MVC 自动化测试

## 快速开始

```bash
mvn -q -DskipTests package
```

完整验收：

```bash
mvn -q test && mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5537
```

然后访问：

```text
http://127.0.0.1:5537
```

页面标题应显示“岩层滴算台”。

## 固定 fixture 口径

- 深度单位：mm；年龄单位：yr BP；代理指标：δ18O（‰ VPDB）。
- 生长段：年轻段为 0–100 mm，较老段为 102–200 mm；100–102 mm 是明确间断。
- 年代锦标：`U-040` 与 `U-160` 属于同一相关组 `G1`，默认共享校正 σ 为 30 年；`SURFACE` 与 `BASE` 是固定控制点。
- 代理样本：5–60 mm 与 140–195 mm，间断内部没有样本，也不会生成样本年龄。
- 时间网格：0–6500 yr BP，步长 250 年；无年代支持的格点保留 `null` 并标记 `supported=false`。
- 默认生成 160 个确定性分支，结果可重复；每个分支保留两个独立生长段，不平均成一条跨间断假曲线。

## 建模规则

- 每个生长段在自己的控制点之间建立随深度增加的单调年龄关系，使用 PAVA 处理单调约束。
- 同一年代锦标年龄由独立测量误差与共享校正误差组成；同一相关组在所有生长段中使用同一个共享扰动分支。
- 间断侧为闭边界时，只向该侧边界做基于局部生长率先验的短距离外延，年龄不确定性随外延距离传播；两侧仍不连接。
- 间断侧为开边界时，不向间断内部或边界外推；间断时长标记为不可估，只保留非负物理约束。
- 局部生长率只在同一段相邻控制点之间计算；间断深度区间没有生长率。
- 代理指标先在每个分支内映射到年龄，再统计规则年龄网格；`coverageRate=有效分支数/总分支数`，缺失分支不补零、不插值。

## 冲突处理

若两个启用的年代锦标位于同一深度，系统按相关误差计算年龄差 σ，并同时检查：

- 年龄差达到 3σ；
- 二者 2σ 年龄区间互不相交。

满足条件时返回 `CONFLICT` 和证据对象，包括两个锦标 ID、深度、年龄、年龄区间、差异、差异 σ、z 值与 `errorInflationApplied=false`。系统不会扩大所有误差、不会跨段借点、不会输出强行通过的代理曲线。

调整间断端点导致年代或代理样本落入间断内部时返回 `400 INVALID`，而不是自动跨越样本重新连线。

## 页面操作

- 修改间断两侧深度及开/闭边界。
- 在表格中取消某个年代锦标的“启用”。
- 修改 `correctionGroup`：同名组共享校正参数，清空或改名即取消共享。
- 查看年龄走廊、局部生长率、间断时长和规则时间网格统计。
- SVG 用不同颜色区分两个生长段；灰色网格点表示无年代支持。
- 运行记录保存到 SQLite，可打开单个 JSON 链接另存。

## HTTP API

- `GET /api/scenario`：读取当前场景；空库时自动写入固定 fixture。
- `PUT /api/scenario`：替换场景。
- `POST /api/scenario/reset`：恢复固定 fixture。
- `POST /api/runs`：计算模型并保存运行记录；请求体可含 `{"scenario": ..., "persistScenario": true}`。
- `GET /api/runs`：列出运行记录。
- `GET /api/runs/{id}`：读取完整运行 JSON。
- `GET /api/export`：导出场景和全部运行记录快照。
- `POST /api/import?resetBeforeImport=true`：导入快照。
- `POST /api/admin/clear`：清空场景和运行记录。
- `POST /api/admin/reseed`：清空后重新导入固定 fixture。

## 清空后重放复核

命令行方式：

```bash
curl -s http://127.0.0.1:5537/api/export -o snapshot.json
curl -s -X POST http://127.0.0.1:5537/api/admin/clear
curl -s -X POST 'http://127.0.0.1:5537/api/import?resetBeforeImport=true' \
  -H 'Content-Type: application/json' --data-binary @snapshot.json
curl -s http://127.0.0.1:5537/api/runs
```

页面方式：点击“导出快照”，再点击“清空数据库”，最后通过“导入快照”选择 JSON 文件。导入后运行记录 ID、请求场景和完整结果均可复核。

## 自动化测试覆盖

- 首页可访问并包含“岩层滴算台”。
- 默认 fixture 产生两个独立生长段、160 个分支、缺失网格和间断估计。
- 间断两侧开边界时不估计间断时长，年龄走廊不进入间断深度。
- 排除一个年代锦标后仍不跨段借点。
- 同深度年龄冲突返回证据且不修改输入 σ。
- 间断端点移动到控制点周围时拒绝跨越插值。
- 导出、清空、再导入后可读取同一运行记录。

## 数据文件与构建产物

SQLite 数据库、Maven `target/` 目录是本地运行产物；重新启动时会自动创建 schema。需要完全复核时先清空数据库，再导入快照或恢复 fixture。
