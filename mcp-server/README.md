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
| `WMS_USER` | (필수) | 서비스 계정 |
| `WMS_PASSWORD` | (필수) | 서비스 계정 비밀번호 |

**이 자격증명은 `/api/**` 전체에 유효합니다** — 읽기 전용은 이 서버가 부르는 URL
넷이 지키는 것이지 HTTP 계층이 지키는 것이 아닙니다. 값을 `.mcp.json`에 넣지 마세요.

## 실행

```bash
uv run pytest -q     # 테스트
uv run python -m wms_mcp.server       # 서버 (stdio — 보통은 Claude Code가 띄웁니다)
```

첫 실행은 `uv sync`가 Python 3.13과 패키지 약 41개를 내려받으므로 느립니다 — Claude Code가
처음 이 서버에 붙는 순간도 그만큼 걸립니다.

## 연결

저장소 루트의 `.mcp.json`이 등록을 담고 있습니다. `WMS_USER`·`WMS_PASSWORD`를
셸 환경에 두고 Claude Code를 이 저장소에서 실행하면 도구 넷이 보입니다.
