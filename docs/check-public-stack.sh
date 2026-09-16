#!/usr/bin/env bash
# 공개 스택 점검 — 2026-09-14
#
# 무엇을 위한 것인가:
#   공개 스택(compose, :8090 → Cloudflare Tunnel)을 바꾼 뒤 손으로 하던 확인을 한 번에 돌린다.
#   전부 실제로 틀렸거나 틀릴 뻔한 것들이다.
#   - 터널이 :8081(개발 DB + AI 키)을 가리킨 채 공개될 뻔했다
#   - AI 키를 시드 때 넣었다 빼는 절차가 사람 기억에 달려 있었다
#   - master는 바뀌었는데 공개 스택은 옛 이미지 그대로였다(2026-09-14, 9/10 이미지)
#   - 응답이 온다고 우리 스택이 답한 게 아니다(8080이면 OMS가 200을 준다)
#
# 읽기 전용이다. 데이터를 바꾸지 않는다(demo 로그인 세션 하나만 생긴다).
# 비밀을 출력하지 않는다 — 컨테이너 env는 비었는지만 본다.
#
# 실행: ~/study/jhg-wms-project 에서  docs/check-public-stack.sh
# 종료 코드: 실패가 하나라도 있으면 1.

set -uo pipefail

BASE="${WMS_BASE_URL:-https://wms.jhgsoft.com}"
HOST="${BASE#*://}"; HOST="${HOST%%/*}"
PORT="${NGINX_PORT:-8090}"
DEMO_USER="${DEMO_USER:-demo}"            # 공개해도 되는 계정(README)
DEMO_PASSWORD="${DEMO_PASSWORD:-demo1234}"
TUNNEL_CONFIG="${TUNNEL_CONFIG:-$HOME/.cloudflared/config.yml}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

fails=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; fails=$((fails + 1)); }
skip() { printf '  SKIP  %s\n' "$1"; }

# docker 시각(2026-09-14T14:10:09.79+09:00 / ...Z)을 epoch로 — macOS date
epoch() {
  date -j -f '%Y-%m-%dT%H:%M:%S%z' \
    "$(printf '%s' "$1" | sed -E 's/\.[0-9]+//; s/Z$/+0000/; s/([+-][0-9]{2}):([0-9]{2})$/\1\2/')" +%s
}

echo "== 컨테이너 ($REPO)"
git -C "$REPO" fetch -q origin 2>/dev/null || true
if ! docker info >/dev/null 2>&1; then
  echo "Docker API에 접근할 수 없습니다 — Docker Desktop/Colima와 권한을 확인하십시오" >&2
  exit 2
fi
# 런타임에 들어가는 경로만 본다 — 문서 커밋으로 재빌드를 요구하지 않는다.
src_at="$(git -C "$REPO" log -1 --format=%ct origin/master -- src build.gradle settings.gradle gradle Dockerfile)"
src_sha="$(git -C "$REPO" log -1 --format=%h origin/master -- src build.gradle settings.gradle gradle Dockerfile)"
for s in wms1 wms2 wms3; do
  if [ "$(docker inspect -f '{{.State.Running}}' "$s" 2>/dev/null)" != true ]; then
    fail "$s 실행 중이 아님"; continue
  fi
  pass "$s 실행 중"

  if docker exec "$s" sh -c 'test -z "${ANTHROPIC_API_KEY:-}"'; then
    pass "$s ANTHROPIC_API_KEY 없음"
  else
    fail "$s ANTHROPIC_API_KEY가 들어 있다 — 시드 override를 빼고 재생성할 것"
  fi
  # 앱이 스스로 판단한 결과. 분류 2 + 브리핑 1 = 3줄
  n="$(docker logs "$s" 2>&1 | grep -c 'ANTHROPIC_API_KEY 미설정')"
  [ "$n" -ge 3 ] && pass "$s 기동 로그 AI 꺼짐 ${n}줄" || fail "$s 기동 로그 AI 꺼짐 ${n}줄(3 기대)"

  # ponytail: 빌드 시각 비교라 "master를 빌드했는지"까지는 모른다(다른 브랜치를 늦게 빌드해도 통과).
  # 정확히 하려면 빌드 때 git SHA를 이미지 라벨로 넣는다.
  img_at="$(epoch "$(docker image inspect -f '{{.Created}}' "$(docker inspect -f '{{.Image}}' "$s")")")"
  if [ -n "$src_at" ] && [ "$img_at" -lt "$src_at" ]; then
    fail "$s 이미지가 master 런타임 변경($src_sha)보다 오래됐다 — docker compose build 후 한 대씩 재생성"
  else
    pass "$s 이미지가 master 런타임 변경($src_sha) 이후 빌드"
  fi
done

echo "== 터널"
if [ -f "$TUNNEL_CONFIG" ]; then
  target="$(grep -A1 "hostname: $HOST\$" "$TUNNEL_CONFIG" | grep -o 'service: *[^ #]*' | head -1)"
  case "$target" in
    *"localhost:$PORT") pass "$HOST → localhost:$PORT" ;;
    "") skip "${TUNNEL_CONFIG}에 $HOST 규칙 없음" ;;
    *) fail "$HOST → ${target#service: } — 공개 대상은 localhost:$PORT(nginx)여야 한다" ;;
  esac
else
  skip "$TUNNEL_CONFIG 없음"
fi

echo "== 공개 주소 ($BASE)"
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/login")"
[ "$code" = 200 ] && pass "GET /login 200" || fail "GET /login $code"

# nginx만 X-Served-By를 붙인다. 셋이 다 보여야 3대가 모두 받고 있는 것이다.
served="$(for _ in 1 2 3 4 5 6 7 8 9; do
  curl -s -o /dev/null -D - "$BASE/login" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-served-by"{print $2}'
done | sort -u | grep -c .)"
[ "$served" -eq 3 ] && pass "X-Served-By 3개 순환" || fail "X-Served-By ${served}개(3 기대) — nginx가 아니거나 인스턴스가 빠졌다"

jar="$(mktemp)"; trap 'rm -f "$jar"' EXIT
# 로그인 페이지에 _csrf input이 둘이다 — 둘 다 잡으면 토큰이 이어져 403이 난다(2026-09-14).
csrf="$(curl -s -c "$jar" "$BASE/login" | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//')"
loc="$(curl -s -o /dev/null -b "$jar" -c "$jar" -w '%{http_code} %{redirect_url}' \
  --data-urlencode "username=$DEMO_USER" --data-urlencode "password=$DEMO_PASSWORD" \
  --data-urlencode "_csrf=$csrf" "$BASE/login")"
case "$loc" in
  "302 $BASE/") pass "demo 로그인" ;;
  *) fail "demo 로그인 → $loc" ;;
esac
code="$(curl -s -o /dev/null -b "$jar" -w '%{http_code}' "$BASE/admin/returns")"
[ "$code" = 200 ] && pass "demo로 /admin/returns 200" || fail "demo로 /admin/returns $code"

echo
if [ "$fails" -eq 0 ]; then echo "공개 스택 정상"; else echo "실패 ${fails}건"; exit 1; fi
