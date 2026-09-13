#!/usr/bin/env bash
# 반품 데모 데이터 시드 — 2026-09-13
#
# 무엇을 위한 것인가:
#   공개 스택(compose, :8090)은 빈 볼륨에서 InitDb가 재고 20개만 심는다. 반품 화면·반품률·분류가
#   전부 비어 있어 방문자가 볼 게 없다. 개발 DB의 DEMO- 반품 30건과 같은 사유 문구로 채운다.
#
# 왜 SQL이 아니라 OMS가 부르는 API를 타는가:
#   반품은 출고된(SHIPPED) 예약에만 접수되고, 반품률의 분모는 출고 원장이다. 실제 경로
#   (reserve -> ship -> returns)를 타야 재고·원장·예약·분류 트리거가 전부 일관되게 남는다.
#   (seed-cycle-count-demo.sh와 같은 원칙)
#
# ⚠ 사유 문구는 시연용이다. 실제 고객이 쓴 것이 아니다 — 평가셋으로 쓰지 말 것.
#
# 만드는 것:
#   - 반품 없는 출고 20건(상품 1~20 각 3개) — 반품률에 분모를 준다
#   - 반품 30건(출고 2개 중 1개 반품) — 파손 7 / 오배송 7 / 변심 8 / 기타·애매 8
#     파손은 상품 3·7, 오배송은 상품 10·11에 몰았다 — 반품률 상위가 눈에 띄도록.
#
# 전제: WMS가 떠 있고 /api/** Basic 계정 사용 가능. AI 키가 있으면 반품 사유가 분류된다.
# 반복 실행: 출고가 계속 쌓이고 반품 requestKey(DEMO-<orderId>)가 충돌한다. 다시 하려면 볼륨째 새로.

set -euo pipefail

BASE="${WMS_BASE_URL:-http://localhost:8081}"
AUTH="${WMS_BASIC_USER:-wms}:${WMS_BASIC_PASSWORD:-wms}"

post() {   # post <path> <json>  ->  "HTTP코드 본문"
  curl -sS -u "$AUTH" -H 'Content-Type: application/json' -w '\n%{http_code}' \
    --data "$2" "$BASE$1"
}

# ship_order <orderId> <productId> <qty>  ->  requestKey를 stdout으로
ship_order() {
  local key; key="$(uuidgen | tr 'A-Z' 'a-z')"
  local items="{\"$2\":$3}"
  local out; out="$(post /api/inventory/reserve "{\"requestKey\":\"$key\",\"orderId\":$1,\"items\":$items}")"
  [ "$(printf '%s' "$out" | head -1)" = "true" ] || { echo "예약 실패: 주문 $1 ($out)" >&2; exit 1; }
  out="$(post /api/inventory/ship "{\"requestKey\":\"$key\",\"items\":$items}")"
  [ "$(printf '%s' "$out" | tail -1)" = "200" ] || { echo "출고 실패: 주문 $1 ($out)" >&2; exit 1; }
  echo "$key"
}

ORDER=80000

# return_demo <productId> <reason>
return_demo() {
  ORDER=$((ORDER + 1))
  local key; key="$(ship_order "$ORDER" "$1" 2)"
  local out; out="$(post /api/returns "{\"requestKey\":\"DEMO-$ORDER\",\"orderId\":$ORDER,\"orderRequestKey\":\"$key\",\"reason\":\"$2\",\"items\":[{\"orderItemId\":${ORDER}1,\"productId\":$1,\"quantity\":1}]}")"
  [ "$(printf '%s' "$out" | tail -1)" = "201" ] || { echo "반품 실패: 주문 $ORDER ($out)" >&2; exit 1; }
  echo "  반품 DEMO-$ORDER  상품 $1  ← $2"
}

echo "반품 없는 출고 20건"
for p in $(seq 1 20); do
  ORDER=$((ORDER + 1))
  ship_order "$ORDER" "$p" 3 >/dev/null
done

echo "반품 30건 (시연용 문구)"
# 파손
return_demo 3  "택배 박스가 찌그러져서 안에 컵이 깨졌어요"
return_demo 7  "화면에 금이 가 있는 상태로 도착했습니다"
return_demo 3  "모서리가 다 까져서 왔네요"
return_demo 7  "뚜껑이 부러진 채로 배송됐어요"
return_demo 12 "긁힘이 있는데 쓰는 데는 문제없어요"
return_demo 3  "포장은 멀쩡한데 안에 내용물이 새어 있었어요"
return_demo 7  "받자마자 열어보니 유리가 산산조각이던데요"
# 오배송
return_demo 10 "주문한 색상이랑 다른 게 왔어요"
return_demo 11 "L 사이즈 시켰는데 S가 왔습니다"
return_demo 10 "두 개 주문했는데 하나만 들어있어요"
return_demo 11 "전혀 다른 상품이 배송됐네요"
return_demo 5  "구성품 중에 케이블이 빠져 있어요"
return_demo 14 "제품은 맞는데 사은품이 안 왔어요"
return_demo 10 "송장은 제 이름인데 물건이 제가 시킨 게 아니에요"
# 변심
return_demo 2  "생각보다 커서 안 맞네요"
return_demo 4  "사이즈 교환하려구요"
return_demo 6  "필요 없어져서 반품합니다"
return_demo 8  "포장만 뜯었어요"
return_demo 9  "몇 번 써봤는데 안 맞아서요"
return_demo 13 "색이 화면이랑 달라서 마음에 안 들어요"
return_demo 15 "한 달 정도 쓰다가 안 쓰게 됐어요"
return_demo 16 "선물했는데 상대방이 이미 갖고 있대요"
# 기타·애매 — 분류기가 OTHER로 가거나 신뢰도를 낮춰야 하는 것
return_demo 1  "배송기사님이 너무 불친절했어요"
return_demo 17 "그냥요"
return_demo 18 "음... 그냥 별로네요"
return_demo 19 "배송이 너무 늦게 와서요"
return_demo 20 "결제가 중복으로 됐어요"
return_demo 2  "ㅁㄴㅇㄹ"
return_demo 4  "반품이요"
return_demo 6  "환불 부탁드립니다"

echo
echo "완료. 확인: $BASE/admin/returns"
