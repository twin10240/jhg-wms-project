# enum 컬럼 스키마 마이그레이션

> **현행 (2026-08-30 확인)** — V3.0에서 dev도 PostgreSQL로 통일했다. 아래 H2 관련 서술은
> 그 전의 이력이다. dev DB는 전환 때 새로 만들어져 **제약이 코드 enum과 일치**한다(실측).
> **prod(Railway)만 미적용**으로 남아 있고, 이는 재배포 시점 항목이다.

`ddl-auto: update`는 **기존 컬럼의 enum 허용값을 절대 갱신하지 않는다.** 그래서 enum에 값을 추가하면
새로 만든 DB에서는 되고, 이미 있던 DB에서는 INSERT가 DB 레벨에서 거부된다.

실제 사고: `InventoryTransactionType`에 `RETURN`을 추가한 뒤 RMA 검수 완료(재입고)가 500.

## DB별 발현 형태

| 시점 | dev/local | prod |
|------|-----------|------|
| ~V2.1 | H2 — 네이티브 `ENUM(...)` 값 목록 | PostgreSQL — `varchar(255)` + `check` 제약 |
| **V3.0~ (현재)** | **PostgreSQL — prod와 동일** | PostgreSQL — 동일 |

**엔진을 통일한 것이 이 문제에 준 효과**: 예전에는 dev(H2)와 prod(Postgres)가 서로 다른 방식으로
막아서, dev에서 통과한 enum 추가가 prod에서만 터졌다. 지금은 같은 엔진·같은 `check` 제약이므로
**기존 dev DB에서 먼저 걸린다.** prod 전용 사고였던 것이 개발 중에 드러나는 문제로 바뀌었다.

다만 `ddl-auto: update`가 기존 컬럼의 제약을 갱신하지 않는다는 사실 자체는 그대로다.
DB를 새로 만들면 최신 제약이 생기고, 이미 있던 DB는 낡은 제약을 유지한다 — dev도 예외가 아니다.

## 코드에서 막은 것 / 못 막은 것

모든 enum 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 붙였다(2026-08-14).

- **H2: 해결.** 네이티브 ENUM 대신 `varchar`로 생성 → 이후 값 추가에 DDL 불필요.
- **Postgres: 미해결.** Hibernate 6.6은 `@Enumerated(STRING)`에 check 제약을 계속 생성한다.
  `columnDefinition`으로도 억제되지 않고, 이를 끄는 설정도 6.6에는 없다
  (`hibernate.type.preferred_enum_jdbc_type` 없음 — 확인함).

**결론: prod는 enum 값을 추가할 때마다 아래 마이그레이션이 필요하다.**

## dev — 현재 정합 (2026-08-30 실측)

V3.0 전환 때 PostgreSQL DB를 새로 만들었으므로 제약이 코드와 일치한다.

```
inventory_adjustment.type  CHECK  OPENING RECEIVE SHIP ADJUST RETURN COUNT
InventoryTransactionType   코드   OPENING RECEIVE SHIP ADJUST RETURN COUNT   ← 일치
```

`cycle_count.status`·`rma_return.status`·`rma_return_item.disposition`·`purchase_order.status`·
`replenishment_request.status`·`reservation.status`도 모두 생성돼 있다.

**이력**: H2를 쓰던 시절에는 네이티브 ENUM 컬럼이라
`ALTER TABLE inventory_adjustment ALTER COLUMN type VARCHAR(20)`로 풀었다(34행 값 보존 확인).
`@JdbcTypeCode(SqlTypes.VARCHAR)`도 그때 붙인 것이고, PostgreSQL에서는 원래 `varchar`라
이 애노테이션이 동작을 바꾸지는 않는다.

## prod(PostgreSQL) — 미적용

Railway 서비스가 중단 상태라 지금 장애가 나고 있는 건 아니다. **재배포 체크리스트 항목**이다.

- Railway Postgres 플러그인이 **아직 살아 있으면** → 스크립트 실행 필요
- **DB까지 삭제됐으면** → 다음 배포 때 스키마가 새로 생성되므로 불필요

실행 스크립트: [`wms-enum-schema-migration.sql`](wms-enum-schema-migration.sql)

`inventory_adjustment.type`(`RETURN`)뿐 아니라 `purchase_order.status`·`replenishment_request.status`
(둘 다 2026-07-26에 `CANCELLED` 추가)도 함께 현행화한다. prod DB가 그 이전 것이면 셋 다 낡았다.
스크립트는 테이블이 없으면 건너뛰고, 이미 최신이면 같은 내용으로 재생성하므로 반복 실행해도 안전하다.

실행 방법 (로컬에 `psql`이 없을 때 — H2 Shell을 JDBC 클라이언트로 사용):

```bash
PG=~/.gradle/caches/modules-2/files-2.1/org.postgresql/postgresql/42.7.7/*/postgresql-42.7.7.jar
java -cp "/Users/jo/study/h2-2.3.232.jar:$PG" org.h2.tools.Shell \
  -driver org.postgresql.Driver \
  -url "jdbc:postgresql://<PUBLIC_HOST>:<PORT>/<DB>" \
  -user <USER> -password <PASSWORD>
```

Railway는 **public proxy 주소**여야 한다. `PGHOST`(`*.railway.internal`)는 Railway 내부에서만
닿으므로 로컬에서는 `DATABASE_PUBLIC_URL` 쪽 호스트·포트를 쓴다.

## 다음에 enum 값을 추가할 때

순서:

1. enum 상수 추가
2. **dev DB 제약 확인** — 새로 만든 DB면 자동 반영되지만, 쓰던 DB면 낡은 제약이 남는다.
   ```sql
   select pg_get_constraintdef(con.oid) from pg_constraint con
     join pg_class rel on rel.oid = con.conrelid
    where con.contype = 'c' and rel.relname = '<테이블>';
   ```
   낡았으면 스크립트의 해당 구문을 dev에도 실행한다(prod와 같은 SQL이다).
3. 스크립트(`wms-enum-schema-migration.sql`)에 신규 값 반영
4. prod에 실행 → 배포

이 절차가 반복해서 부담되면 그때 Flyway를 도입한다. 지금은 enum 추가 빈도가 낮아 수동으로 충분하다.

## 실제 적용 이력

| 날짜 | 추가한 값 | 대상 컬럼 |
|------|-----------|-----------|
| 2026-08-12 | `RETURN` | `inventory_adjustment.type` |
| 2026-08-15 | `COUNT` | `inventory_adjustment.type` |
| 2026-08-15 | (신규 테이블) | `cycle_count.status` |

`COUNT`는 이 문서가 예고한 절차를 처음으로 그대로 따른 사례다 — 상수 추가 → 스크립트 갱신 → 배포.
**prod 반영은 전부 재배포 시점에 한다**(Railway 중단 상태). dev는 V3.0 전환 때 DB를 새로 만들어
이미 최신이다.
