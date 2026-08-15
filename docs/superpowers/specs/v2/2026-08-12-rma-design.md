# RMA (반품) — WMS V2 스펙

## 상태 흐름

```
REQUESTED → RECEIVED → COMPLETED
     └───────────────→ CANCELLED
```

- REQUESTED → RECEIVED: 입고 처리 (상태 전환만, 수량 변경 없음)
- RECEIVED → COMPLETED: 검수 완료 (품목별 승인 수량 + 처분 입력)
- REQUESTED → CANCELLED: 미입고 취소

## 품목별 처분

| 처분 | 의미 | 재고 영향 |
|------|------|-----------|
| RESTOCKED | 재입고 | applyDelta(+acceptedQuantity, RETURN) |
| DISPOSED | 폐기 | 없음 |
| REJECTED | 거절 | 없음 |

### 처분 규칙

- acceptedQuantity == 0 → disposition 반드시 REJECTED
- acceptedQuantity > 0 → disposition은 RESTOCKED 또는 DISPOSED
- COMPLETED 품목은 disposition 필수
- disposition null은 검수 전(REQUESTED/RECEIVED) 또는 CANCELLED만 허용
- V2에서는 한 품목(orderItemId)에 하나의 처분만 지정 (혼합 처분 제외)

## 검증 기준

- **식별 기준**: orderId + productId (검증·집계), orderItemId (저장·반환)
- **동일 productId 여러 orderItemId**: productId별 합산으로 검증, orderItemId별 독립 검수

### 접수 시 검증

1. Reservation 존재 + SHIPPED 상태
2. 요청 수량 1 이상
3. 상품이 출고 내역에 포함
4. 누적 반품량 ≤ 출고 수량

### 누적 반품량 계산

```
CANCELLED → 제외
COMPLETED → acceptedQuantity 합산
REQUESTED, RECEIVED → requestedQuantity 합산
```

거절된 수량은 다시 신청 가능.

### 동시성

Reservation FOR UPDATE로 동일 orderId RMA 접수를 직렬화.
RMA를 생성하는 모든 코드 경로가 반드시 Reservation을 먼저 잠가야 함.

## API

### POST /api/returns (접수)

```json
{
  "requestKey": "UUID",
  "orderId": 100,
  "reason": "상품 불량",
  "items": [
    { "orderItemId": 501, "productId": 1, "quantity": 1 }
  ]
}
```

- 신규: 201
- 같은 requestKey + 같은 내용: 200 (기존 rmaId)
- 같은 requestKey + 다른 내용: 409
- 검증 실패: 400/409

### GET /api/returns/{rmaId} (단건 조회)

```json
{
  "rmaId": 30,
  "requestKey": "UUID",
  "orderId": 100,
  "status": "COMPLETED",
  "items": [
    {
      "orderItemId": 501,
      "productId": 1,
      "requestedQuantity": 2,
      "acceptedQuantity": 1,
      "disposition": "RESTOCKED"
    }
  ]
}
```

## WMS → OMS 콜백

POST /api/return-status-events (best-effort, 커밋 후)

- 통지 대상: COMPLETED, CANCELLED
- 인증: 기존 oms.callback.user/password 재사용
- 실패해도 RMA 완료/재고 트랜잭션 롤백하지 않음
- OMS가 GET /api/returns/{rmaId}로 유실 결과 회수

## 재고 처리

RESTOCKED acceptedQuantity를 productId별 합산 후 applyDelta(+qty, RETURN, "RMA#id").
기존 재고 증가 경로를 그대로 사용 → OMS 백오더 승격 통지 자동 발생.

## 검수 규칙

- 0 ≤ acceptedQuantity ≤ requestedQuantity
- 승인되지 않은 나머지 수량은 암묵적 거절
- 전 품목 일괄 완료 (품목별 중간 완료 없음)

## 비범위

배송 상태 추적, 고객용 화면, 환불 금액 계산, 교환, 반품 배송비, 택배사 회수 연동, Kafka/outbox, 동일 품목 내 혼합 처분, since 기반 목록 조회, 품목별 거절/처분 사유, 검수 메모.
