-- reservation 연동 키 전환: order_id → request_key — 2026-09-03
--
-- 왜: order_id는 OMS DB의 시퀀스라 전역 유일하지 않다. OMS DB를 초기화하면 1부터 다시 발급되고,
--     그때 WMS에 남은 옛 예약과 신규 주문이 같은 번호를 갖는다. 품목·수량까지 같으면 옛 예약이
--     신규 주문의 것으로 조용히 반환돼, 재고가 잡히지 않은 채 OMS만 성공으로 믿고
--     출고는 옛 주문의 송장번호를 돌려줬다. 키를 OMS가 주문마다 새로 만드는 UUID로 옮긴다.
--
-- 무엇: ① order_id의 UNIQUE 제약 제거  ② request_key(uuid) 추가 + 기존 행 백필 + NOT NULL + UNIQUE
--
-- 왜 수동인가: ddl-auto=update는 (1) 기존 UNIQUE 제약을 제거하지 못하고,
--              (2) 행이 있는 테이블에 NOT NULL 컬럼을 추가하지 못한다. 둘 다 여기서 처리한다.
--
-- 백필 값에 대하여: 기존 행에는 임의 UUID를 넣는다. 이 예약들은 OMS의 이전 세대에 속하므로
--                  OMS가 다시 주소를 잡을 수 없는 것이 정상이다(그게 이 변경의 요지다).
--                  수불대장·관리자 화면의 과거 기록은 order_id로 그대로 읽힌다.
--
-- 반복 실행: 안전하다. 이미 적용된 DB에서는 각 단계가 조건부로 건너뛴다.
--
-- 주의: 트랜잭션으로 감싼다. 실패 시 통째로 롤백된다.

BEGIN;

-- ① order_id UNIQUE 제약 제거.
--    제약 이름은 Hibernate가 생성해 환경마다 다르므로(dev 실측: uk5am62t7is2k3xi9i2g6buqe46)
--    이름이 아니라 "reservation(order_id) 단일 컬럼 UNIQUE"라는 모양으로 찾는다.
DO $$
DECLARE
    target_name text;
BEGIN
    SELECT con.conname INTO target_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'reservation'
      AND con.contype = 'u'
      AND con.conkey = ARRAY[(SELECT attnum FROM pg_attribute
                              WHERE attrelid = rel.oid AND attname = 'order_id' AND NOT attisdropped)];

    IF target_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE reservation DROP CONSTRAINT %I', target_name);
        RAISE NOTICE 'order_id UNIQUE 제약 제거: %', target_name;
    ELSE
        RAISE NOTICE 'order_id UNIQUE 제약 없음 — 건너뜀';
    END IF;
END $$;

-- ② request_key 추가 → 백필 → NOT NULL → UNIQUE
ALTER TABLE reservation ADD COLUMN IF NOT EXISTS request_key uuid;

-- gen_random_uuid()는 PostgreSQL 13+ 내장이다(pgcrypto 확장 불필요).
UPDATE reservation SET request_key = gen_random_uuid() WHERE request_key IS NULL;

ALTER TABLE reservation ALTER COLUMN request_key SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'reservation' AND con.contype = 'u'
          AND con.conkey = ARRAY[(SELECT attnum FROM pg_attribute
                                  WHERE attrelid = rel.oid AND attname = 'request_key' AND NOT attisdropped)]
    ) THEN
        ALTER TABLE reservation ADD CONSTRAINT uk_reservation_request_key UNIQUE (request_key);
        RAISE NOTICE 'request_key UNIQUE 제약 생성';
    END IF;
END $$;

-- ③ order_id는 남지만 키가 아니다. NOT NULL만 유지한다(표시·수불대장 참조용).
ALTER TABLE reservation ALTER COLUMN order_id SET NOT NULL;

COMMIT;
