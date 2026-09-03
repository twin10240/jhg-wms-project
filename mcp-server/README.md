# WMS 반품 분석 MCP 서버

Claude Code가 WMS 반품 데이터로 보고서를 쓰게 하는 읽기 전용 MCP 서버입니다.
WMS 앱과 **별도 프로세스**로 뜨고, WMS의 `/api/analytics/**` REST를 부릅니다.

## 구조

```
Claude Code ──stdio(MCP)──> 이 서버 ──HTTP basic──> WMS ──> ReturnAnalyticsService
```

계산은 하지 않습니다. WMS가 이미 하고, 여기서 또 하면 화면과 보고서가 다른 숫자를
낼 수 있게 됩니다.

## 도구 넷 (전부 읽기 전용)

| 도구 | 인자 |
|---|---|
| `product_return_rates` | `from_date`, `to_date` |
| `return_category_breakdown` | `from_date`, `to_date` |
| `return_details_by_product` | `product_id`, `from_date`, `to_date` |
| `return_details_by_category` | `category`, `from_date`, `to_date` |

`from`은 파이썬 예약어라 인자 이름이 `from_date`입니다. REST 파라미터 이름으로
바꾸는 일은 `client.py`가 합니다.

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

## 연결

저장소 루트의 `.mcp.json`이 등록을 담고 있습니다. Claude Code를 이 저장소에서 실행하면
도구 넷이 보입니다 — 로컬 기본값이 있으므로 `export`는 필요 없습니다.
다른 WMS를 붙이려면 `WMS_BASE_URL`과 함께 `WMS_USER`·`WMS_PASSWORD`를 환경변수로 덮으세요.
