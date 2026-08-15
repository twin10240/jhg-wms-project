# enum 컬럼 스키마 마이그레이션 (2026-08-14)

`ddl-auto: update`는 **기존 컬럼의 enum 허용값을 절대 갱신하지 않는다.** 그래서 enum에 값을 추가하면
새로 만든 DB에서는 되고, 이미 있던 DB에서는 INSERT가 DB 레벨에서 거부된다.

실제 사고: `InventoryTransactionType`에 `RETURN`을 추가한 뒤 RMA 검수 완료(재입고)가 500.

## DB별 발현 형태

| DB | 컬럼 | 막는 장치 |
|----|------|-----------|
| H2 (dev/local) | 네이티브 `ENUM('ADJUST','OPENING','RECEIVE','SHIP')` | ENUM 값 목록 |
| PostgreSQL (prod) | `varchar(255)` | `check (type in (...))` 제약 |

## 코드에서 막은 것 / 못 막은 것

모든 enum 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 붙였다(2026-08-14).

- **H2: 해결.** 네이티브 ENUM 대신 `varchar`로 생성 → 이후 값 추가에 DDL 불필요.
- **Postgres: 미해결.** Hibernate 6.6은 `@Enumerated(STRING)`에 check 제약을 계속 생성한다.
  `columnDefinition`으로도 억제되지 않고, 이를 끄는 설정도 6.6에는 없다
  (`hibernate.type.preferred_enum_jdbc_type` 없음 — 확인함).

**결론: prod는 enum 값을 추가할 때마다 아래 마이그레이션이 필요하다.**

## dev(H2) — 적용 완료

```sql
ALTER TABLE inventory_adjustment ALTER COLUMN type VARCHAR(20);
```

기존 34행 값 보존 확인(`ADJUST 4 / OPENING 20 / RECEIVE 6 / SHIP 4`), 네이티브 ENUM 0건.

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

로드맵의 실사(`COUNT` 원장)가 바로 다음 후보다. 순서:

1. enum 상수 추가
2. prod에 위 `DROP/ADD CONSTRAINT` 실행 (신규 값 포함)
3. 배포

이 절차가 반복해서 부담되면 그때 Flyway를 도입한다. 지금은 enum 추가 빈도가 낮아 수동으로 충분하다.
