# WMS 반품 분석 MCP 서버

Claude Code가 WMS 반품 데이터로 보고서를 쓰게 하는 읽기 전용 MCP 서버입니다.
WMS 앱과 **별도 프로세스**로 뜨고, WMS의 `/api/analytics/**` REST를 부릅니다.

## 구조

```
Claude Code ──stdio(MCP)──> 이 서버 ──HTTP basic──> WMS ──> ReturnAnalyticsService
```

계산은 하지 않습니다. WMS가 이미 하고, 여기서 또 하면 화면과 보고서가 다른 숫자를
낼 수 있게 됩니다.

## 도구 여섯 (전부 읽기 전용)

| 도구 | 인자 |
|---|---|
| `product_return_rates` | `from_date`, `to_date` |
| `return_category_breakdown` | `from_date`, `to_date` |
| `return_details_by_product` | `product_id`, `from_date`, `to_date` |
| `return_details_by_category` | `category`, `from_date`, `to_date` |
| `cycle_count_accuracy` | `from_date`, `to_date` |
| `cycle_count_variances` | `from_date`, `to_date` |

`from`은 파이썬 예약어라 인자 이름이 `from_date`입니다. REST 파라미터 이름으로
바꾸는 일은 `client.py`가 합니다.

실사 도구 둘은 **승인된 세션만** 모수로 삼습니다. 반려된 세션은 "계수를 신뢰할 수 없다"고
사람이 판정한 것이라 정확도에서 빼고, 뺀 항목 수를 `excludedRejectedItems`로 함께 냅니다 —
조용히 빼면 분모를 속이는 것과 같습니다. `accuracy`가 `null`이면 잴 것이 없다는 뜻이고
`0`(전부 틀림)과 다릅니다.

**조회 구간은 최대 366일입니다**(`client.MAX_WINDOW_DAYS`). 넘으면 소켓을 열기 전에
`ToolError`로 거절하고 구간을 좁히라고 말합니다. 호출자가 화면에서 모델로 바뀌면서 생긴
제약입니다 — 사람은 달력을 집지만 모델은 `from=2020-01-01`을 그럴듯하게 던지고, WMS는
구간 내 원장을 거르기 전에 메모리에 올립니다. 윤년 한 해를 담는 값이라 정당한 연간 리뷰는
막지 않습니다.

**응답 행 수에는 상한이 없습니다.** 실측(2026-09-03) 전 기간 상세가 50행·653자라 아직
발동할 여지가 없어 넣지 않았습니다. 필요해지면 상세 도구의 반환을
`{"rows": [...], "truncated": bool, "total": int}`로 바꿉니다 — 잘린 사실을 담지 않고
그냥 자르면 조용한 오답이 됩니다.

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `WMS_BASE_URL` | `http://localhost:8081` | WMS 주소 |
| `WMS_USER` | `wms` (`.mcp.json`의 `${WMS_USER:-wms}`) | 서비스 계정 |
| `WMS_PASSWORD` | `wms` (`.mcp.json`의 `${WMS_PASSWORD:-wms}`) | 서비스 계정 비밀번호 |

`.mcp.json`이 로컬 기본값 `wms/wms`를 갖습니다 — `export` 없이 바로 붙습니다.
`application.yml`이 `${WMS_BASIC_USER:wms}`로 같은 값을 이미 평문으로 담고 있어
새로 노출되는 비밀이 없고, 두 파일이 같은 규칙(로컬 기본값 + 환경변수 override)을 씁니다.

**이 자격증명은 `/api/**` 전체에 유효합니다** — 읽기 전용은 이 서버가 부르는 URL
넷이 지키는 것이지 HTTP 계층이 지키는 것이 아닙니다.

**그래서 `WMS_BASE_URL`을 로컬 밖으로 돌리는 순간 이 기본값은 폐기 대상입니다.**
원격 WMS를 붙일 때는 `WMS_USER`·`WMS_PASSWORD`를 환경변수로 반드시 덮으세요 —
기본값이 있다는 것은 누락이 더 이상 오류로 드러나지 않는다는 뜻이기도 합니다.

## 실행

```bash
uv run pytest -q     # 테스트
uv run python -m wms_mcp.server       # 서버 (stdio — 보통은 Claude Code가 띄웁니다)
```

첫 실행은 `uv sync`가 Python 3.13과 패키지 약 41개를 내려받으므로 느립니다 — Claude Code가
처음 이 서버에 붙는 순간도 그만큼 걸립니다.

## 개발 DB의 유효 구간 — 보고서를 볼 때 어느 기간을 물을 것인가

**이 로컬 개발 DB에는 시연용 데이터와 개발 검증 잔재가 섞여 있다.** 구간을 잘못 잡으면
보고서가 창고 현실이 아니라 잔재를 측정한다. 소프트웨어 문제가 아니라 이 DB의 상태다.

| 출처 | 건수 | 분류 | 성격 |
|---|---|---|---|
| `request_key LIKE 'DEMO-%'` | 30 | 30/30 | **시연용 정본** — 지우지 말 것 |
| 그 외 | 19 | 5/19 | 수동 통합검증 잔재(`test`, `V2-x`, `RMA-SMOKE-*` 등) |

실측(2026-09-03):

| 조회 구간 | 총반품 | 미분류 | 정체 |
|---|---|---|---|
| 2026-08-01 ~ 08-31 | 17 | **13 (76%)** | 분모·분자 전부 잔재 |
| **2026-09-01 ~ 09-03** | **31** | **0** | DEMO 30건 — 여기가 유효 구간 |
| 2026-08-01 ~ 09-03 | 48 | 13 (27%) | 섞임 |

**보고서는 DEMO 데이터가 있는 구간으로 물을 것.** 날짜를 외우지 말고 그때그때 확인한다:

```sql
select min(requested_at)::date, max(requested_at)::date, count(*)
from rma_return where request_key like 'DEMO-%';
```

잔재를 코드로 걸러내지 않는 이유: 19건 중 12건이 진짜 고객 반품과 구분되지 않는 랜덤 UUID다.
남는 신호는 사유 문자열뿐인데, 운영 코드에 `reason not in ('test', …)`를 넣으면 진짜 고객이
사유란에 "test"라고 썼을 때 그 반품이 지표에서 조용히 사라진다. **없는 문제를 막으려고
있는 데이터를 삼키는 쪽이 더 나쁘다.**

잔재를 실제로 지우려면 재생성 비용을 먼저 보라 — `docs/oms-wms-manual-verification.md`상
재생성은 OMS를 함께 띄우고 **양쪽 DB를 `ddl-auto: create`로 초기화**하는 절차이므로
DEMO 30건도 같이 날아간다.

## 연결

저장소 루트의 `.mcp.json`이 등록을 담고 있습니다. Claude Code를 이 저장소에서 실행하면
도구 넷이 보입니다 — 로컬 기본값이 있으므로 `export`는 필요 없습니다.
다른 WMS를 붙이려면 `WMS_BASE_URL`과 함께 `WMS_USER`·`WMS_PASSWORD`를 환경변수로 덮으세요.
