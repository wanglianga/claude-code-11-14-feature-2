#!/usr/bin/env bash
# 端到端验证脚本：覆盖题目要求的完整故事线
#   实例主键 → 状态机（合法/非法）→ 正负号变更日志 → 实时口径 vs 日报口径
#   → 日报锁定 → 计划变更不回写 → 超窗补录 → 更正版本 → 差异定位到具体变更
# 用法: ./scripts/verify.sh [BASE_URL]
# 说明: 若本机可用 psql 且 CLEAN_DB=1（默认），会先清空业务表保证聚合断言可重复。
set -euo pipefail

BASE="${1:-http://127.0.0.1:8080}"
MANAGER="manager:manager123"
VIEWER="viewer:viewer123"
DATE="$(date +%F)"
DATE2="$(date -u -d '+1 day' +%F)"
PASS=0
FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  ✔ $1"; }
bad()  { FAIL=$((FAIL+1)); say "  ✘ $1"; }
check() { # check <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then ok "$1 [$2]"; else bad "$1 期望=$3 实际=$2"; fi
}

# 时间辅助：UTC ISO8601，支持 "基准时间 +/- N minutes" 运算
at() { date -u -d "$1" +%Y-%m-%dT%H:%M:%SZ; }

req() { # req <method> <path> <auth> [json]
  local method="$1" path="$2" auth="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -s -X "$method" -u "$auth" -H 'Content-Type: application/json' -d "$body" "$BASE$path"
  else
    curl -s -X "$method" -u "$auth" "$BASE$path"
  fi
}

say "== 0. 环境准备 =="
# 清库连接可用环境变量覆盖（默认本任务容器内开发库；Compose 验证时用 flight/flight/flightops）
PGHOST="${PGHOST:-127.0.0.1}"; PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-ccdev}"; PGPASSWORD="${PGPASSWORD:-ccdev}"; PGDATABASE="${PGDATABASE:-ccdev}"
export PGPASSWORD
if command -v psql >/dev/null 2>&1 && [ "${CLEAN_DB:-1}" = "1" ]; then
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -q -c \
    "TRUNCATE flight_change_log, daily_report_item, report_correction, report_diff, schedule_version, alert, daily_report, flight_instance RESTART IDENTITY CASCADE" \
    && say "  业务表已清空（可重复执行验证）"
  CLEAN=1
else
  say "  未清理数据库（无 psql 或 CLEAN_DB=0），聚合断言将跳过"
  CLEAN=0
fi

# 每个航班一个独立实例；同一航班号不同航段各自成实例
N=$((RANDOM % 9000 + 1000))
F1="CA$N"; F2="MU$((N+1))"; F3="CZ$((N+2))"; F4="ZH$((N+3))"; F5="OK$((N+4))"
say "  验证日期=$DATE 航班=$F1(航段1,2) $F2 $F3 $F4(加班) $F5(非法流转,日期$DATE2)"

EFF="$(at '-2 days')"
S1="$(at '-4 hours')"
S2="$(at '-5 hours')"
S3="$(at '-2 hours')"
S4="$(at '-3 hours')"
S5="$(at '-11 hours')"

reg() { # reg <flightNo> <leg> <kind> <schedDep> [serviceDate]
  req POST /api/instances "$MANAGER" "{
    \"flightNo\":\"$1\",\"serviceDate\":\"${5:-$DATE}\",\"legNo\":$2,
    \"origin\":\"ZBAA\",\"destination\":\"ZSSS\",\"kind\":\"$3\",
    \"schedDeparture\":\"$4\",\"schedArrival\":\"$(at "$4 + 2 hours")\",
    \"effectiveFrom\":\"$EFF\"}" >/dev/null
}
evt() { # evt <flightNo> <leg> <toStatus> <eventTime> [serviceDate]
  req POST /api/events "$MANAGER" "{
    \"flightNo\":\"$1\",\"serviceDate\":\"${5:-$DATE}\",\"legNo\":$2,
    \"toStatus\":\"$3\",\"eventTime\":\"$4\"}"
}

say "== 1. 登记实例（主键=航班号+执行日期+航段） =="
reg "$F1" 1 REGULAR "$S1"
reg "$F2" 1 REGULAR "$S2"
reg "$F3" 1 REGULAR "$S3"
reg "$F1" 2 REGULAR "$S4"     # 同号不同航段，各自成实例
reg "$F4" 1 EXTRA   "$S5"     # 加班，独立实例
reg "$F5" 1 REGULAR "$(at '+26 hours')" "$DATE2"
DUP=$(req POST /api/instances "$MANAGER" "{\"flightNo\":\"$F1\",\"serviceDate\":\"$DATE\",\"legNo\":1,\"origin\":\"ZBAA\",\"destination\":\"ZSSS\",\"schedDeparture\":\"$S1\",\"schedArrival\":\"$(at "$S1 + 2 hours")\"}" | jq -r .status)
check "重复登记同一主键被拒绝(409)" "$DUP" "409"

say "== 2. 状态机：非法流转拒绝并告警 =="
CODE=$(curl -s -o /tmp/illegal.json -w '%{http_code}' -X POST -u "$MANAGER" -H 'Content-Type: application/json' \
  -d "{\"flightNo\":\"$F5\",\"serviceDate\":\"$DATE2\",\"legNo\":1,\"toStatus\":\"LANDED\",\"eventTime\":\"$(at '-10 minutes')\"}" "$BASE/api/events")
check "未推出直接落地被拒绝(409)" "$CODE" "409"
check "响应携带告警ID" "$(jq -r '.alertId != null' /tmp/illegal.json)" "true"
ALERTS=$(req GET "/api/alerts?date=$DATE2" "$VIEWER")
check "告警可查(rule)" "$(echo "$ALERTS" | jq -r '.[0].rule')" "ILLEGAL_TRANSITION"
check "告警内容含非法路径" "$(echo "$ALERTS" | jq -r '.[0].message | contains("PLANNED -> LANDED")')" "true"

say "== 3. 正常事件流（窗口内 REALTIME，正负号变更日志） =="
# F1 航段1：延误 12 分钟 → 正常
evt "$F1" 1 BOARDING "$(at "$S1 - 30 minutes")" >/dev/null
evt "$F1" 1 PUSHBACK "$(at "$S1 - 5 minutes")" >/dev/null
R=$(evt "$F1" 1 DEPARTED "$(at "$S1 + 12 minutes")")
check "F1-L1 判定正常" "$(echo "$R" | jq -r .releaseNormal)" "true"
check "F1-L1 延误 12 分钟" "$(echo "$R" | jq -r .releaseDelayMinutes)" "12"
check "窗口内事件来源 REALTIME" "$(echo "$R" | jq -r .source)" "REALTIME"
check "窗口内事件不标记迟到" "$(echo "$R" | jq -r .late)" "false"
evt "$F1" 1 LANDED "$(at "$S1 + 2 hours")" >/dev/null
# 凌晨补录落地时间：同状态补录 → 时间更正（窗口内触发更新与撤回）
R=$(evt "$F1" 1 LANDED "$(at "$S1 + 2 hours + 10 minutes")")
check "补录落地时间按时间更正受理" "$(echo "$R" | jq -r .eventType)" "TIME_CORRECTION"
# F2：延误 50 分钟 → 不正常
evt "$F2" 1 DELAYED  "$(at "$S2 + 20 minutes")" >/dev/null
evt "$F2" 1 BOARDING "$(at "$S2 + 30 minutes")" >/dev/null
evt "$F2" 1 PUSHBACK "$(at "$S2 + 45 minutes")" >/dev/null
R=$(evt "$F2" 1 DEPARTED "$(at "$S2 + 50 minutes")")
check "F2 判定不正常" "$(echo "$R" | jq -r .releaseNormal)" "false"
evt "$F2" 1 LANDED "$(at "$S2 + 3 hours")" >/dev/null
# F3：取消（不计入分母）
evt "$F3" 1 CANCELLED "$(at '-90 minutes')" >/dev/null
# F1 航段2：起飞后备降再续飞，算一班
evt "$F1" 2 BOARDING "$(at "$S4 - 30 minutes")" >/dev/null
evt "$F1" 2 PUSHBACK "$(at "$S4 - 5 minutes")" >/dev/null
evt "$F1" 2 DEPARTED "$(at "$S4 + 5 minutes")" >/dev/null
evt "$F1" 2 DIVERTED "$(at "$S4 + 90 minutes")" >/dev/null
R=$(evt "$F1" 2 DEPARTED "$(at "$S4 + 150 minutes")")
check "备降后续飞是合法流转" "$(echo "$R" | jq -r .eventType)" "STATUS_CHANGE"
evt "$F1" 2 LANDED "$(at "$S4 + 4 hours")" >/dev/null

CH=$(req GET "/api/instances/$F1/$DATE/1/changes" "$VIEWER")
check "变更日志含冲抵(-1)条目" "$(echo "$CH" | jq '[.[] | select(.sign==-1)] | length > 0')" "true"
check "变更日志版本连续递增" "$(echo "$CH" | jq '[.[].id.changeVersion] as $v | $v == [range(1; ($v|length)+1)]')" "true"

say "== 4. 实时口径（分母=已判定班次） =="
RT=$(req GET "/api/stats/realtime?date=$DATE" "$VIEWER")
if [ "$CLEAN" = "1" ]; then
  check "实时分母=3(两航段+F2)" "$(echo "$RT" | jq -r .counted)" "3"
  check "实时正常=2" "$(echo "$RT" | jq -r .normal)" "2"
  check "实时正常率 66.67" "$(echo "$RT" | jq -r .rate)" "66.67"
  check "取消不计入分母" "$(echo "$RT" | jq -r .cancelledExcluded)" "1"
  check "应飞未飞待判定" "$(echo "$RT" | jq -r .pendingJudgment)" "1"
else
  say "  （跳过聚合断言：数据库未清理）"
fi

say "== 5. 日报 v1（同一批事件重算，日报口径与实时口径分列） =="
V1=$(req POST "/api/reports/$DATE/recompute" "$MANAGER")
if [ "$CLEAN" = "1" ]; then
  check "日报分母=4(含应飞未飞)" "$(echo "$V1" | jq -r .counted)" "4"
  check "日报正常=2" "$(echo "$V1" | jq -r .normal)" "2"
  check "日报正常率 50" "$(echo "$V1" | jq -r .rate)" "50"
  check "实时口径率分列 66.67" "$(echo "$V1" | jq -r .realtimeRate)" "66.67"
fi
req POST "/api/reports/$DATE/lock" "$MANAGER" >/dev/null
LOCKED=$(req GET "/api/reports/$DATE" "$VIEWER" | jq -r '.[0].status')
check "日报 v1 已锁定" "$LOCKED" "LOCKED"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST -u "$MANAGER" "$BASE/api/reports/$DATE/recompute")
check "锁定后普通重算被拒绝(400)" "$CODE" "400"

say "== 6. 航司事后改计划：已发布结论不回写 =="
req POST "/api/instances/$F2/$DATE/1/schedule" "$MANAGER" \
  "{\"schedDeparture\":\"$(at "$S2 + 1 hour")\",\"schedArrival\":\"$(at "$S2 + 3 hours")\",\"effectiveFrom\":\"$(at '-1 minute')\",\"source\":\"AOC\"}" >/dev/null
DET=$(req GET "/api/instances/$F2/$DATE/1" "$VIEWER")
check "计划版本已增至 2" "$(echo "$DET" | jq -r '.scheduleVersions | length')" "2"
check "判定仍绑定 v1 计划" "$(echo "$DET" | jq -r '.instance.releaseVersionId')" "$(echo "$DET" | jq -r '.scheduleVersions[0].id')"
check "已发布结论不回写(仍不正常)" "$(echo "$DET" | jq -r '.instance.releaseNormal')" "false"

say "== 7. 超窗补录（迟到窗口 6h）走 BACKFILL 并标注来源 =="
evt "$F4" 1 BOARDING "$(at "$S5 - 30 minutes")" >/dev/null
evt "$F4" 1 PUSHBACK "$(at "$S5 - 5 minutes")" >/dev/null
R=$(evt "$F4" 1 DEPARTED "$(at "$S5 + 5 minutes")")
check "超窗事件标记迟到" "$(echo "$R" | jq -r .late)" "true"
check "超窗事件来源 BACKFILL" "$(echo "$R" | jq -r .source)" "BACKFILL"
check "补录后判定正常(延误5)" "$(echo "$R" | jq -r .releaseNormal)" "true"

say "== 8. 更正版本：原值/更正值/原因可查，差异定位到具体变更 =="
V2=$(req POST "/api/reports/$DATE/recompute" "$MANAGER" "{\"kind\":\"CORRECTION\",\"reason\":\"$F4 补录起飞时间\"}")
check "v2 为更正版" "$(echo "$V2" | jq -r .kind)" "CORRECTION"
if [ "$CLEAN" = "1" ]; then
  check "v2 日报正常率 75" "$(echo "$V2" | jq -r .rate)" "75"
fi
V1AGAIN=$(req GET "/api/reports/$DATE" "$VIEWER" | jq -r '.[0]')
check "v1 原值不被改写(仍50)" "$(echo "$V1AGAIN" | jq -r .rate)" "50"
check "v1 仍锁定" "$(echo "$V1AGAIN" | jq -r .status)" "LOCKED"

DIFF=$(req GET "/api/reports/$DATE/diff?from=1&to=2" "$VIEWER")
check "差异定位到航班 $F4" "$(echo "$DIFF" | jq -r "[.[] | select(.id.flightNo==\"$F4\")] | length > 0")" "true"
check "正常性翻转 false→true" "$(echo "$DIFF" | jq -r "[.[] | select(.id.flightNo==\"$F4\" and .id.field==\"normal\")] | .[0] | .oldValue + \"->\" + .newValue")" "false->true"
check "差异定位到具体变更版本" "$(echo "$DIFF" | jq -r "[.[] | select(.id.flightNo==\"$F4\")] | .[0].causedByChanges | length > 0")" "true"
CORR=$(req GET "/api/reports/$DATE/corrections" "$VIEWER")
check "更正记录含原值/更正值" "$(echo "$CORR" | jq -r "[.[] | select(.id.flightNo==\"$F4\" and .id.field==\"normal\")] | .[0] | .oldValue + \"->\" + .newValue")" "false->true"
check "更正记录含原因" "$(echo "$CORR" | jq -r ".[0].reason | contains(\"补录\")")" "true"

ITEMS=$(req GET "/api/reports/$DATE/versions/2" "$VIEWER" | jq -r ".items[] | select(.id.flightNo==\"$F4\")")
check "明细实时口径与日报口径分列" "$(echo "$ITEMS" | jq -r '[has("realtimeCounted"), has("counted")] | all')" "true"
check "$F4 更正后两口径一致(normal)" "$(echo "$ITEMS" | jq -r '[.normal, .realtimeNormal] | unique | length')" "1"

say "== 9. 鉴权与文档 =="
check "未认证访问被拒(401)" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/stats/realtime?date=$DATE")" "401"
check "只读账号可查询" "$(curl -s -o /dev/null -w '%{http_code}' -u "$VIEWER" "$BASE/api/stats/realtime?date=$DATE")" "200"
check "只读账号不可写(403)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST -u "$VIEWER" -H 'Content-Type: application/json' -d '{}' "$BASE/api/events")" "403"
check "OpenAPI 文档可访问" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs")" "200"
check "Swagger UI 可访问" "$(curl -s -o /dev/null -w '%{http_code}' -L "$BASE/swagger-ui.html")" "200"
check "口径端点可查" "$(req GET /api/caliber "$VIEWER" | jq -r '.rules | length >= 9')" "true"

say ""
say "通过 $PASS 项，失败 $FAIL 项"
[ "$FAIL" = "0" ]
