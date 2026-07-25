# 운영 완결성 + 접근제어 — 설계 스펙

작성: 2026-07-25
범위: WMS 저장소 단독. OMS는 재고 관측·보충 요청까지만 담당(변경 없음).

## 이 트랙의 위치

`docs/wms-business-roadmap.md`의 **Phase 3(재고 실사/Cycle Count)와는 별개 트랙**이다. 숫자 대신 주제로 부른다.

- 로드맵에서 **Deferred/YAGNI로 미뤄둔 "역할 분리(작업자 vs 관리자)"** 를 포트폴리오 목적으로 앞당긴다.
  (로드맵 Deferred 항목에 착수 표시를 남긴다.)
- UI 섹션은 신규 계획을 만들지 않고 기존 `docs/wms-admin-ux-followup.md`(P1~P5)를 **실행 항목으로 흡수·참조**한다.
- 재고 실사·반품·위치 등 로드맵의 신규 도메인 기능은 **다루지 않는다**.

## 목표

세 가지를 한 트랙으로 묶어 "운영 완결성 + 접근제어"를 채운다.

1. **발주 생애주기 완결** — 부분 입고가 만들어낸 중간 상태(`PARTIALLY_RECEIVED`)에서 빠져나올 출구(취소)를 준다.
2. **접근제어** — HTTP Basic 팝업 단일 유저 → 폼 로그인 + DB 유저 + 롤 기반 인가. OMS 서버간 호출(`/api/**`)은 Basic 유지.
3. **UI/UX 리스킨** — 36줄 `admin.css`를 일관된 admin 테마로. 롤 인식 화면. `wms-admin-ux-followup.md` 실행.

성공 기준: 운영자가 롤에 맞는 화면에서 `보충요청 검토 → 발주 → 부분 입고/취소 → 재고 확인`을 처리하고, OMS 연동은 그대로 동작한다.

---

## A. 발주 생애주기 완결 (취소)

### 도메인
- `PurchaseOrderStatus`에 `CANCELLED` 추가.
- `PurchaseOrder.cancel()`:
  - 허용: `ORDERED`, `PARTIALLY_RECEIVED`
  - 거부(예외): `RECEIVED`, `CANCELLED`(중복 취소)
  - `cancelledAt` 기록.
  - **이미 입고된 품목(`receivedQty`)은 손대지 않는다** — 실물 재고. 재고 역산·콜백 없음(재고 증가가 아님).
- `ReplenishmentRequestStatus`에 `CANCELLED` 추가.

### 서비스
- `PurchaseOrderService.cancel(poId)` — **한 트랜잭션**에서:
  1. `PurchaseOrder`를 `CANCELLED`로 전이
  2. 연결된 `ReplenishmentRequest`가 있으면 `CANCELLED`로 전이
- 연결 요청이 없거나 이미 종결 상태면 발주만 취소(방어).

### UI
- 발주 상세: **취소 버튼(MANAGER 전용)** + 확인 절차.
- 발주 목록: 상태 필터에 `CANCELLED` 추가. 상태 배지에 취소 색상.

### 불변식
- 취소는 `onHandQty`·원장을 바꾸지 않는다. (취소 전후 재고 동일)

---

## B. 접근제어 (핵심)

### 문제
현재 `SecurityConfig`는 **단일 SecurityFilterChain**이 `/`·`/admin/**`·`/api/**`를 전부 `httpBasic`으로 덮는다.
**OMS가 `/api/**`를 Basic으로 호출**하므로, 폼 로그인을 그대로 얹으면 OMS 연동이 깨진다.

### 해결 — 보안 체인 2분할 (OMS가 오늘 적용한 패턴과 동일)

| 체인 | securityMatcher | 인증 | 대상 | CSRF |
|------|-----------------|------|------|------|
| `@Order(1)` API | `/api/**` | `httpBasic` — 서비스 계정(`WMS_BASIC_USER`/`WMS_BASIC_PASSWORD`) | OMS 서버간 호출 | 예외 |
| `@Order(2)` Web | `/`, `/admin/**` | `formLogin` — DB 유저 | 사람(운영자·관리자) | 활성 |

- **API 체인은 인증 실패 시 401을 직접 응답**한다(폼 로그인 리다이렉트로 새지 않게). 오늘 OMS에서 겪은 `/error` 재디스패치 → 302 버그를 처음부터 차단.
- 정적 리소스(`/css/**` 등)와 `/error`, `/login`은 permitAll.

### DB 유저
- `WmsUser` 엔티티: `username`(unique), `password`(bcrypt 해시), `role`(enum: `OPERATOR`, `MANAGER`), 생성시각.
- `WmsUserRepository`.
- `UserDetailsService` 구현체 — DB에서 유저 로드.
- **시드**: 기동 시 `operator`/`manager` 계정 2개를 시드(없을 때만). 자격증명은 설정에서 주입, 저장은 bcrypt 해시.
  - 로컬 기본값 제공(예: `operator`/`operator`, `manager`/`manager`).
  - **prod 프로파일에서 시드 자격증명이 공백이면 기동 실패**(오늘 콜백 인증과 동일한 fail-fast 원칙).
- **유저 관리 CRUD 화면은 범위 밖**(YAGNI). 유저 추가는 시드/DB로.

### 롤 인가 경계
| 액션 | OPERATOR | MANAGER |
|------|:--------:|:-------:|
| 전체 조회(대시보드·재고·예약·발주·보충요청) | ✅ | ✅ |
| 재고 조정(`/admin/inventory/adjust`) | ✅ | ✅ |
| 발주 입고(receive) | ✅ | ✅ |
| 발주 생성 | ❌ | ✅ |
| 발주 취소 | ❌ | ✅ |
| 보충요청 승인·거절 | ❌ | ✅ |

- **서버가 최종 권위**: `.requestMatchers(...).hasRole("MANAGER")` + 필요한 곳에 메서드/컨트롤러 레벨 확인.
- **UI는 보조**: 롤에 없는 액션 버튼·링크를 숨긴다(숨김은 편의일 뿐, 서버가 막는다).

### 로그인 페이지
- 새 `templates/login.html`. Spring Security formLogin과 배선(`/login` GET/POST, CSRF 토큰).
- C의 admin 테마로 스타일.

---

## C. UI/UX 리스킨

`docs/wms-admin-ux-followup.md`의 P1~P5를 실행 항목으로 삼는다. 신규 도메인 기능·프런트 프레임워크 없음, 서버 렌더링 Thymeleaf 유지.

### 이번 트랙에서 반드시 하는 것
- `admin.css`(36줄) → 일관된 admin 테마: nav·테이블·폼·버튼·flash·**상태 배지**(ORDERED/PARTIALLY_RECEIVED/RECEIVED/CANCELLED 색상+텍스트 병행).
- **롤 인식 nav/액션**: 롤에 없는 링크·버튼 숨김(B의 롤에 연동).
- 로그인 페이지 동일 테마.
- 상태·업무 문구 한글화(ux-followup P2): enum 원문을 화면에 직접 노출하지 않음. 상태는 색상만이 아니라 텍스트 병행.
- 접근성 기본(ux-followup P5): 버튼 최소 높이, label 연결, flash `role="alert"`.

### 여력 되면(선택, 우선순위 낮음)
- 대시보드 처리 대기 카드(ux-followup P1), 재고 가용수량 오름차순 정렬.
- 재고 조정 전 예상 수량 표시·음수 조정 사유 필수(ux-followup P3).
- 720px 이하 반응형(ux-followup P5).

> 선택 항목은 "여력 되면"으로 두고, 필수 항목(테마·롤 인식·한글화·접근성 기본)을 완료 기준으로 삼는다.

---

## 구현 순서 (의존성 기반)

1. **B 백엔드** — 보안 체인 2분할 + `WmsUser`/시드 + `UserDetailsService` + 롤 인가. (토대: A의 취소 버튼·C의 롤 nav가 롤에 의존)
2. **A** — 발주 취소 도메인·서비스·상태, 최소 UI(MANAGER 게이트).
3. **C** — 전 템플릿 리스킨 + 로그인 페이지 + 롤 인식 nav + 한글화. (모든 화면·액션이 존재한 뒤 마지막에 스킨)

님의 원래 우선순위(취소→UI→접근제어)와 순서가 다른 이유: UI의 롤 인식과 취소 버튼 노출이 롤 존재에 의존하므로 접근제어(B)가 먼저다.

---

## 테스트 전략

### 도메인/서비스 단위
- 발주 취소: `ORDERED`에서 성공, `PARTIALLY_RECEIVED`에서 성공, `RECEIVED`/중복 취소 거부.
- 취소 시 연결 보충 요청 `CANCELLED` 전이. 연결 없을 때 발주만 취소.
- 취소 전후 `onHandQty`·원장 불변.

### 보안 — 실서블릿 레벨 (`@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate`)
- 미인증 웹 경로(`/admin/**`) → 로그인 리다이렉트(302 `/login`).
- 미인증/오인증 `/api/**` → **401 유지**(302 아님). ← 오늘 배운 교훈: MockMvc는 `/error` 재디스패치를 재현 못 하므로 이 경로는 반드시 실서블릿으로.
- 폼 로그인 성공/실패.

### MockMvc 슬라이스
- 롤 인가: OPERATOR가 MANAGER 전용 액션(발주 생성·취소·승인) 호출 시 403.
- MANAGER는 허용.

### 회귀
- 기존 132개 테스트가 보안 체인 분할 후에도 통과(특히 `/api/**` 호출 테스트의 Basic 인증 경로).

---

## 범위 밖 (명시적 제외)

- 유저 관리 CRUD 화면 (유저는 시드/DB로 추가).
- JWT/OAuth, 세션 외 인증, 비밀번호 재설정·이메일.
- 재고 실사(Cycle Count)·반품(RMA)·위치(Location) 등 로드맵 신규 도메인.
- 새 프런트엔드 프레임워크, 실시간 WebSocket.

## 완료 기준

- [ ] 발주를 `ORDERED`·`PARTIALLY_RECEIVED`에서 취소할 수 있고, 연결 요청이 `CANCELLED`로 종결된다. 재고는 불변.
- [ ] `/admin/**`은 폼 로그인, `/api/**`는 Basic으로 분리되고 OMS 연동(기존 테스트)이 깨지지 않는다.
- [ ] OPERATOR/MANAGER 롤이 DB에서 로드되고, 롤 밖 액션은 서버가 403으로 막는다.
- [ ] admin 화면이 일관된 테마 + 롤 인식 nav + 한글 상태 표기로 리스킨된다.
- [ ] 실서블릿 보안 테스트로 `/api/**` 401 유지가 검증된다.
