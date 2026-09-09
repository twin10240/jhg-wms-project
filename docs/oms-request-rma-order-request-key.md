# [OMS 작업 요청] 반품 접수에 주문의 `requestKey`를 실어 주세요

**요청일** 2026-09-09 · **WMS 쪽 상태** 구현·테스트 완료(Java 511건 통과), `feat/wms-rma-order-request-key` 브랜치
**성격** OMS↔WMS 계약 확장 · **호환성 안 깨짐** — 선택 항목이라 지금 그대로 두어도 동작합니다

---

## 1. 한 줄 요약

`POST /api/returns` 본문에 **`orderRequestKey`** 한 칸을 더 채워 주세요.
값은 그 반품이 가리키는 **주문의 `requestKey`** 입니다 — 2026-09-03에 이미 만들어
예약·출고·해제·송장조회에 싣고 있는 그 UUID이고, 새로 발급할 것이 없습니다.

```jsonc
POST /api/returns
{
  "requestKey": "…",          // 반품 자신의 멱등 키 (지금도 보내는 값, 그대로)
  "orderId": 100,             // 지금도 보내는 값, 그대로
  "orderRequestKey": "…",     // ← 추가: 이 반품이 가리키는 '주문'의 requestKey
  "reason": "…",
  "items": [ … ]
}
```

**두 키를 헷갈리면 안 됩니다.** `requestKey`는 반품의 키, `orderRequestKey`는 주문의 키입니다.

## 2. 왜 필요한가 — 예약·출고를 옮긴 것과 같은 이유

`orderId`는 유일하지 않습니다. OMS DB를 초기화하면 시퀀스가 1부터 다시 나가고, WMS에는 같은
번호의 옛 예약이 남습니다. 지금 WMS의 반품 접수는 이렇게 대상을 고릅니다:

```java
// orderId로 찾아 "가장 최근" 예약을 쓴다 — 추측이다
reservationRepository.findByOrderIdLatestFirstWithLock(orderId).stream().findFirst()
```

같은 `orderId`에 예약이 둘이면 이 추측이 두 방향으로 틀립니다.

1. **옛 주문의 반품이 새 주문에 붙습니다.** 출고량 검증·누적 반품량 검증이 남의 주문 기준으로
   돌아갑니다.
2. **실제로 출고된 상품을 거절합니다.** 옛 주문에서 산 상품을 반품하면 최신 예약에는 그 상품이
   없으므로 `400 "출고 내역에 없는 상품입니다"` 가 납니다. 고객은 분명히 받은 물건인데요.

2026-09-03에 예약·출고·해제·송장조회를 `requestKey`로 옮긴 것이 정확히 이 문제였습니다
(`docs/oms-request-reservation-request-key.md`). **반품만 남아 있습니다.**

## 3. WMS는 이미 받을 준비가 됐습니다

- `orderRequestKey`가 오면 예약을 **단건 조회**합니다(추측 없음).
- 없으면 기존 `orderId` 경로 그대로입니다 — **그래서 지금 배포해도 OMS는 안 깨집니다.**
- 형식이 UUID가 아니면 `400`(안쪽에서 터져 500이 되지 않도록 경계에서 막습니다).
- `orderRequestKey`로 찾은 예약의 `orderId`가 본문의 `orderId`와 **다르면 `400`** 입니다.
  둘 다 OMS가 같은 요청에 실어 보낸 값이라, 어긋나면 보내는 쪽이 헷갈린 것으로 보고
  한쪽을 임의로 믿지 않습니다.

응답 코드·멱등 규칙은 하나도 바뀌지 않았습니다(201 / 200 / 409 / 400 / 404 그대로).

## 4. OMS가 할 일

1. 반품 접수 요청을 만들 때 주문 엔티티의 `requestKey`를 `orderRequestKey`로 실어 보냅니다.
2. 그게 전부입니다. 스키마 변경도, 새 UUID 발급도 없습니다.

`requestKey`가 없는 **옛 주문**(2026-09-03 이전, dev 백필 334행 밖)은 값을 비워 보내면 됩니다 —
그 요청은 지금과 똑같이 레거시 경로를 탑니다.

## 5. 그 다음 (WMS 몫)

OMS가 모든 반품 요청에 키를 싣는 것이 확인되면 WMS가 정리합니다.

- `RmaService.findReservation()`의 레거시 분기와 `ReservationRepository.findByOrderIdLatestFirstWithLock()` 삭제
- `RmaReturn`에 주문 `requestKey`를 저장하고, **누적 반품량 집계도 그 키 기준으로** 전환
  (지금은 `orderId`로 셉니다. 재사용된 `orderId`에서는 과다 집계될 수 있는데, 과다 집계는
  접수를 *거절*하는 방향이라 조용한 오답이 아니어서 이번에는 손대지 않았습니다.)

## 6. 확인 방법

같은 `orderId`로 예약을 둘 만들어(상품을 다르게) 둘 다 출고한 뒤, **옛 예약의** 상품을 반품해
보세요. `orderRequestKey` 없이는 `400 "출고 내역에 없는 상품입니다"`, 실으면 `201`입니다.
WMS 쪽 재현 테스트는 `RmaServiceTest.주문_requestKey를_실으면_그_예약을_단건으로_찾는다`입니다.
