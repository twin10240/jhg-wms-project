-- prod(PostgreSQL) enum check 제약 현행화 — 2026-08-14
--
-- 왜: ddl-auto=update는 기존 컬럼의 check 제약을 갱신하지 않는다.
--     enum에 값을 추가하면 새 DB에서는 되고 기존 DB에서는 INSERT가 거부된다.
--     (dev H2에서 RETURN 추가 후 RMA 재입고가 500으로 터진 것과 같은 원인, 다른 형태)
--
-- 무엇: 아래 표의 허용값을 현재 코드 기준으로 다시 박는다.
--       테이블이 없으면 건너뛴다(rma_* 는 신규 배포 시 Hibernate가 올바르게 생성한다).
--       이미 최신이면 같은 내용으로 재생성되므로 반복 실행해도 안전하다.
--
-- 주의: 트랜잭션으로 감싼다. 실패 시 통째로 롤백된다.

BEGIN;

DO $$
DECLARE
    target record;
    con    record;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            ('inventory_adjustment',  'type',        ARRAY['OPENING','RECEIVE','SHIP','ADJUST','RETURN','COUNT']),
            ('purchase_order',        'status',      ARRAY['ORDERED','PARTIALLY_RECEIVED','RECEIVED','CANCELLED']),
            ('replenishment_request', 'status',      ARRAY['REQUESTED','APPROVED','REJECTED','FULFILLED','CANCELLED']),
            ('reservation',           'status',      ARRAY['RESERVED','SHIPPED','RELEASED']),
            ('wms_user',              'role',        ARRAY['OPERATOR','MANAGER']),
            ('rma_return',            'status',      ARRAY['REQUESTED','RECEIVED','COMPLETED','CANCELLED']),
            ('rma_return_item',       'disposition', ARRAY['RESTOCKED','DISPOSED','REJECTED'])
        ) AS t(tbl, col, vals)
    LOOP
        CONTINUE WHEN to_regclass(target.tbl) IS NULL;

        -- 해당 컬럼을 대상으로 하는 check 제약만 제거 (conkey로 정확히 매칭 — 이름 LIKE 매칭은 위험)
        FOR con IN
            SELECT c.conname
            FROM   pg_constraint c
            JOIN   pg_attribute  a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
            WHERE  c.conrelid = target.tbl::regclass
              AND  c.contype  = 'c'
              AND  a.attname  = target.col
        LOOP
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', target.tbl, con.conname);
            RAISE NOTICE 'dropped %.%', target.tbl, con.conname;
        END LOOP;

        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I CHECK (%I IN (%s))',
            target.tbl,
            target.tbl || '_' || target.col || '_check',
            target.col,
            (SELECT string_agg(quote_literal(v), ',') FROM unnest(target.vals) AS v)
        );
        RAISE NOTICE 'refreshed %.%', target.tbl, target.col;
    END LOOP;
END $$;

COMMIT;

-- 검증: 아래를 실행해 허용값에 신규 값이 들어갔는지 눈으로 확인한다.
SELECT c.conrelid::regclass AS tbl, c.conname, pg_get_constraintdef(c.oid) AS def
FROM   pg_constraint c
WHERE  c.contype = 'c'
  AND  c.conrelid::regclass::text IN
       ('inventory_adjustment','purchase_order','replenishment_request',
        'reservation','wms_user','rma_return','rma_return_item')
ORDER  BY tbl, c.conname;
