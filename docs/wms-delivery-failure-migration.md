# 배송 상태 버전 이전 — 배송 실패·재배송·완료 (2026-10-19)

`reservation`에 배송 상태 컬럼 4개를 더하고, 기존 출고·완료 행을 배송 버전으로 옮긴다.
SQL: [`wms-delivery-failure-migration.sql`](wms-delivery-failure-migration.sql) · 검사: [`check-delivery-failure-migration.sh`](check-delivery-failure-migration.sh)

## 반드시 지킬 순서

기본 `application.yml`은 `ddl-auto: update`다. **데이터가 있는 DB에 이 SQL과 사후 검증을 끝내기 전에
새 코드로 앱을 한 번이라도 띄우지 않는다.** IDE·`bootRun`·자동 기동·`docker compose up`(재생성)도 모두 해당한다.
병합은 DB 이전 승인이 아니다 — DB마다 사용자 승인을 따로 받는다.

새 앱이 먼저 떠도 기동은 성공한다(`delivery_version`의 DB 기본값 0 덕분). 그러나 그건 **백필도 제약도 없는 상태**다 —
기존 배송 중·완료 행이 모두 "미출고 v0"으로 보여 화면에서 버튼이 사라지고 조회가 틀린 상태를 준다.
그 경우에도 아래 SQL을 실행하면 복구된다(검사 (c)), 하지만 그사이 쓰인 값은 보장하지 않는다.

| DB | 앱 | 실행 담당 | 상태 |
|---|---|---|---|
| 로컬 brew PostgreSQL `wms` | `:8081` 개발 (MCP가 이 DB를 읽는다) | WMS 담당(Claude Code), 사용자 승인 후 | 대기 |
| compose PostgreSQL 컨테이너 | `:8090` 공개 스택 | WMS 담당(Claude Code), 공개 배포 승인 후 | 대기 |

테스트 DB `wms_test`는 `create-drop`이라 해당 없다.

## 절차

1. **대상 확인·쓰기 중지.** 주소·DB 이름·실행 중인 앱 버전(`git rev-parse HEAD`)을 기록하고 그 DB를 쓰는 앱을 모두 내린다.
   공개 스택은 `wms1~3` 세 대 모두다. 자동 재시작(IDE, compose `restart:`)이 다시 띄우지 않게 한다.
2. **백업.** `pg_dump -Fc -d <db> -f wms-before-delivery-<날짜>.dump` (compose는 컨테이너 안에서).
3. **사전 검사·적용.** SQL 하나가 둘을 다 한다:
   ```bash
   psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d <db> -f docs/wms-delivery-failure-migration.sql
   ```
   모순 행이 있으면 `WARNING 모순 행: …` 목록과 `사전 검사 실패` 오류로 **아무것도 바꾸지 않고** 끝난다.
   정리 방안을 기록하고 사용자 승인을 받은 뒤 처리하고 다시 실행한다:
   - **SHIPPED인데 송장 없음**: 구 앱을 띄운 상태에서 OMS의 `POST /api/inventory/ship` 재호출(또는 OMS 송장 동기화)로
     송장을 보완한다 — 재고는 다시 깎이지 않는다. 그다음 앱을 내리고 3번부터.
   - **송장·운송사·발급 시각 중 일부만 있음 / 미출고·해제인데 송장·완료 정보 있음**: 원인을 먼저 확인한다. 임의 송장·현재 시각으로 메우지 않는다.
4. **사후 검증.** 기록만 믿지 말고 실제 DB를 조회한다:
   ```sql
   -- 컬럼 정의: delivery_version은 NO | 0 이어야 한다
   SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns
   WHERE table_name = 'reservation' AND column_name LIKE 'delivery_%' ORDER BY column_name;
   -- 제약 5개
   SELECT conname FROM pg_constraint WHERE conrelid = 'reservation'::regclass AND conname LIKE 'reservation_deliver%' ORDER BY 1;
   -- 백필 결과: SHIPPED 행 수 = (SHIPPED, v1) + (DELIVERED, v2) 이고, 그 밖의 행은 (NULL, 0)
   SELECT status, delivery_status, delivery_version, count(*) FROM reservation GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;
   -- 0이어야 한다: 완료 시각과 상태 시각 불일치
   SELECT count(*) FROM reservation WHERE delivery_status = 'DELIVERED' AND delivered_at <> delivery_status_at;
   ```
   SQL 끝의 요약 표와 3번 이전 `SELECT status, count(*) … GROUP BY status`의 행 수가 맞는지 대조한다.
5. **새 앱 기동.** 4번이 모두 맞은 DB에만 새 코드를 띄운다. 확인: 출고 행의 `GET /api/shipments/{requestKey}`가
   `status`·`deliveryVersion`·`occurredAt`을 주는지, `/admin/reservations`의 배송 열 버튼, MCP(`:8081`) 보고서가 도는지.
   하나라도 실패하면 새 앱을 내리고 원인을 본다.
6. **공개 스택이면** `docs/check-public-stack.sh` 통과까지가 끝이다(AGENTS.md).

결과는 요청 문서의 교차 저장소 기록에 DB별로 남긴다: 승인일, 실행 명령, 4번 조회 결과, 새 앱 SHA, 5번 확인.

## 되돌리기

- **새 기능으로 쓰기 전**(실패·재배송 기록 전): 트랜잭션이 실패했다면 이미 롤백됐다. 커밋 뒤 되돌리려면 2번 백업을 복원하고 구 앱을 띄운다.
- **실패·재배송 기록이 생긴 뒤**: 컬럼을 지우는 롤백은 하지 않는다 — 그 기록과 OMS에 이미 나간 버전이 사라진다.
  문제는 전진 수정 SQL로 고친다.

## 규칙의 근거

- **버전 0만 백필한다.** 그래서 재실행이 안전하다 — 새 앱이 만든 v2 이상의 행은 다시 돌려도 그대로다(검사 (a) 재실행).
- **기존 완료 = v2.** 실패 없이 완료한 새 흐름(`SHIPPED v1 → DELIVERED v2`)과 같은 모양이다. 이 행을 완료 재통지하면
  기존 `delivered_at`과 v2를 그대로 보낸다. OMS는 version 0 완료 주문에 이 v2를 도입하고 알림을 다시 내지 않는다(OMS 계약).
- **제약.** 실패 상태 ⇔ 실패 코드(현재 코드만 둔다), 상태·시각·버전의 모양, 완료 ⇔ `delivered_at` = 상태 시각.
  SHIPPED와 실패가 번갈아 오므로 SHIPPED는 홀수 버전, 실패·완료는 짝수 버전이다 — 버전이 건너뛰거나 상태와 어긋난 쓰기를 DB가 막는다.
  CHECK는 NULL이면 통과하므로 NULL이 될 수 있는 비교는 `COALESCE(…, FALSE)`로 감쌌다.
- **네이티브 ENUM을 쓰지 않는다.** 기존 규칙과 같이 VARCHAR + CHECK다([`wms-enum-schema-migration.md`](wms-enum-schema-migration.md)).

## 검사 (2026-10-19 실행)

`bash docs/check-delivery-failure-migration.sh` — 검사 전용 임시 DB만 만들고 끝나면 지운다(개발·공개 DB 미사용).
구 앱(`origin/master`) jar로 실제 스키마를 만든 뒤 기존 행 네 종류(예약·해제·배송 중·완료)를 심고 다음을 본다:

- (a) 정상 이전 백필, `delivery_version` NOT NULL DEFAULT 0, 재실행 시 새 v4·시각·사유 보존, 잘못된 입력 12종 거부
- (b) 송장 없는 출고 행이 있으면 모순 1건 보고 후 중단, 컬럼 추가까지 롤백
- (c) 새 앱이 먼저 뜬 DB: 기동은 되지만 백필 안 됨 → SQL 사후 실행으로 복구
- 이전된 DB에 새 앱 기동 → 송장 조회가 이전된 완료 행을 `DELIVERED v2`·기존 `deliveredAt`으로 반환
