# WMS Auth TODO

## Goal

Expose WMS through a Railway public URL only after adding minimal authentication.

## Status: 완료 (2026-07-13) — 운영 배포·공개 도메인 생성까지 전부 끝

공개 URL: `https://jhg-wms-project-production.up.railway.app` (Basic Auth `wms-admin`, 비밀번호는 Railway Variables).
배포 런북 검증 통과: 무자격 `/`·API 401, Basic Auth 대시보드·API 200, 무CSRF admin POST 403,
OMS 관리자 화면 경유 재고 조회·조정(+1/−1 원복) 정상 — OMS→WMS private networking 유지.

코드 작업(1~6)은 전부 구현·테스트 완료. WMS 76 tests / OMS 163 tests BUILD SUCCESSFUL, 회귀 0.
로컬 기본 자격증명 **wms/wms**(양쪽 일치), env로 override.

## Recommended path

Use HTTP Basic Auth for the whole WMS app. Skip users, roles, JWT, and form login for now; WMS is still a single-admin operational tool.

## Tasks

- [x] **1. `spring-boot-starter-security` 추가** (+ `spring-security-test` testImpl). `build.gradle`.
- [x] **2. WMS `SecurityConfig` 신설** — `com.jhg.wms.config.SecurityConfig`.
   - 정적 자원·`/error` permitAll.
   - `/`, `/admin/**`, `/api/**` 인증 요구.
   - HTTP Basic 활성화.
   - `/api/**` CSRF 예외(서버간 호출은 토큰 없음), `/admin/**` 폼은 CSRF 유지.
   - 인메모리 유저 1명, delegating(bcrypt) 인코더, 자격증명은 `wms.basic.user/password`.
- [x] **3. WMS env 변수** — `application.yml`(main/test)에 `wms.basic.user: ${WMS_BASIC_USER:wms}` / `wms.basic.password: ${WMS_BASIC_PASSWORD:wms}`.
- [x] **4. OMS에도 동일 env** — OMS `application.yml`(main/test)에 `wms.basic.*` 플레이스홀더.
- [x] **5. OMS WMS 어댑터가 Basic Auth 전송** — `WmsInventoryAdapter`·`WmsInventoryQueryAdapter`·`WmsPurchaseOrderAdapter` 생성자에서 `RestClient.Builder.defaultHeaders(setBasicAuth)`.
- [x] **6. WMS admin POST 폼에 CSRF hidden** — `templates/admin/inventory.html`(재고조정), `templates/admin/purchaseorders.html`(발주생성·입고).
- [x] **(추가) 보안 슬라이스 테스트** — `SecurityConfigTest`(무자격 API 401 / 무CSRF admin POST 403 / 인증 200) + 기존 @WebMvcTest 3종 인증 대응 수정.
- [x] **7. Railway 공개 도메인 생성** — 2026-07-13 런북 순서대로 완료 (env → OMS 재배포 → WMS 배포 → 검증 → 도메인).

## 배포 런북 (운영 — Railway 콘솔, 사용자 수행)

> ⚠️ **순서 엄수 — 안 지키면 OMS→WMS 전면 401.** 두 개의 별도 서비스라 창(window)이 생긴다.

1. **env 먼저** — OMS·WMS **두 서비스 모두** Variables에 강한 값으로 `WMS_BASIC_USER` / `WMS_BASIC_PASSWORD` 설정(로컬 wms/wms 금지).
2. **OMS 먼저 배포** — 자격증명 전송 시작. WMS는 아직 무인증이라 무해.
3. **그 다음 WMS 배포** — 인증 활성화.
4. 검증(아래) 통과 확인.
5. **마지막에 WMS 공개 도메인 생성**.

이유: WMS 인증을 먼저 켜면 OMS가 아직 자격증명을 안 보내 reserve/ship/release·발주·입고가 전부 401 하드 실패(어댑터의 5xx→백오더 흡수는 4xx/401을 삼키지 않음).

## Verification

- WMS `/` 무자격 → `401`.
- WMS `/` Basic Auth → 대시보드 `200`.
- WMS admin POST 무CSRF → `403`.
- WMS admin POST CSRF 포함 → 성공.
- OMS 메인 재고조회가 private `WMS_BASE_URL`로 여전히 동작.
- OMS 주문 reserve/ship/release, 발주 create/receive 여전히 동작.
- 공개 URL 접근 시 인증 프롬프트 후 WMS UI 로드.

## Keep

- `WMS_BASE_URL`은 private Railway URL 유지: `http://jhg-wms-project.railway.internal:8081`.
- 공개 WMS URL은 브라우저/관리자 접근 전용.

## Notes

- **Railway 헬스체크는 이슈 아님(확인됨)**: WMS `railway.json`에 `healthcheckPath` 없음 + Dockerfile HEALTHCHECK·actuator 없음 → Railway가 `/`로 HTTP 헬스체크를 하지 않아 `/` 인증이 배포를 막지 않는다.
- **범위 밖**: `/api/replenishments`(WMS→OMS 역방향 콜백, risk.md #3)는 이 작업과 무관 — OMS 쪽 private networking 의존 유지.

## Deferred

- Form login.
- User table.
- Roles.
- JWT/API tokens.
- Separate read/write credentials.

Add those only when WMS has multiple operators, external API clients, or real role separation.
