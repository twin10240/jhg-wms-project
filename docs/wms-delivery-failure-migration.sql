-- reservation 배송 상태 버전 도입: 배송 실패·재배송·완료 — 2026-10-19
--
-- 왜: 배송 실패와 재배송을 기록하려면 "지금 어느 배송 시도의 어느 상태인가"를 버전과 함께 저장해야 한다.
--     ReservationStatus.SHIPPED는 재차감 방지·해제 거부·반품 게이트가 쓰므로 건드리지 않고 별도 컬럼을 둔다.
--
-- 무엇: ① 배송 컬럼 4개 추가  ② 기존 행 모순 사전 검사(있으면 통째로 중단)  ③ 기존 행 백필
--       ④ CHECK 제약  ⑤ 결과 요약 출력
--
-- 왜 수동인가: ddl-auto=update는 (1) 기존 출고·완료 행의 상태를 백필하지 못하고, (2) 상태·버전·시각을
--              서로 묶는 CHECK를 만들지 못한다. **데이터가 있는 DB에는 이 SQL과 사후 검증이 끝나기 전에
--              새 코드로 앱을 띄우지 않는다** — 절차는 docs/wms-delivery-failure-migration.md.
--
-- 백필 규칙(시각을 지어내지 않는다 — 이미 있는 issued_at·delivered_at만 옮긴다):
--   미출고·해제                         → status NULL, version 0, 시각 NULL
--   SHIPPED + 송장 + 미완료            → SHIPPED   v1, 시각 = issued_at
--   SHIPPED + 송장 + delivered_at 있음 → DELIVERED v2, 시각 = delivered_at
--
-- 반복 실행: 안전하다. 백필은 version = 0인 행만 본다 — 새 앱이 만든 실패·재배송 행(v2 이상)은
--            다시 돌려도 버전·시각이 초기화되지 않는다. 제약은 지우고 같은 정의로 다시 만든다.
--
-- 실행: psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d <db> -f docs/wms-delivery-failure-migration.sql
--       트랜잭션으로 감싼다. 사전 검사나 제약이 실패하면 컬럼 추가까지 통째로 롤백된다.

BEGIN;

-- 재실행 때의 "이미 있음, 건너뜀" NOTICE는 숨긴다. 모순 행 목록(WARNING)과 오류는 그대로 보인다.
SET LOCAL client_min_messages = warning;

-- 이전 중 구/신 앱의 쓰기를 막는다(앱 정지가 우선이고 이건 보조 방어). 읽기는 막지 않는다.
LOCK TABLE reservation IN EXCLUSIVE MODE;

-- ① 컬럼. 타입은 Hibernate 매핑과 같다(enum은 VARCHAR, Instant는 timestamp(6) with time zone).
--    새 앱이 먼저 떠서 컬럼이 이미 있어도 IF NOT EXISTS로 넘어가고, 기본값·NOT NULL은 아래에서 다시 맞춘다.
ALTER TABLE reservation ADD COLUMN IF NOT EXISTS delivery_status       varchar(255);
ALTER TABLE reservation ADD COLUMN IF NOT EXISTS delivery_version      bigint NOT NULL DEFAULT 0;
ALTER TABLE reservation ADD COLUMN IF NOT EXISTS delivery_status_at    timestamp(6) with time zone;
ALTER TABLE reservation ADD COLUMN IF NOT EXISTS delivery_failure_code varchar(255);
ALTER TABLE reservation ALTER COLUMN delivery_version SET DEFAULT 0;
ALTER TABLE reservation ALTER COLUMN delivery_version SET NOT NULL;

-- ② 사전 검사 — 아직 백필되지 않은(version 0) 행 중 규칙에 맞지 않는 것.
--    임의 송장·현재 시각으로 메우지 않는다. 하나라도 있으면 목록을 보여주고 중단한다.
--    "SHIPPED인데 송장 없음"은 구 앱의 POST /api/inventory/ship 재호출(송장 보완 경로)로 먼저 정리한다.
DO $$
DECLARE
    bad record;
    bad_count int := 0;
BEGIN
    FOR bad IN
        SELECT reservation_id, request_key, status, tracking_number IS NOT NULL AS has_tracking,
               carrier_code IS NOT NULL AS has_carrier, issued_at IS NOT NULL AS has_issued,
               delivered_at IS NOT NULL AS has_delivered
        FROM reservation
        WHERE delivery_version = 0 AND (
               -- 출고됐는데 송장 세 필드 중 하나라도 없음
               (status = 'SHIPPED' AND (tracking_number IS NULL OR carrier_code IS NULL OR issued_at IS NULL))
               -- 출고 전·해제인데 송장·완료 정보가 있음
            OR (status <> 'SHIPPED' AND (tracking_number IS NOT NULL OR carrier_code IS NOT NULL
                                         OR issued_at IS NOT NULL OR delivered_at IS NOT NULL))
               -- 버전 0인데 배송 상태 값이 이미 있음(새 앱이 먼저 떠서 쓰다 만 흔적)
            OR delivery_status IS NOT NULL OR delivery_status_at IS NOT NULL OR delivery_failure_code IS NOT NULL)
        ORDER BY reservation_id
    LOOP
        bad_count := bad_count + 1;
        RAISE WARNING '모순 행: reservation_id=% request_key=% status=% 송장=% 운송사=% 발급시각=% 완료시각=%',
            bad.reservation_id, bad.request_key, bad.status, bad.has_tracking, bad.has_carrier,
            bad.has_issued, bad.has_delivered;
    END LOOP;
    IF bad_count > 0 THEN
        RAISE EXCEPTION '사전 검사 실패: 모순 행 %건. 정리 후 다시 실행하세요(아무것도 바뀌지 않았습니다).', bad_count;
    END IF;
END $$;

-- ③ 백필 — version 0인 출고 행만. 이미 버전이 있는 행은 새 앱이 관리하는 현재 상태다.
UPDATE reservation
SET delivery_status = 'SHIPPED', delivery_version = 1, delivery_status_at = issued_at
WHERE delivery_version = 0 AND status = 'SHIPPED' AND delivered_at IS NULL;

UPDATE reservation
SET delivery_status = 'DELIVERED', delivery_version = 2, delivery_status_at = delivered_at
WHERE delivery_version = 0 AND status = 'SHIPPED' AND delivered_at IS NOT NULL;

-- ④ 제약. CHECK는 결과가 NULL이면 통과하므로 NULL이 될 수 있는 비교는 COALESCE로 감싼다.
--    반복 실행을 위해 지우고 같은 정의로 다시 만든다(정의를 고쳐 재실행해도 새 정의가 걸린다).
ALTER TABLE reservation DROP CONSTRAINT IF EXISTS reservation_delivery_status_check;
ALTER TABLE reservation DROP CONSTRAINT IF EXISTS reservation_delivery_failure_code_check;
ALTER TABLE reservation DROP CONSTRAINT IF EXISTS reservation_delivery_failure_code_iff;
ALTER TABLE reservation DROP CONSTRAINT IF EXISTS reservation_delivery_state_check;
ALTER TABLE reservation DROP CONSTRAINT IF EXISTS reservation_delivered_at_check;

-- 값 집합. NULL(미출고)은 아래 상태 제약이 따로 잡는다.
ALTER TABLE reservation ADD CONSTRAINT reservation_delivery_status_check
    CHECK (delivery_status IN ('SHIPPED', 'DELIVERY_FAILED', 'DELIVERED'));
ALTER TABLE reservation ADD CONSTRAINT reservation_delivery_failure_code_check
    CHECK (delivery_failure_code IN ('RECIPIENT_UNAVAILABLE', 'ADDRESS_ISSUE'));

-- 현재 실패 코드만 둔다: 실패 상태 ⇔ 코드 있음. 미출고(status NULL)는 COALESCE로 FALSE가 돼 코드가 없어야 한다.
ALTER TABLE reservation ADD CONSTRAINT reservation_delivery_failure_code_iff
    CHECK (COALESCE(delivery_status = 'DELIVERY_FAILED', FALSE) = (delivery_failure_code IS NOT NULL));

-- 상태·버전·시각의 모양.
--   미출고: 상태·시각 없음, 버전 0.
--   배송 중: 출고 + 송장 + 시각 필수. 버전은 1부터 한 칸씩 오르며 SHIPPED와 실패가 번갈아 오므로
--            SHIPPED는 홀수(1·3·5…), 실패·완료는 짝수(2·4…)다. 음수 버전은 두 갈래 어디에도 맞지 않아 거부된다.
ALTER TABLE reservation ADD CONSTRAINT reservation_delivery_state_check
    CHECK (
        (delivery_status IS NULL AND delivery_version = 0 AND delivery_status_at IS NULL)
     OR (delivery_status IS NOT NULL AND delivery_status_at IS NOT NULL
         AND status = 'SHIPPED' AND tracking_number IS NOT NULL
         AND ((delivery_status = 'SHIPPED' AND delivery_version >= 1 AND delivery_version % 2 = 1)
           OR (delivery_status IN ('DELIVERY_FAILED', 'DELIVERED') AND delivery_version >= 2 AND delivery_version % 2 = 0)))
    );

-- 완료 ⇔ delivered_at 있음, 그리고 완료 시각 = 상태 시각(조회 계약: 마이크로초까지 같다).
ALTER TABLE reservation ADD CONSTRAINT reservation_delivered_at_check
    CHECK (COALESCE(delivery_status = 'DELIVERED', FALSE) = (delivered_at IS NOT NULL)
           AND (delivered_at IS NULL OR delivered_at = delivery_status_at));

-- ⑤ 요약 — 사후 검증의 출발점. 문서의 기대값과 대조한다.
SELECT status, delivery_status, delivery_version, count(*) AS rows
FROM reservation
GROUP BY status, delivery_status, delivery_version
ORDER BY status, delivery_status NULLS FIRST, delivery_version;

COMMIT;
