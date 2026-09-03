#!/usr/bin/env bash
# 실사 데모 데이터 시드 — 2026-09-03
#
# 왜 SQL이 아니라 화면 엔드포인트를 부르는가:
#   실사 승인은 계수값을 재고에 "절대값으로 덮어쓰고" COUNT 원장에 "COUNT#{sessionId}" 참조를
#   남긴다. SQL로 직접 넣으면 그 로직을 복제해야 하고, 틀리면 보고서가 거짓말을 한다.
#   실제 흐름(open -> saveCounts+submit -> approve/reject)을 그대로 타면 재고·원장·행위자가
#   전부 일관되게 남는다.
#
#   항목 id만 DB에서 읽는다(읽기 전용). 화면 HTML을 파싱하는 것보다 안정적이고,
#   쓰기는 전부 애플리케이션을 통과하므로 로직 복제가 아니다.
#
# 만드는 것: 실사 세션 5개 / 항목 22개
#   - 대부분 일치(정확도 지표가 의미를 갖도록)
#   - 부족(장부>실물)과 과다(장부<실물) 양방향
#   - 상품 3·11이 두 세션에서 반복 차이 → "반복 차이 SKU" 지표
#   - 항목 1개짜리 세션 → "표본 얇음" 케이스
#   - 반려 세션 1개 → 재고 미반영 경로
#
# 전제: WMS가 떠 있고(기본 8081), PostgreSQL wms DB 접근 가능.
# 반복 실행: 세션이 계속 쌓인다(멱등이 아니다). 다시 만들려면 기존 실사를 지우고 돌릴 것.

set -euo pipefail

BASE="${WMS_BASE_URL:-http://localhost:8081}"
# 실사는 제출자와 승인자가 달라야 한다 — "센 사람이 스스로 장부를 고치지 못한다"는 통제라
# 같은 계정으로 제출·승인하면 approve가 거부한다(실측). 그래서 계정 둘을 쓴다.
OP_USER="${WMS_OPERATOR_USER:-operator}"
OP_PASSWORD="${WMS_OPERATOR_PASSWORD:-operator}"
MGR_USER="${WMS_MANAGER_USER:-manager}"
MGR_PASSWORD="${WMS_MANAGER_PASSWORD:-manager}"
PSQL="${PSQL:-/opt/homebrew/opt/postgresql@17/bin/psql}"
PGARGS="${PGARGS:--h 127.0.0.1 -U wms -d wms}"

OP_JAR="$(mktemp)"; MGR_JAR="$(mktemp)"
trap 'rm -f "$OP_JAR" "$MGR_JAR"' EXIT
JAR="$OP_JAR"   # 기본은 계수자. 승인·반려 직전에만 관리자로 바꾼다.

# CSRF 토큰은 세션에 묶인다. 페이지를 받은 쿠키 그대로 POST해야 한다 —
# 페이지를 두 번 받으면 토큰이 갱신돼 403이 난다(실측).
csrf_of() {
  curl -sS -b "$JAR" -c "$JAR" "$BASE$1" \
    | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//'
}

# login_as <jar> <user> <password>
login_as() {
  local jar="$1" user="$2" pass="$3"
  local token; token="$(curl -sS -c "$jar" "$BASE/login" \
    | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')"
  local code; code="$(curl -sS -b "$jar" -c "$jar" -o /dev/null -w '%{http_code}' \
    --data-urlencode "username=$user" --data-urlencode "password=$pass" \
    --data-urlencode "_csrf=$token" "$BASE/login")"
  [ "$code" = "302" ] || { echo "로그인 실패($user, HTTP $code)." >&2; exit 1; }
  echo "로그인: $user"
}

# expect_status <sessionId> <기대상태> — 화면은 실패를 flash로만 알리고 302를 낸다.
# 상태를 실제로 확인하지 않으면 "승인했다"고 거짓 출력하게 된다(실제로 그랬다).
expect_status() {
  local actual; actual="$($PSQL $PGARGS -tA -c \
    "select status from cycle_count where cycle_count_id = $1")"
  [ "$actual" = "$2" ] || {
    echo "실사 #$1 상태가 $2가 아니라 $actual 입니다. 화면의 flash 메시지를 확인하세요." >&2
    exit 1
  }
}

# open_session <memo> <productId>...  ->  세션 id를 stdout으로
open_session() {
  local memo="$1"; shift
  local token; token="$(csrf_of /admin/cycle-counts/new)"
  local args=(--data-urlencode "memo=$memo" --data-urlencode "_csrf=$token")
  local pid; for pid in "$@"; do args+=(--data-urlencode "productIds=$pid"); done
  local loc; loc="$(curl -sS -b "$JAR" -c "$JAR" -o /dev/null -w '%{redirect_url}' \
    "${args[@]}" "$BASE/admin/cycle-counts")"
  local id="${loc##*/}"
  [[ "$id" =~ ^[0-9]+$ ]] || { echo "세션 생성 실패: $loc" >&2; exit 1; }
  echo "$id"
}

# count_and_submit <sessionId> <productId:delta>...
#   delta는 장부 대비 실물의 차이다. 0이면 일치, 음수면 부족, 양수면 과다.
count_and_submit() {
  local id="$1"; shift
  # macOS 기본 bash는 3.2라 연관 배열(declare -A)이 없다. 문자열 조회로 대신한다.
  local specs=" $* "

  # 항목 id와 개시 시점 장부 수량을 DB에서 읽는다(읽기 전용).
  local rows; rows="$($PSQL $PGARGS -tA -F'|' -c \
    "select cycle_count_item_id, product_id, book_qty_at_open
       from cycle_count_item where cycle_count_id = $id order by cycle_count_item_id")"

  local token; token="$(csrf_of "/admin/cycle-counts/$id")"
  local args=(--data-urlencode "_csrf=$token" --data-urlencode "action=submit")
  local i=0 line item_id product_id book d counted
  while IFS='|' read -r item_id product_id book; do
    [ -z "$item_id" ] && continue
    # grep은 매치가 없으면 1을 낸다. 델타를 지정하지 않은 상품(= 일치 항목)이 대부분이라
    # set -e 아래에서는 여기서 스크립트가 죽는다 — || true로 끊는다.
    d="$(echo "$specs" | tr ' ' '\n' | grep "^${product_id}:" | head -1 | cut -d: -f2 || true)"
    [ -z "$d" ] && d=0
    counted=$(( book + d ))
    (( counted < 0 )) && counted=0
    args+=(--data-urlencode "items[$i].itemId=$item_id"
           --data-urlencode "items[$i].countedQty=$counted")
    i=$((i+1))
  done <<< "$rows"

  curl -sS -b "$JAR" -c "$JAR" -o /dev/null -w '' \
    "${args[@]}" "$BASE/admin/cycle-counts/$id/counts"
  expect_status "$id" SUBMITTED
  echo "  실사 #$id: 항목 ${i}개 계수·제출"
}

approve() {
  JAR="$MGR_JAR"                       # 승인은 제출자가 아닌 사람이어야 한다
  local token; token="$(csrf_of "/admin/cycle-counts/$1")"
  curl -sS -b "$JAR" -c "$JAR" -o /dev/null \
    --data-urlencode "_csrf=$token" "$BASE/admin/cycle-counts/$1/approve"
  JAR="$OP_JAR"
  expect_status "$1" APPROVED
  echo "  실사 #$1: 승인 (차이가 재고에 반영됨)"
}

reject() {
  JAR="$MGR_JAR"
  local token; token="$(csrf_of "/admin/cycle-counts/$1")"
  curl -sS -b "$JAR" -c "$JAR" -o /dev/null \
    --data-urlencode "_csrf=$token" --data-urlencode "reason=$2" \
    "$BASE/admin/cycle-counts/$1/reject"
  JAR="$OP_JAR"
  expect_status "$1" REJECTED
  echo "  실사 #$1: 반려 — $2"
}

login_as "$OP_JAR" "$OP_USER" "$OP_PASSWORD"
login_as "$MGR_JAR" "$MGR_USER" "$MGR_PASSWORD"

# ── 세션 1: 정기 실사 A — 대부분 일치, 부족 1·과다 1
S=$(open_session "정기 실사 A — 1~8번 구역" 1 2 3 4 5 6 7 8)
count_and_submit "$S" 3:-2 7:1
approve "$S"

# ── 세션 2: 정기 실사 B — 부족 1
S=$(open_session "정기 실사 B — 9~14번 구역" 9 10 11 12 13 14)
count_and_submit "$S" 11:-3
approve "$S"

# ── 세션 3: 재실사 — 상품 3·11이 또 틀린다(반복 차이 SKU)
S=$(open_session "재실사 — 직전 차이 품목 확인" 3 11 15 16)
count_and_submit "$S" 3:-1 11:-2
approve "$S"

# ── 세션 4: 항목 1개 — 얇은 표본 케이스
S=$(open_session "단품 확인 — 20번" 20)
count_and_submit "$S" 20:-1
approve "$S"

# ── 세션 5: 반려 — 재고에 반영되지 않는 경로
S=$(open_session "야간 실사 — 계수 신뢰 어려움" 17 18 19)
count_and_submit "$S" 17:-5 18:4 19:-2
reject "$S" "계수 중 물리 이동이 있었음. 재실사 필요."

echo
echo "완료. 확인:"
echo "  $PSQL $PGARGS -c \"select status, count(*) from cycle_count group by 1\""
