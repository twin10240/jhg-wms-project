#!/usr/bin/env bash
# 배송 상태 이전 SQL 검사 — 검사 전용 임시 PostgreSQL DB에서만 돈다. 개발 DB wms·공개 compose DB는 건드리지 않는다.
#
# 순서: 구 앱(OLD_REF)으로 실제 스키마 생성 → 기존 행 심기 → (a) 정상 이전·재실행·제약
#       (b) 모순 데이터 중단 (c) 새 앱이 먼저 떴던 DB의 사후 이전 → 이전된 DB에 새 앱 기동·송장 조회.
# 전제: 로컬 brew postgresql@17, 롤 wms(createdb 권한), JDK 21. 실행: bash docs/check-delivery-failure-migration.sh
# 끝나면(실패해도) 이 스크립트가 만든 DB·워크트리·프로세스만 지운다.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SQL="$ROOT/docs/wms-delivery-failure-migration.sql"
OLD_REF=${OLD_REF:-origin/master}
PGHOST=${PGHOST:-localhost} PGUSER=${PGUSER:-wms} PGPASSWORD=${PGPASSWORD:-wms}
export PGHOST PGUSER PGPASSWORD
PORT=${PORT:-18091}
RUN=delivery_mig_$$
DB=${RUN}_main DB_BAD=${RUN}_bad DB_EARLY=${RUN}_early
WORK=$(mktemp -d)
APP_PID=

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; exit 1; }
q()    { psql -v ON_ERROR_STOP=1 -X -Atq -d "$1" -c "$2"; }

cleanup() {
    [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null || true
    for d in "$DB" "$DB_BAD" "$DB_EARLY"; do dropdb --if-exists "$d" 2>/dev/null || true; done
    git -C "$ROOT" worktree remove --force "$WORK/old" 2>/dev/null || true
    rm -rf "$WORK"
    echo "정리: 임시 DB 3개·구 앱 워크트리 삭제"
}
trap cleanup EXIT

# 앱을 띄워 기동 완료를 기다린다. 실패하면 로그 끝을 보여주고 중단한다.
start_app() {   # $1=jar $2=db $3=log
    java -jar "$1" --spring.datasource.url="jdbc:postgresql://$PGHOST:5432/$2" --server.port="$PORT" \
        --oms.base-url=http://localhost:1 >"$3" 2>&1 &
    APP_PID=$!
    for _ in $(seq 1 90); do
        grep -q "Started JhgWmsApplication" "$3" && return 0
        kill -0 "$APP_PID" 2>/dev/null || { tail -40 "$3"; fail "앱 기동 실패($2)"; }
        sleep 1
    done
    tail -40 "$3"; fail "앱 기동 시간 초과($2)"
}
stop_app() { kill "$APP_PID"; wait "$APP_PID" 2>/dev/null || true; APP_PID=; }

echo "== 준비: 구 앱($OLD_REF)·새 앱 jar 빌드"
git -C "$ROOT" worktree add -q --detach "$WORK/old" "$OLD_REF"
(cd "$WORK/old" && ./gradlew -q bootJar -x test)
(cd "$ROOT" && ./gradlew -q bootJar -x test)
OLD_JAR=$(ls "$WORK"/old/build/libs/*.jar | grep -v plain | head -1)
NEW_JAR=$(ls "$ROOT"/build/libs/*.jar | grep -v plain | head -1)

echo "== 구 앱으로 스키마 생성"
createdb "$DB"
start_app "$OLD_JAR" "$DB" "$WORK/old-app.log"
stop_app
q "$DB" "SELECT 1 FROM information_schema.columns WHERE table_name='reservation' AND column_name='delivery_version'" \
    | grep -q . && fail "구 스키마에 delivery_version이 이미 있다 — OLD_REF 확인"

# 기존 행 네 종류: 예약(1)·해제(2)·배송 중(3)·완료(4). 시각은 마이크로초까지 넣어 절삭 없이 옮기는지 본다.
q "$DB" "
INSERT INTO reservation (reservation_id, request_key, order_id, status, carrier_code, tracking_number, issued_at, delivered_at, created_at) VALUES
 (9001, '00000000-0000-0000-0000-000000009001', 9001, 'RESERVED', NULL, NULL, NULL, NULL, now()),
 (9002, '00000000-0000-0000-0000-000000009002', 9002, 'RELEASED', NULL, NULL, NULL, NULL, now()),
 (9003, '00000000-0000-0000-0000-000000009003', 9003, 'SHIPPED', 'MOCK', 'MOCK-9003-T', '2026-10-01T01:00:00.123456Z', NULL, now()),
 (9004, '00000000-0000-0000-0000-000000009004', 9004, 'SHIPPED', 'MOCK', 'MOCK-9004-T', '2026-10-01T01:00:00.123456Z', '2026-10-02T02:00:00.654321Z', now());"

createdb -T "$DB" "$DB_BAD"
createdb -T "$DB" "$DB_EARLY"

echo "== (a) 정상 이전"
psql -v ON_ERROR_STOP=1 -X -q -d "$DB" -f "$SQL" >/dev/null
row() { q "$DB" "SELECT coalesce(delivery_status,'-')||'|'||delivery_version||'|'||coalesce(delivery_status_at::text,'-')||'|'||coalesce(delivery_failure_code,'-') FROM reservation WHERE reservation_id=$1"; }
[ "$(row 9001)" = "-|0|-|-" ] && pass "예약 행: 상태 없음 v0" || fail "예약 행: $(row 9001)"
[ "$(row 9002)" = "-|0|-|-" ] && pass "해제 행: 상태 없음 v0" || fail "해제 행: $(row 9002)"
[ "$(q "$DB" "SELECT delivery_status||delivery_version||(delivery_status_at=issued_at) FROM reservation WHERE reservation_id=9003")" = "SHIPPED1true" ] \
    && pass "배송 중 행: SHIPPED v1, 시각=issued_at" || fail "배송 중 행: $(row 9003)"
[ "$(q "$DB" "SELECT delivery_status||delivery_version||(delivery_status_at=delivered_at) FROM reservation WHERE reservation_id=9004")" = "DELIVERED2true" ] \
    && pass "완료 행: DELIVERED v2, 시각=delivered_at(마이크로초 보존)" || fail "완료 행: $(row 9004)"
[ "$(q "$DB" "SELECT is_nullable||'|'||column_default FROM information_schema.columns WHERE table_name='reservation' AND column_name='delivery_version'")" = "NO|0" ] \
    && pass "delivery_version: NOT NULL DEFAULT 0(실제 컬럼 정의)" || fail "delivery_version 정의"

echo "== (a) 재실행 — 새 앱이 만든 v4 보존"
# 새 앱이 9003을 실패 v2 → 재배송 v3 → 실패 v4로 진행한 상태를 만든다.
q "$DB" "UPDATE reservation SET delivery_status='DELIVERY_FAILED', delivery_version=4, delivery_status_at='2026-10-05T05:00:00.111111Z', delivery_failure_code='ADDRESS_ISSUE' WHERE reservation_id=9003"
psql -v ON_ERROR_STOP=1 -X -q -d "$DB" -f "$SQL" >/dev/null
[ "$(q "$DB" "SELECT delivery_version||delivery_failure_code||(delivery_status_at='2026-10-05T05:00:00.111111Z') FROM reservation WHERE reservation_id=9003")" = "4ADDRESS_ISSUEtrue" ] \
    && pass "재실행: v4·시각·사유 그대로" || fail "재실행 후: $(row 9003)"
[ "$(row 9004 | cut -d'|' -f1-2)" = "DELIVERED|2" ] && pass "재실행: 완료 v2 그대로" || fail "재실행 후 완료: $(row 9004)"

echo "== (a) 잘못된 신규 입력은 제약이 거부"
reject() {   # $1=설명 $2=UPDATE 본문
    if psql -X -q -d "$DB" -c "BEGIN; $2; ROLLBACK;" >/dev/null 2>&1; then fail "통과하면 안 됨: $1"; else pass "거부: $1"; fi
}
reject "음수 버전"                  "UPDATE reservation SET delivery_version=-1 WHERE reservation_id=9001"
reject "미지원 상태"                "UPDATE reservation SET delivery_status='LOST' WHERE reservation_id=9004"
reject "미지원 실패 코드"           "UPDATE reservation SET delivery_failure_code='LOST' WHERE reservation_id=9003"
reject "실패인데 코드 없음"         "UPDATE reservation SET delivery_failure_code=NULL WHERE reservation_id=9003"
reject "실패 아닌데 코드 있음"      "UPDATE reservation SET delivery_failure_code='ADDRESS_ISSUE' WHERE reservation_id=9004"
reject "미출고인데 코드 있음"       "UPDATE reservation SET delivery_failure_code='ADDRESS_ISSUE' WHERE reservation_id=9001"
reject "미출고인데 버전 있음"       "UPDATE reservation SET delivery_version=1 WHERE reservation_id=9001"
reject "상태인데 시각 없음"         "UPDATE reservation SET delivery_status_at=NULL WHERE reservation_id=9004"
reject "SHIPPED 짝수 버전"          "UPDATE reservation SET delivery_status='SHIPPED', delivery_version=2, delivery_failure_code=NULL, delivered_at=NULL WHERE reservation_id=9003"
reject "완료인데 delivered_at 없음" "UPDATE reservation SET delivered_at=NULL WHERE reservation_id=9004"
reject "완료 시각 불일치"           "UPDATE reservation SET delivered_at='2026-10-02T02:00:00.654322Z' WHERE reservation_id=9004"
reject "NULL 상태 + 시각"           "UPDATE reservation SET delivery_status_at=now() WHERE reservation_id=9002"

echo "== (b) 모순 데이터는 이전을 통째로 중단"
q "$DB_BAD" "INSERT INTO reservation (reservation_id, request_key, order_id, status, created_at) VALUES
 (9005, '00000000-0000-0000-0000-000000009005', 9005, 'SHIPPED', now());"   # 송장 없는 출고
if psql -v ON_ERROR_STOP=1 -X -q -d "$DB_BAD" -f "$SQL" >"$WORK/bad.log" 2>&1; then fail "모순 데이터인데 이전이 성공했다"; fi
grep -q "사전 검사 실패: 모순 행 1건" "$WORK/bad.log" && pass "사전 검사가 모순 1건을 보고하고 중단" || { cat "$WORK/bad.log"; fail "중단 사유"; }
q "$DB_BAD" "SELECT 1 FROM information_schema.columns WHERE table_name='reservation' AND column_name='delivery_version'" | grep -q . \
    && fail "롤백되지 않았다 — 컬럼이 남아 있다" || pass "롤백: 컬럼 추가까지 되돌려짐"

echo "== (c) 새 앱이 먼저 떴던 DB(금지된 순서)도 SQL로 복구"
start_app "$NEW_JAR" "$DB_EARLY" "$WORK/early-app.log"
stop_app
[ "$(q "$DB_EARLY" "SELECT delivery_version||coalesce(delivery_status,'-') FROM reservation WHERE reservation_id=9004")" = "0-" ] \
    && pass "먼저 뜬 새 앱: 컬럼은 생겼지만 백필은 안 됨(기동 성공 ≠ 이전 완료)" || fail "먼저 뜬 새 앱 상태"
psql -v ON_ERROR_STOP=1 -X -q -d "$DB_EARLY" -f "$SQL" >/dev/null
[ "$(q "$DB_EARLY" "SELECT delivery_status||delivery_version FROM reservation WHERE reservation_id=9004")" = "DELIVERED2" ] \
    && pass "사후 SQL: 백필·제약 적용" || fail "사후 SQL 백필"

echo "== 이전된 DB에 새 앱 기동 → 송장 조회"
start_app "$NEW_JAR" "$DB" "$WORK/new-app.log"
body=$(curl -sf -u wms:wms "http://localhost:$PORT/api/shipments/00000000-0000-0000-0000-000000009004")
echo "$body" | grep -q '"status":"DELIVERED","deliveryVersion":2,"occurredAt":"2026-10-02T02:00:00.654321Z","failureCode":null' \
    && pass "조회: 이전된 완료 행 DELIVERED v2, occurredAt=기존 deliveredAt" || fail "조회 응답: $body"
body=$(curl -sf -u wms:wms "http://localhost:$PORT/api/shipments/00000000-0000-0000-0000-000000009003")
echo "$body" | grep -q '"status":"DELIVERY_FAILED","deliveryVersion":4' && pass "조회: 재실행 보존 v4" || fail "조회 응답: $body"
stop_app

echo "모든 검사 통과"
