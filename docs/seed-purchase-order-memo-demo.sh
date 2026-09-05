#!/usr/bin/env bash
# 발주 메모 데모 데이터 시드 — 2026-09-05
#
# 무엇을 위한 것인가:
#   발주 메모 자동 범주화(2단계)를 붙이려면 분류할 메모가 있어야 하는데 지금 0건이다.
#   운영에 남은 발주 1건의 메모는 사람이 쓴 것이 아니라 ReplenishmentRequestService.approve()가
#   조립한 "OMS 보충 요청 #N - ..." 문자열이다. 이 스크립트는 수동 발주 경로로 메모 30건을 만든다.
#
# ⚠ 이것은 시연 데이터다. 실제 창고에서 나온 메모가 아니다.
#   아래 메모 문구는 Claude가 지어낸 것이라 실제 창고 어휘가 아니라 모델이 상상한 어휘다.
#   이걸 그대로 평가셋으로 쓰면 모델이 자기가 쓴 말투를 채점하는 셈이라 정확도가 실제보다
#   후하게 나온다. 반품 평가셋이 "라벨은 Claude 초안, 사람이 검수 — 최종 권위는 사람"이라고
#   못 박은 것과 같은 이유로, 이 목록은 문구부터 사람이 손보고 쓰는 것이 맞다.
#   docs/wms-classification-eval.md 참고.
#
# 왜 SQL이 아니라 화면 엔드포인트를 부르는가:
#   분류 모수에 들어갈 자격이 "수동 발주로 사람이 쓴 메모"인데, SQL로 직접 넣으면
#   그 경로를 통과하지 않은 행이 만들어진다. 실제 폼 POST를 타야 나중에 경로로 걸러도 맞는다.
#   (seed-cycle-count-demo.sh와 같은 원칙)
#
# 만드는 것: 수동 발주 30건 — 메모 문구가 서로 다른 결을 갖도록 섞었다.
#   결품 임박 / 정기 보충 / 행사 대비 / 신상품 초도 / 단종 소진 / 불량 교체분 /
#   거래처 사정(최소수량·단가) / 계절 대비 / 메모가 성의 없는 것 / 범주가 애매한 것
#   범주를 미리 정해 두지 않았다 — 메모를 눈으로 읽고 나서 정하는 것이 순서다.
#
# 안 만드는 것: 입고 처리와 과거 발주일.
#   입고(리드타임)와 발주 간격은 메모 확보와 상관이 없고, 발주일을 과거로 미는 것은
#   애플리케이션을 우회하는 SQL UPDATE가 필요하다. 필요해지면 그때 따로 만든다.
#
# 전제: WMS가 떠 있고(기본 8081), MANAGER 계정으로 로그인 가능.
# 반복 실행: 발주가 계속 쌓인다(멱등이 아니다).

set -euo pipefail

BASE="${WMS_BASE_URL:-http://localhost:8081}"
MGR_USER="${WMS_MANAGER_USER:-manager}"
MGR_PASSWORD="${WMS_MANAGER_PASSWORD:-manager}"

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

# CSRF 토큰은 세션에 묶인다. 페이지를 받은 쿠키 그대로 POST해야 한다 —
# 페이지를 두 번 받으면 토큰이 갱신돼 403이 난다(seed-cycle-count-demo.sh에서 실측).
csrf_of() {
  curl -sS -b "$JAR" -c "$JAR" "$BASE$1" \
    | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//'
}

login() {
  local token; token="$(curl -sS -c "$JAR" "$BASE/login" \
    | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')"
  local code; code="$(curl -sS -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' \
    --data-urlencode "username=$MGR_USER" --data-urlencode "password=$MGR_PASSWORD" \
    --data-urlencode "_csrf=$token" "$BASE/login")"
  [ "$code" = "302" ] || { echo "로그인 실패($MGR_USER, HTTP $code)." >&2; exit 1; }
  echo "로그인: $MGR_USER"
}

# create_po <memo> <productId:qty> [productId:qty ...]
# 폼은 실패해도 302에 flash로만 알린다 — 리다이렉트를 따라가 성공 문구를 확인해야
# "만들었다"고 거짓 출력하지 않는다(seed-cycle-count-demo.sh에서 겪은 것과 같은 함정).
create_po() {
  local memo="$1"; shift
  local token; token="$(csrf_of "/admin/purchase-orders")"

  local args=(--data-urlencode "memo=$memo" --data-urlencode "_csrf=$token")
  local i=0
  for line in "$@"; do
    args+=(--data-urlencode "items[$i].productId=${line%%:*}")
    args+=(--data-urlencode "items[$i].quantity=${line##*:}")
    i=$((i + 1))
  done

  local body; body="$(curl -sS -b "$JAR" -c "$JAR" -L "${args[@]}" "$BASE/admin/purchase-orders")"
  local created; created="$(printf '%s' "$body" | grep -o '발주 생성 완료. (발주 #[0-9]*)' | head -1)"
  [ -n "$created" ] || { echo "발주 생성 실패: $memo" >&2; exit 1; }
  echo "  $created  ← $memo"
}

login
echo "발주 메모 시연 데이터 30건을 만듭니다 (실제 창고 메모가 아닙니다)."

# 결품이 임박해서 — 근거 패널의 소진 예상이 짧게 나오는 상품들
create_po "10번 결품 임박. 오늘 중 발주 필요"                     10:120
create_po "상품9 이번주 안에 바닥납니다. 급함"                     9:100
create_po "11번 소진 빨라서 추가로 넣습니다"                      11:80
create_po "17번 재고 얼마 안 남음, 넉넉히"                        17:150

# 정기 보충 — 특별한 사정 없이 주기적으로
create_po "9월 1주차 정기 보충"                                   2:40 3:40
create_po "정기 보충 (격주)"                                      4:60
create_po "월초 정기분입니다"                                     5:50 6:30
create_po "정기"                                                  13:40
create_po "주간 보충"                                             18:35

# 행사·프로모션 대비
create_po "추석 프로모션 대비 선발주. 9/20까지 입고 필요"          10:300 9:200
create_po "행사 물량입니다. 남으면 반품 협의됨"                    11:250
create_po "라이브방송 예정분 — 수량 확정되면 추가 발주"           17:180

# 신상품 초도
create_po "신상품 초도 물량. 반응 보고 재발주 결정"                19:60
create_po "신규 입점 상품 초도분"                                 20:50

# 단종·소진
create_po "단종 예정. 마지막 발주분입니다"                        14:20
create_po "거래처 단종 통보. 재고 소진까지만 운영"                 15:15

# 불량·반품 관련 보전
create_po "입고 검수에서 불량 12개 나와 교체분 요청"               3:12
create_po "반품 재입고가 늦어져서 그만큼 우선 채웁니다"            1:20

# 거래처 사정
create_po "최소발주수량 100개라 필요분보다 많이 넣습니다"          16:100
create_po "10월부터 단가 인상 예고. 인상 전 선매입"                18:200
create_po "거래처 휴무(9/28~10/2) 대비 미리 확보"                 5:80
create_po "리드타임 길어졌다고 연락옴. 앞당겨 발주"               13:60

# 계절 대비
create_po "환절기 수요 대비"                                      6:70
create_po "겨울 시즌 준비 1차"                                    12:90

# 메모가 성의 없거나 짧은 것 — 실제로 이런 게 섞인다
create_po "추가"                                                  7:30
create_po "요청분"                                                8:25
create_po "ㅇㅇ"                                                  4:10

# 범주가 애매한 것 — 분류기가 OTHER로 갈지 다른 데로 갈지 보는 케이스
create_po "창고장님 지시"                                         2:50
create_po "지난번 건과 동일하게"                                  12:40
create_po "확인 후 조정 예정"                                     20:30

echo
echo "완료. 발주 화면에서 메모를 눈으로 훑고 범주 체계를 정하세요:"
echo "  $BASE/admin/purchase-orders"
echo "문구는 실제 창고 어휘로 손보는 편이 낫습니다 — 헤더의 경고 참고."
