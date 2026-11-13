#!/usr/bin/env bash
# 多口径并行计算与发布仲裁子系统端到端验证
#   口径注册库 → 并行物化（机场正常/航司延误）→ 逐口径补偿与分叉审计
#   → 发布仲裁（归因/拦截）→ 切换评审（撞锁定顺延）→ 口径改版双版本绑定
#   → 下游绑定台账 → 历史补齐区间分列
# 用法: ./scripts/verify-multi-caliber.sh [BASE_URL]
set -euo pipefail

BASE="${1:-http://127.0.0.1:8080}"
MANAGER="manager:manager123"
VIEWER="viewer:viewer123"
DATE="$(date +%F)"
PASS=0
FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  ✔ $1"; }
bad()  { FAIL=$((FAIL+1)); say "  ✘ $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 [$2]"; else bad "$1 期望=$3 实际=$2"; fi; }
at()   { date -u -d "$1" +%Y-%m-%dT%H:%M:%SZ; }

req() {
  local method="$1" path="$2" auth="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -s -X "$method" -u "$auth" -H 'Content-Type: application/json' -d "$body" "$BASE$path"
  else
    curl -s -X "$method" -u "$auth" "$BASE$path"
  fi
}

say "== 0. 环境准备（清运行期数据，保留 V2 种子口径与基线主口径） =="
PGHOST="${PGHOST:-127.0.0.1}"; PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-ccdev}"; PGPASSWORD="${PGPASSWORD:-ccdev}"; PGDATABASE="${PGDATABASE:-ccdev}"
export PGPASSWORD
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -q -c \
  "TRUNCATE flight_change_log, daily_report_item, report_correction, report_diff, schedule_version, alert, daily_report, flight_instance, caliber_release_diff, caliber_result_item, caliber_compensation, caliber_switch_conflict, downstream_binding, caliber_backfill_segment, caliber_run RESTART IDENTITY CASCADE" \
  && psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -q -c \
  "DELETE FROM caliber_switch_review WHERE id > 1; DELETE FROM caliber_definition WHERE id > 3; UPDATE caliber_definition SET status='ACTIVE', superseded_at=NULL WHERE id <= 3" \
  && say "  运行期表已清空，种子口径复位"

N=$((RANDOM % 9000 + 1000))
F1="CA$N"; F2="MU$((N+1))"; F3="CZ$((N+2))"
EFF="$(at '-2 days')"

reg() {
  req POST /api/instances "$MANAGER" "{
    \"flightNo\":\"$1\",\"serviceDate\":\"$DATE\",\"legNo\":$2,
    \"origin\":\"ZBAA\",\"destination\":\"ZSSS\",\"kind\":\"$3\",
    \"schedDeparture\":\"$4\",\"schedArrival\":\"$(at "$4 + 2 hours")\",
    \"effectiveFrom\":\"$EFF\"}" >/dev/null
}
evt() {
  req POST /api/events "$MANAGER" "{
    \"flightNo\":\"$1\",\"serviceDate\":\"$DATE\",\"legNo\":$2,
    \"toStatus\":\"$3\",\"eventTime\":\"$4\"}"
}

say "== 1. 构造差额故事：$F1 推出 +10（机场正常）/ 起飞 +40（航司延误）/ 到达准点（集团正常） =="
S1="$(at '-4 hours')"
reg "$F1" 1 REGULAR "$S1"
evt "$F1" 1 BOARDING "$(at "$S1 - 30 minutes")" >/dev/null
evt "$F1" 1 PUSHBACK "$(at "$S1 + 10 minutes")" >/dev/null
evt "$F1" 1 DEPARTED "$(at "$S1 + 40 minutes")" >/dev/null
evt "$F1" 1 LANDED "$(at "$S1 + 2 hours")" >/dev/null
reg "$F2" 1 REGULAR "$(at '-2 hours')"
evt "$F2" 1 CANCELLED "$(at '-90 minutes')" >/dev/null
reg "$F3" 1 EXTRA "$(at '-11 hours')"

say "== 2. 并行计算：同一批事件产出三套带版本物化结果 =="
RUNS=$(req POST "/api/caliber-runs/compute/$DATE" "$MANAGER")
check "并行运行数量=3" "$(echo "$RUNS" | jq 'length')" "3"
check "结果均绑定口径 v1" "$(echo "$RUNS" | jq -c '[.[].caliberVersion] | unique')" "[1]"
check "区间标注 ORIGINAL" "$(echo "$RUNS" | jq '[.[].backfillScope] | unique | .[0]')" '"ORIGINAL"'
AIRPORT_RUN=$(echo "$RUNS" | jq ".[] | select(.caliberCode==\"AIRPORT\")")
AIRLINE_RUN=$(echo "$RUNS" | jq ".[] | select(.caliberCode==\"AIRLINE\")")
GROUP_RUN=$(echo "$RUNS" | jq ".[] | select(.caliberCode==\"GROUP\")")
check "机场分母=2(含应飞未飞)" "$(echo "$AIRPORT_RUN" | jq .counted)" "2"
check "机场正常=1" "$(echo "$AIRPORT_RUN" | jq .normal)" "1"
check "航司分母=1(仅已起飞)" "$(echo "$AIRLINE_RUN" | jq .counted)" "1"
check "航司正常=0(判延误)" "$(echo "$AIRLINE_RUN" | jq .normal)" "0"
check "集团分母=3(全部登记)" "$(echo "$GROUP_RUN" | jq .counted)" "3"
check "集团正常=1" "$(echo "$GROUP_RUN" | jq .normal)" "1"

say "== 3. 同航班跨口径对比：机场正常、航司延误（差额=推出与起飞之间 30 分钟等待） =="
CMP=$(req GET "/api/caliber-runs/$DATE/flights/$F1/legs/1/compare" "$VIEWER")
check "对比覆盖三套口径" "$(echo "$CMP" | jq '.caliberResults | length')" "3"
check "机场结论正常" "$(echo "$CMP" | jq -r '.caliberResults[] | select(.caliberCode=="AIRPORT") | .normal')" "true"
check "机场延误 10 分钟" "$(echo "$CMP" | jq -r '.caliberResults[] | select(.caliberCode=="AIRPORT") | .delayMinutes')" "10"
check "航司结论延误" "$(echo "$CMP" | jq -r '.caliberResults[] | select(.caliberCode=="AIRLINE") | .normal')" "false"
check "航司延误 40 分钟" "$(echo "$CMP" | jq -r '.caliberResults[] | select(.caliberCode=="AIRLINE") | .delayMinutes')" "40"
check "集团到达正常" "$(echo "$CMP" | jq -r '.caliberResults[] | select(.caliberCode=="GROUP") | .normal')" "true"

say "== 4. 首次发布三套运行（无基线直接发布） =="
for code in AIRPORT AIRLINE GROUP; do
  RID=$(echo "$RUNS" | jq ".[] | select(.caliberCode==\"$code\") | .id")
  P=$(req POST "/api/caliber-releases/$DATE/runs/$RID/publish" "$MANAGER")
  check "$code 运行发布成功" "$(echo "$P" | jq -r '.run.status')" "PUBLISHED"
done

say "== 5. 超窗补录：$F3 落地/起飞事件向所有并行口径逐口径补偿 =="
DEP="$(at '-11 hours + 5 minutes')"
evt "$F3" 1 BOARDING "$(at "$DEP - 40 minutes")" >/dev/null
evt "$F3" 1 PUSHBACK "$(at "$DEP - 10 minutes")" >/dev/null
evt "$F3" 1 DEPARTED "$DEP" >/dev/null
COMP=$(req GET "/api/caliber-runs/$DATE/compensations" "$VIEWER")
check "补偿记录=3事件×3口径=9条" "$(echo "$COMP" | jq 'length')" "9"
check "全部为超窗补录类型" "$(echo "$COMP" | jq '[.[].kind] | unique | .[0]')" '"LATE_BACKFILL"'
for code in AIRPORT AIRLINE GROUP; do
  check "$code 口径各留 3 条补偿" "$(echo "$COMP" | jq "[.[] | select(.caliberCode==\"$code\")] | length")" "3"
done
check "分区分叉审计为空" "$(req GET "/api/caliber-runs/$DATE/fork-audit" "$VIEWER")" "[]"
check "旧发布运行保持 PUBLISHED 基线" "$(req GET "/api/caliber-runs/$DATE" "$VIEWER" | jq "[.[] | select(.runNo==1 and .status!=\"PUBLISHED\")] | length")" "0"

say "== 6. 发布补偿后新运行：差额可归因到具体变更版本 =="
LATEST_AIRLINE=$(req GET "/api/caliber-runs/$DATE" "$VIEWER" | jq "[.[] | select(.caliberCode==\"AIRLINE\")] | max_by(.runNo)")
LA_ID=$(echo "$LATEST_AIRLINE" | jq .id)
OUT=$(req POST "/api/caliber-releases/$DATE/runs/$LA_ID/publish" "$MANAGER")
check "补偿运行发布通过" "$(echo "$OUT" | jq -r '.blocked')" "false"
check "差额全部可解释" "$(echo "$OUT" | jq '[.diffs[].attribution] | unique | .[0]')" '"EXPLAINED"'
check "差额归因到变更版本" "$(echo "$OUT" | jq -r '.diffs[0].causedByChanges | length > 0')" "true"
check "旧基线被取代" "$(req GET "/api/caliber-runs/$DATE" "$VIEWER" | jq "[.[] | select(.caliberCode==\"AIRLINE\" and .runNo==1) | .status][0]")" '"SUPERSEDED"'

say "== 7. 不可解释差额拦截发布（SQL 模拟无变更支撑的篡改） =="
TAMPER_RUN=$(req POST "/api/caliber-runs/compute/$DATE" "$MANAGER" | jq ".[] | select(.caliberCode==\"AIRLINE\") | .id")
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -q -c \
  "UPDATE caliber_result_item SET normal=TRUE, last_change_version=0 WHERE run_id=$TAMPER_RUN AND flight_no='$F1'"
CODE=$(curl -s -o /tmp/blocked.json -w '%{http_code}' -X POST -u "$MANAGER" "$BASE/api/caliber-releases/$DATE/runs/$TAMPER_RUN/publish")
check "不可解释差额 409 拦截" "$CODE" "409"
check "拦截信息含 UNEXPLAINED 指引" "$(jq -r '.message | contains("拦截")' /tmp/blocked.json)" "true"
check "被拦运行置 BLOCKED" "$(req GET "/api/caliber-runs/$DATE" "$VIEWER" | jq "[.[] | select(.id==$TAMPER_RUN) | .status][0]")" '"BLOCKED"'
check "拦截差额留痕可查" "$(req GET "/api/caliber-releases/$DATE/runs/$TAMPER_RUN/diffs" "$VIEWER" | jq '[.[].attribution] | index("UNEXPLAINED") != null')" "true"

say "== 8. 切换评审：无锁立即生效；撞日报锁定则顺延并留冲突记录 =="
R1=$(req POST /api/caliber-releases/switch-reviews "$MANAGER" \
  "{\"serviceDate\":\"$DATE\",\"toCaliberCode\":\"AIRLINE\",\"reason\":\"航司口径升主口径\"}")
RID1=$(echo "$R1" | jq .id)
req POST "/api/caliber-releases/switch-reviews/$RID1/decision" "$MANAGER" '{"approved":true,"comment":"同意"}' >/dev/null
check "主口径已切到航司" "$(req GET /api/caliber-releases/main "$VIEWER" | jq -r .caliberCode)" "AIRLINE"

req POST "/api/reports/$DATE/recompute" "$MANAGER" >/dev/null
req POST "/api/reports/$DATE/lock" "$MANAGER" >/dev/null
R2=$(req POST /api/caliber-releases/switch-reviews "$MANAGER" \
  "{\"serviceDate\":\"$DATE\",\"toCaliberCode\":\"GROUP\",\"reason\":\"集团口径上线\"}")
RID2=$(echo "$R2" | jq .id)
DEFER=$(req POST "/api/caliber-releases/switch-reviews/$RID2/decision" "$MANAGER" '{"approved":true,"comment":"同意但遇锁"}')
check "撞锁定评审顺延" "$(echo "$DEFER" | jq -r .status)" "CONFLICT_DEFERRED"
check "顺延到下一发布周期" "$(echo "$DEFER" | jq -r .effectiveDate)" "$(date -u -d '+1 day' +%F)"
check "冲突记录留痕" "$(req GET "/api/caliber-releases/switch-conflicts?date=$DATE" "$VIEWER" | jq 'length')" "1"
check "顺延期前主口径不变" "$(req GET /api/caliber-releases/main "$VIEWER" | jq -r .caliberCode)" "AIRLINE"
req POST "/api/caliber-releases/switch-reviews/$RID2/resolve" "$MANAGER" >/dev/null
check "落地后主口径切到集团" "$(req GET /api/caliber-releases/main "$VIEWER" | jq -r .caliberCode)" "GROUP"
check "冲突记录标记已解决" "$(req GET "/api/caliber-releases/switch-conflicts?date=$DATE" "$VIEWER" | jq '.[0].resolved')" "true"
check "驳回的评审不改主口径" "$(req POST /api/caliber-releases/switch-reviews "$MANAGER" "{\"serviceDate\":\"$DATE\",\"toCaliberCode\":\"AIRPORT\",\"reason\":\"试探\"}" | jq .id | xargs -I{} curl -s -X POST -u "$MANAGER" -H 'Content-Type: application/json' -d '{"approved":false}' "$BASE/api/caliber-releases/switch-reviews/{}/decision" | jq -r .status)" "REJECTED"

say "== 9. 口径改版生成新版本，历史物化结果不追溯改写 =="
# 容差 15→45：$F1 起飞延误 40 分钟在 v2 下翻转正常，制造可归因于口径版本切换的差额
V2=$(req POST /api/calibers/AIRLINE/versions "$MANAGER" \
  "{\"timestampSource\":\"DEPARTURE\",\"denominatorRule\":\"FLOWN_ONLY\",\"toleranceMinutes\":45,\"description\":\"容差放宽\",\"changeSummary\":\"容差15→45\"}")
check "航司口径生成 v2" "$(echo "$V2" | jq .version)" "2"
check "旧版退役保留" "$(req GET /api/calibers/AIRLINE "$VIEWER" | jq '[.[] | select(.version==1) | .status][0]')" '"RETIRED"'
FIRST_RUN=$(req GET "/api/caliber-runs/$DATE" "$VIEWER" | jq "[.[] | select(.caliberCode==\"AIRLINE\")] | min_by(.runNo)")
check "历史运行仍绑定 v1" "$(echo "$FIRST_RUN" | jq .caliberVersion)" "1"
check "历史运行容差快照仍是 15" "$(echo "$FIRST_RUN" | jq .toleranceMinutes)" "15"
NEW_RUN=$(req POST "/api/caliber-runs/compute/$DATE" "$MANAGER" | jq ".[] | select(.caliberCode==\"AIRLINE\")")
check "新运行绑定 v2" "$(echo "$NEW_RUN" | jq .caliberVersion)" "2"
NID=$(echo "$NEW_RUN" | jq .id)
PUB=$(req POST "/api/caliber-releases/$DATE/runs/$NID/publish" "$MANAGER")
check "口径版本差额归因于定义切换" "$(echo "$PUB" | jq -r '.diffs[0].causedByChanges')" "CALIBER_DEF_V1->V2"

say "== 10. 下游绑定台账：大屏/月报/对外报送各引用哪一版 =="
PERIOD="${DATE:0:7}"
BIND=$(req POST /api/downstream-bindings "$MANAGER" \
  "{\"target\":\"MONTHLY_REPORT\",\"period\":\"$PERIOD\",\"caliberCode\":\"AIRPORT\",\"caliberVersion\":1,\"runId\":$(echo "$AIRPORT_RUN" | jq .id),\"serviceDate\":\"$DATE\",\"note\":\"锁定月报\"}")
check "月报绑定成功" "$(echo "$BIND" | jq -r .active)" "true"
check "月报缺 runId 被拒绝(400)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST -u "$MANAGER" -H 'Content-Type: application/json' -d "{\"target\":\"EXTERNAL_REPORT\",\"caliberCode\":\"AIRPORT\",\"caliberVersion\":1}" "$BASE/api/downstream-bindings")" "400"
req POST /api/downstream-bindings "$MANAGER" \
  "{\"target\":\"SCREEN\",\"caliberCode\":\"AIRLINE\",\"note\":\"大屏实时引用\"}" >/dev/null
check "生效绑定含大屏与月报" "$(req GET /api/downstream-bindings/active "$VIEWER" | jq 'length')" "2"
check "按口径版本可反查影响面" "$(req GET "/api/downstream-bindings/by-caliber?code=AIRPORT&version=1" "$VIEWER" | jq 'length > 0')" "true"

say "== 11. 历史补齐：补齐区间与原始区间分列标注 =="
BF=$(req POST /api/caliber-runs/backfill "$MANAGER" \
  "{\"rangeFrom\":\"$DATE\",\"rangeTo\":\"$DATE\",\"reason\":\"新口径上线前补齐\"}")
check "三套口径各一条区间标注" "$(echo "$BF" | jq '.segments | length')" "3"
check "区间类型为 BACKFILL" "$(echo "$BF" | jq -r '.segments[0].segmentType')" "BACKFILL"
check "补齐运行区间标注 BACKFILL" "$(echo "$BF" | jq '[.runs[].backfillScope] | unique | .[0]')" '"BACKFILL"'
check "区间台账可查" "$(req GET "/api/caliber-runs/backfill/segments" "$VIEWER" | jq 'length')" "3"

say "== 12. 鉴权 =="
check "未认证拒绝(401)" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/caliber-runs/$DATE")" "401"
check "viewer 只读可查" "$(curl -s -o /dev/null -w '%{http_code}' -u "$VIEWER" "$BASE/api/calibers")" "200"
check "viewer 不可写(403)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST -u "$VIEWER" -H 'Content-Type: application/json' -d '{}' "$BASE/api/caliber-runs/compute/$DATE")" "403"

say ""
say "通过 $PASS 项，失败 $FAIL 项"
[ "$FAIL" = "0" ]
