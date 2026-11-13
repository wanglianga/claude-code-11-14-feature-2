# 航班正常性统计服务（flight-regularity-service）

解决"大屏 87.4% 与次日日报 85.1% 永远对不上"的问题：同一批航班、同一批事件，
实时累加把每次变更当新记录重复计数，离线重算只取最终状态。本服务用
**实例主键 + 状态机 + 正负号变更日志 + 计划版本绑定 + 日报版本/更正** 让两套口径
从同一事实源派生、可逐航班对账、可指回具体版本和具体那次变更。

- 技术栈：Java 25 · Spring Boot 4 · Spring Data JPA · PostgreSQL 18（分区表）· Flyway · springdoc-openapi（Swagger UI）
- 项目类型：后端服务（无前端业务页面），提供 OpenAPI 3.x 文档与 Swagger UI

---

## 1. 核心设计

### 1.1 航班实例主键
`航班实例 = (航班号 flight_no, 执行日期 service_date, 航段号 leg_no)`。
补班/加班（`instance_kind=FERRY/EXTRA`）与同日同号的不同航段各自成独立实例，
同一航班一天被改十几次状态也只落在同一实例上，从根上杜绝重复计数。

### 1.2 状态机（非法流转拒绝并告警）
```
PLANNED → ESTIMATED/DELAYED/BOARDING/CANCELLED
ESTIMATED → ESTIMATED/DELAYED/BOARDING/CANCELLED
DELAYED → ESTIMATED/BOARDING/CANCELLED
BOARDING → PUSHBACK/DELAYED/CANCELLED
PUSHBACK → DEPARTED/CANCELLED
DEPARTED → LANDED/DIVERTED            （起飞后备降：合法）
DIVERTED → DEPARTED/LANDED/CANCELLED  （备降后续飞/备降场落地/备降后取消：合法）
CANCELLED → RESTORED                  （取消后恢复：合法）
RESTORED → ESTIMATED/DELAYED/BOARDING/CANCELLED
LANDED → （终态）
```
- **未推出直接落地**（LANDED 只能来自 DEPARTED/DIVERTED）等非法路径：**拒绝（409）并写告警**
  （`alert` 表 + `GET /api/alerts`），告警在独立事务中保存，不随事件回滚。
- 时间更正（补录）：`LANDED→LANDED`（凌晨补录落地时间）、`LANDED→DEPARTED`（落地后补报起飞时间）
  按 `TIME_CORRECTION` 受理，不视为非法流转。

### 1.3 变更日志：不覆盖旧值，正负号冲抵
校验通过的每次变更写入 `flight_change_log`（分区表）两条记录：`-1` 冲抵旧状态事实、`+1`
计入新状态事实，版本号（`change_version`）在实例内连续递增。下游按实例主键逐条冲抵即得最终状态，
日报重算与实时投影来自同一批日志。

### 1.4 放行判定绑定当时生效的计划版本
计划时刻存 `schedule_version`（每次改计划产生新版本，`effective_from` 生效时刻）。
放行判定按**首次实际起飞时刻**匹配当时生效的版本：实际起飞 ≤ 计划起飞 + 容差（默认 20 分钟）记正常。
判定结果（含绑定的 `release_version_id`）一旦发布，航司事后改计划**不反向改写**。

### 1.5 迟到数据
迟到窗口默认 6 小时（`app.late-window-hours`）：窗口内事件来源 `REALTIME`，触发结果更新与撤回（冲抵）；
超窗事件强制标记 `late=true`、来源 `BACKFILL`（补录），照常入账但全程可识别。

### 1.6 日报：同一批事件重算 + 版本 + 更正
- `POST /api/reports/{date}/recompute` 每天用同一批变更日志重放重算，产生新版本；
  逐航班比对放行状态与延误分钟，正常性翻转或延误差超阈值（默认 5 分钟）记入 `report_diff`，
  并**定位到具体那次变更**（`caused_by_changes` 列出该实例两版之间的变更版本号）。
- 日报锁定（`POST .../lock`）后只能以 `kind=CORRECTION` 另出一份并填写原因；
  原值、更正值、原因写入 `report_correction`，原版本永不改写。
- 明细行内**实时口径与日报口径分列**（`realtime_*` 与 `counted/normal/delay_minutes`）。

### 1.7 统计口径（写死，`GET /api/caliber` 可查）
| 规则 | 内容 |
| --- | --- |
| R1 | 取消航班：最终取消且未恢复执行，**不计入分母** |
| R2 | 备降后改飞/续飞：**算一班**（同一实例），按首次实际起飞判定 |
| R3 | 临时取消又恢复：记在执行日期当天，按最终执行结果判定 |
| R4 | 正常放行：实际起飞 ≤ 判定时生效计划起飞 + 20 分钟（可配） |
| R5 | 延误分钟 = max(0, 实际起飞 − 判定绑定计划起飞)；判定绑定版本，事后改计划不回写 |
| R6 | 应飞未飞（未起飞未取消）：日报口径计入分母且记不正常；实时口径暂不计入（待判定） |
| R7 | 迟到窗口 6 小时：窗口内更新与撤回；超窗走补录并标注 BACKFILL |
| R8 | 日报每天用同一批事件重算，逐航班比对，差异超阈值定位到具体变更 |
| R9 | 日报锁定后更正另出一份，原值/更正值/原因同时可查 |

实时口径分母 = 已判定（已起飞）班次；日报口径分母 = 已起飞 + 应飞未飞（记不正常）。
两者分列展示，差异可由变更日志逐航班解释——这就是 87.4 与 85.1 的可对账版本。

---

## 2. 快速开始

### 2.1 Docker Compose 一键启动
```bash
docker compose up --build
# 应用: http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
# OpenAPI JSON: http://localhost:8080/v3/api-docs
```
Compose 含 PostgreSQL 18（库/用户/密码 `flightops/flight/flight`）与应用两个服务，
应用启动时由 Flyway 自动建表（含分区）。

### 2.2 本地开发启动（使用容器自带 PostgreSQL）
前置：`127.0.0.1:5432` 的 PostgreSQL（库/用户/密码均为 `ccdev`）已运行。
```bash
# 运行测试（需要数据库可用）
/opt/maven/bin/mvn test

# 启动服务（二选一）
/opt/maven/bin/mvn spring-boot:run
/opt/maven/bin/mvn package -DskipTests && /opt/jdk-25/bin/java -jar target/flight-regularity-service.jar
```
数据库连接可用环境变量覆盖：`DB_URL` / `DB_USER` / `DB_PASSWORD`。

### 2.3 测试账号（仅本隔离开发/验证环境，非生产配置）
| 账号 | 密码 | 权限 |
| --- | --- | --- |
| `manager` | `manager123` | 读写（值班经理：登记、上报事件、重算、锁定、确认告警） |
| `viewer` | `viewer123` | 只读（查询统计、日报、告警、口径） |

Swagger UI（`/swagger-ui.html`）与 OpenAPI 文档（`/v3/api-docs`）开放访问，无需登录。

---

## 3. 验证方式

### 3.1 自动化测试（18 个用例）
```bash
/opt/maven/bin/mvn test
```
覆盖：状态机合法/非法路径（含取消后恢复、起飞后备降、备降后取消、未推出直接落地）、
正负号变更日志、判定绑定计划版本（改计划不回写）、迟到/补录标记、时间更正重判、
日报口径与实时口径、同一批事件重算结果一致、锁定后更正版本、差异定位到具体变更。

### 3.2 端到端脚本（48 项断言）
服务启动后执行：
```bash
./scripts/verify.sh                    # 默认打 http://127.0.0.1:8080
./scripts/verify.sh http://localhost:8080
# Compose 环境（库为 flightops/flight/flight）：
PGUSER=flight PGPASSWORD=flight PGDATABASE=flightops ./scripts/verify.sh
```
脚本完整走一遍业务故事线：登记实例（含同号双航段、加班）→ 非法流转拒绝并告警 →
正常事件流（窗口内 REALTIME、正负号日志）→ 凌晨补录落地时间（时间更正）→ 实时口径（66.67%）
→ 日报 v1（50%，两口径分列）→ 锁定 → 航司改计划不回写 → 超窗补录（BACKFILL）→
更正版 v2（75%）→ 差异定位到具体变更 → 更正记录（原值/更正值/原因）→ 鉴权与文档检查。
脚本默认先用 psql 清空业务表保证可重复执行（`CLEAN_DB=0` 可关闭，聚合断言将跳过）。

### 3.3 手工验证示例（curl）
```bash
# 登记实例（首版计划）
curl -u manager:manager123 -X POST localhost:8080/api/instances -H 'Content-Type: application/json' -d '{
  "flightNo":"CA1234","serviceDate":"2026-11-13","legNo":1,
  "origin":"ZBAA","destination":"ZSSS",
  "schedDeparture":"2026-11-13T00:00:00Z","schedArrival":"2026-11-13T02:00:00Z",
  "effectiveFrom":"2026-11-11T00:00:00Z"}'

# 上报事件（非法流转会 409 并产生告警）
curl -u manager:manager123 -X POST localhost:8080/api/events -H 'Content-Type: application/json' -d '{
  "flightNo":"CA1234","serviceDate":"2026-11-13","legNo":1,
  "toStatus":"BOARDING","eventTime":"2026-11-13T00:30:00Z"}'

# 实时口径 / 日报 / 差异 / 更正 / 告警 / 口径
curl -u viewer:viewer123 'localhost:8080/api/stats/realtime?date=2026-11-13'
curl -u manager:manager123 -X POST localhost:8080/api/reports/2026-11-13/recompute
curl -u manager:manager123 -X POST localhost:8080/api/reports/2026-11-13/lock
curl -u manager:manager123 -X POST localhost:8080/api/reports/2026-11-13/recompute \
  -H 'Content-Type: application/json' -d '{"kind":"CORRECTION","reason":"补录起飞时间"}'
curl -u viewer:viewer123 'localhost:8080/api/reports/2026-11-13/diff?from=1&to=2'
curl -u viewer:viewer123 'localhost:8080/api/reports/2026-11-13/corrections'
curl -u viewer:viewer123 'localhost:8080/api/alerts?date=2026-11-13'
curl -u viewer:viewer123 'localhost:8080/api/caliber'
```

### 3.4 本题实际执行过的验证
- `mvn test`：18/18 通过（StateMachineTest 5、EventIngestServiceTest 8、ReportServiceTest 5）。
- `./scripts/verify.sh`：48/48 断言通过（本机 `java -jar` + 容器 PostgreSQL 18 实跑）。
- Docker/Compose 配置经静态核查（本容器无 docker 守护进程，未实际构建镜像）。

---

## 4. API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/instances` | 登记航班实例（含首版计划），重复主键 409 |
| GET | `/api/instances?date=` | 按执行日期查实例 |
| GET | `/api/instances/{flightNo}/{date}/{leg}` | 实例详情（含计划版本列表） |
| GET | `/api/instances/{flightNo}/{date}/{leg}/changes` | 变更日志（正负号+版本） |
| POST | `/api/instances/{flightNo}/{date}/{leg}/schedule` | 计划变更（新版本，不回写已发布结论） |
| POST | `/api/events` | 上报状态变更（状态机校验/迟到窗口/正负号入账） |
| GET | `/api/stats/realtime?date=` | 实时口径正常率 |
| POST | `/api/reports/{date}/recompute` | 重算日报（JSON body: kind/reason） |
| POST | `/api/reports/{date}/lock` | 锁定最新版本 |
| GET | `/api/reports/{date}` | 全部版本（日报/实时口径分列） |
| GET | `/api/reports/{date}/versions/{n}` | 指定版本逐航班明细 |
| GET | `/api/reports/{date}/diff?from=&to=` | 版本间差异（定位到具体变更） |
| GET | `/api/reports/{date}/corrections` | 更正记录（原值/更正值/原因） |
| GET | `/api/alerts?date=` | 告警查询；`PUT /api/alerts/{id}/acknowledge` 确认 |
| GET | `/api/caliber` | 固化统计口径 |

## 5. 配置项（`application.yml`，均可环境变量覆盖）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `app.release-tolerance-minutes` | 20 | 放行正常容差（分钟） |
| `app.late-window-hours` | 6 | 迟到窗口（小时），超窗走补录 |
| `app.diff-threshold-minutes` | 5 | 版本间延误分钟差异阈值 |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 本机 ccdev 库 | 数据源 |
| `APP_MANAGER_PASSWORD` / `APP_VIEWER_PASSWORD` | manager123 / viewer123 | 测试账号密码 |

## 6. 数据存储（PostgreSQL 分区表）

| 表 | 分区键 | 说明 |
| --- | --- | --- |
| `flight_instance` | `service_date` 按月 | 实例明细（当前投影），主键 (航班号, 执行日期, 航段) |
| `schedule_version` | — | 计划时刻版本 |
| `flight_change_log` | `service_date` 按月 | 变更日志（正负号+版本，含迟到/来源标记） |
| `daily_report` | `report_date` 按月 | 日报版本与聚合结果（ORIGINAL/CORRECTION，OPEN/LOCKED） |
| `daily_report_item` | `service_date` 按月 | 日报明细（日报口径与实时口径分列） |
| `report_correction` | `report_date` 按月 | 更正记录（原值/更正值/原因） |
| `report_diff` | `report_date` 按月 | 版本间差异（定位到具体变更版本） |
| `alert` | — | 非法流转等告警 |

分区由 Flyway `V1__init.sql` 预建（2026-01 ~ 2027-12 按月 + DEFAULT），超范围数据落 DEFAULT 分区。
