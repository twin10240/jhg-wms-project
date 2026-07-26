# WMS 인증 작업 이력

최종 현행화: 2026-07-26

이 파일은 2026-07-13의 전 경로 HTTP Basic 도입 작업을 기록하던 TODO였다.
현재 구조는 2026-07-25 운영 완결성 작업으로 대체됐다.

## 현재 인증 구조

| 경로 | 인증 | 사용자 |
|---|---|---|
| `/api/**` | HTTP Basic 서비스 계정 | OMS |
| `/`, `/admin/**` | DB 사용자 폼 로그인 | OPERATOR, MANAGER |

- API 미인증은 로그인 리다이렉트가 아닌 `401`을 반환한다.
- 관리자 폼은 CSRF를 사용한다.
- OPERATOR는 조회·재고조정·입고를 수행한다.
- MANAGER는 발주 생성·취소와 보충 요청 승인·반려까지 수행한다.
- 운영 프로파일은 서비스·운영자·관리자 자격증명에 기본값이 없어 누락 시 기동 실패한다.

## 완료 근거

- 설계: [`docs/superpowers/specs/2026-07-25-operational-completeness-access-control-design.md`](docs/superpowers/specs/2026-07-25-operational-completeness-access-control-design.md)
- 계획: [`docs/superpowers/plans/2026-07-25-operational-completeness-access-control.md`](docs/superpowers/plans/2026-07-25-operational-completeness-access-control.md)
- 수동 검증: [OMS 기준본](https://github.com/twin10240/jhg-commerce-project/blob/master/docs/manual-verification-scenarios.md)

## 배포 상태

Railway 설정과 과거 공개 도메인 검증 기록은 Git 이력에 남아 있지만 현재 서비스는 중단 상태다.
재배포 시 `WMS_BASIC_*`, `OMS_CALLBACK_*`, `WMS_OPERATOR_*`, `WMS_MANAGER_*`를 양쪽 서비스에
맞춰 주입하고 private networking 통신을 다시 스모크 테스트한다.
