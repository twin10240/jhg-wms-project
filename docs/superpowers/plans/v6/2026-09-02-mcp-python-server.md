# Python MCP 서버 (V6.0b) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `mcp-server/`에 별도 프로세스로 뜨는 Python MCP 서버를 만든다. 읽기 전용 도구 넷이 WMS의 `/api/analytics/**` REST(V6.0a, master에 병합됨)를 부르고, Claude Code가 stdio로 붙는다.

**Architecture:** 두 모듈이다. `client.py`가 HTTP 호출과 **오류 번역**을 맡고(MCP를 모른다 — 그래서 MCP 없이 테스트된다), `server.py`가 도구 넷을 선언하고 클라이언트에 위임한다. 계산은 하지 않는다 — WMS가 이미 다 한다. 이 서버는 MCP 클라이언트 쪽으로는 서버이고 WMS 쪽으로는 HTTP 클라이언트인 번역 계층이다.

**Tech Stack:** Python 3.13 (uv가 설치·고정), `mcp` 2.1.1, `httpx` 0.28.1, `pytest` + `pytest-asyncio`. uv가 가상환경·잠금파일을 관리한다.

## Global Constraints

- **WMS Java 코드를 건드리지 않는다.** `mcp-server/`, `.github/workflows/ci.yml`(잡 추가), `.mcp.json`, 루트 `README.md`만 손댄다.
- **`mcp-server/`는 Gradle 빌드 밖이다.** `settings.gradle`에 추가하지 않는다.
- **도구 넷, 전부 읽기 전용.** `client.py`에 GET 말고 다른 HTTP 메서드를 두지 않는다. DB 드라이버를 의존성에 넣지 않는다 — 금지를 코드로 강제한다.
- **계산하지 않는다.** 응답을 그대로 돌려준다. 여기서 집계하면 코호트 정의가 둘이 된다.
- **예상한 실패는 반드시 `ToolError`로 던진다.** 실측 확인: 다른 예외를 던지면 모델은 `Error executing tool <name>`만 보고 원인을 모른다.
- **자격증명은 환경변수.** `.mcp.json`은 커밋되므로 값을 넣지 않는다.
- 커밋 메시지는 한글, `feat(mcp):`/`docs(mcp):` 형식, 본문에 "왜 이 선택인가", 트레일러 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- 브랜치: `feat/wms-mcp-python` (base: master `e61feae`). **WMS 테스트 428건은 이 작업으로 바뀌지 않는다** — 바뀌면 뭔가 잘못한 것이다.

---

## 착수 전 실측 (이 기계에서 직접 확인함 — 다시 조사하지 말 것)

**① 파이썬이 없다.** 시스템 파이썬은 3.9.6이고 `mcp`는 3.10+를 요구한다. brew에 python 설치본이 없다.
→ `uv`(0.12.9, brew로 설치 완료)가 3.13을 직접 받아 쓴다.

**② `mcp` 2.x는 `FastMCP`가 아니다.** 2.1.1에서 `MCPServer`로 이름이 바뀌었다.

```python
from mcp.server.mcpserver import MCPServer            # FastMCP 아님
from mcp.server.mcpserver.exceptions import ToolError
```

`mcp.server.fastmcp`를 import하면 마이그레이션 안내와 함께 `ModuleNotFoundError`가 난다.

**③ `ToolError`만 모델에게 닿는다.** 실측 출력:

```
ToolError("from_date는 YYYY-MM-DD 형식이어야 합니다: '2026-8-1'")
  → ToolError: Error executing tool anticipated: from_date는 YYYY-MM-DD 형식이어야 합니다: '2026-8-1'
ValueError("이 메시지는 모델에게 안 간다")
  → UnexpectedToolError: Error executing tool crash          ← 원문이 사라진다
```

**이 설계의 "모델이 스스로 고친다"가 여기 달려 있다.** 예상한 실패는 전부 `ToolError`다.

**④ 도구 이름 집합을 고정하는 방법.**

```python
tools = await mcp.list_tools()          # 코루틴이다
sorted(t.name for t in tools)           # ['product_return_rates', ...]
t.input_schema                          # 2.x는 snake_case. inputSchema 아님
```

**⑤ `from`은 파이썬 예약어다.** 도구 인자 이름을 `from_date`·`to_date`로 둔다. 스키마에도 그 이름으로 나가고(실측), REST 호출 시 `from`·`to`로 바꿔 보낸다. 번역은 `client.py`가 한다.

**⑥ 동기 함수로 등록된다.** async가 강제되지 않는다(실측). httpx 동기 클라이언트를 쓴다 — 도구 넷이 읽기 전용이고 stdio 단일 클라이언트라 동시성이 필요 없다.

**⑦ `mcp.run(transport='stdio')`** — 실측한 시그니처에서 `transport` 기본값이 이미 `'stdio'`다.

**⑧ `call_tool`의 성공 결과 모양.** `CallToolResult`이고 `.is_error`, `.content`를 갖는다.
`.content[i].text`가 JSON 문자열이다 — **리스트를 돌려주면 원소마다 content 항목이 하나씩 생긴다.**
실측: `[{"rmaReturnId": 1, "category": None}]` → `content[0].text`가
`{"rmaReturnId": 1, "category": null}`. **null이 보존된다**(키가 빠지지 않는다).

## WMS REST 계약 (V6.0a, 실기동 curl로 확인함 — 추측 아님)

인증 basic `wms:wms`. 베이스는 `http://localhost:8081`(IDE 기동 포트).

| 도구 | WMS 경로 |
|---|---|
| `product_return_rates` | `GET /api/analytics/product-return-rates?from&to` |
| `return_category_breakdown` | `GET /api/analytics/return-categories?from&to` |
| `return_details_by_product` | `GET /api/analytics/return-details/product/{productId}?from&to` |
| `return_details_by_category` | `GET /api/analytics/return-details/category/{category}?from&to` |

- 날짜는 ISO(`2026-08-01`), `from`·`to` **필수**.
- `{category}`는 `DAMAGED`·`WRONG_ITEM`·`CHANGED_MIND`·`OTHER`·`UNCLASSIFIED`.
- **오류는 400 + `text/plain`.** 실측한 본문:
  - `필수 파라미터 'from'가 없습니다.`
  - `파라미터 'from'의 형식이 올바르지 않습니다. YYYY-MM-DD 날짜 형식이어야 합니다.`
  - `시작일이 종료일보다 뒤입니다.`
  - `알 수 없는 category 'NOPE'. 다음 중 하나여야 합니다: DAMAGED, WRONG_ITEM, CHANGED_MIND, OTHER, UNCLASSIFIED`
- 인증 실패는 **401**, 빈 결과는 **200 + `[]`**.
- **null은 키가 빠지는 게 아니라 `"category": null`로 온다.** 그대로 통과시킨다.
- 응답 실측 예(`return-categories`): `{"counts":[{"category":"DAMAGED","ownerArea":"PACKAGING","count":8},...],"unclassified":13,"totalReturns":47}`

---

## File Structure

| 파일 | 책임 |
|---|---|
| `mcp-server/pyproject.toml` | **생성.** 의존성 `mcp`·`httpx`, 개발 의존성 `pytest`·`pytest-asyncio` |
| `mcp-server/wms_mcp/__init__.py` | **생성.** 빈 파일 |
| `mcp-server/wms_mcp/client.py` | **생성.** WMS REST 호출 + 날짜 검증 + 오류 번역. MCP를 import하지 않는다 |
| `mcp-server/wms_mcp/server.py` | **생성.** 도구 넷 선언 + `main()`. 위임만 한다 |
| `mcp-server/tests/test_client.py` | **생성.** httpx `MockTransport`로 오류 번역을 검증 |
| `mcp-server/tests/test_tools.py` | **생성.** 도구 이름 집합 고정 + 스키마 + 위임 |
| `mcp-server/README.md` | **생성.** 기동·연결·환경변수 |
| `.github/workflows/ci.yml` | **수정.** `mcp-server` 잡 추가 |
| `.mcp.json` | **생성.** Claude Code 등록. 자격증명 값은 넣지 않는다 |
| `README.md` | **수정.** 기존 V6.0 분석 절에 한 문단 추가 |

`client.py`와 `server.py`를 나누는 이유: **오류 번역이 이 서버의 실질적인 로직 전부**이고, 그것을 MCP 없이 테스트할 수 있어야 한다. `client.py`는 MCP를 모른다.

---

### Task 1: 프로젝트 골격 + WMS 클라이언트

**Files:**
- Create: `mcp-server/pyproject.toml`, `mcp-server/wms_mcp/__init__.py`, `mcp-server/wms_mcp/client.py`
- Create: `mcp-server/tests/test_client.py`

**Interfaces:**
- Produces (Task 2가 쓴다):
  - `class WmsError(Exception)` — 예상한 실패. `server.py`가 이것만 잡아 `ToolError`로 바꾼다
  - `def get_return_rates(from_date: str, to_date: str) -> dict`
  - `def get_category_breakdown(from_date: str, to_date: str) -> dict`
  - `def get_details_by_product(product_id: int, from_date: str, to_date: str) -> list`
  - `def get_details_by_category(category: str, from_date: str, to_date: str) -> list`
  - `def _build_client() -> httpx.Client` — 테스트가 갈아끼우는 자리

- [ ] **Step 1: 프로젝트를 만든다**

```bash
cd /Users/jo/study/jhg-wms-project
mkdir -p mcp-server/wms_mcp mcp-server/tests
cd mcp-server
uv init --python 3.13 --no-workspace .
rm -f main.py hello.py            # uv init이 만드는 샘플. 지운다
uv add mcp httpx
uv add --dev pytest pytest-asyncio
touch wms_mcp/__init__.py
```

`pyproject.toml`에 아래를 더한다(테스트가 `wms_mcp`를 import할 수 있어야 하고, async 테스트가 표시 없이 돌아야 한다):

```toml
[tool.pytest.ini_options]
pythonpath = ["."]
asyncio_mode = "auto"
```

`uv init`이 만든 `[project]`의 `name`은 `mcp-server`로 두고, 버전·설명은 손대지 않는다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`mcp-server/tests/test_client.py`:

```python
import httpx
import pytest

from wms_mcp import client


def _client_returning(status: int, *, json=None, text=None):
    """WMS 대신 정해진 응답을 내는 httpx 클라이언트."""
    def handler(request: httpx.Request) -> httpx.Response:
        if json is not None:
            return httpx.Response(status, json=json, request=request)
        return httpx.Response(status, text=text,
                              headers={"content-type": "text/plain"}, request=request)
    return httpx.Client(transport=httpx.MockTransport(handler), base_url="http://wms.test")


def test_정상_응답을_그대로_돌려준다(monkeypatch):
    body = {"counts": [{"category": "DAMAGED", "ownerArea": "PACKAGING", "count": 8}],
            "unclassified": 13, "totalReturns": 47}
    monkeypatch.setattr(client, "_build_client", lambda: _client_returning(200, json=body))

    assert client.get_category_breakdown("2026-08-01", "2026-08-31") == body


def test_빈_목록도_정상이다(monkeypatch):
    # 빈 결과와 오류를 섞으면 모델이 "반품이 없다"를 실패로 읽는다.
    monkeypatch.setattr(client, "_build_client", lambda: _client_returning(200, json=[]))

    assert client.get_details_by_product(9999, "2026-08-01", "2026-08-31") == []


def test_null_범주를_그대로_통과시킨다(monkeypatch):
    # WMS는 키를 빼지 않고 "category": null 로 준다. 지우면 모델이 미분류를 못 센다.
    rows = [{"rmaReturnId": 1, "productId": 2, "reason": "test",
             "category": None, "confidence": None}]
    monkeypatch.setattr(client, "_build_client", lambda: _client_returning(200, json=rows))

    result = client.get_details_by_category("UNCLASSIFIED", "2026-08-01", "2026-08-31")
    assert result[0]["category"] is None
    assert "category" in result[0]


def test_날짜_형식이_틀리면_WMS를_부르기도_전에_거절한다(monkeypatch):
    def explode():
        raise AssertionError("WMS를 부르면 안 된다 — 경계에서 걸러야 왕복이 줄고 메시지가 정확하다")
    monkeypatch.setattr(client, "_build_client", explode)

    with pytest.raises(client.WmsError) as e:
        client.get_return_rates("2026-8-1", "2026-08-31")
    assert "from_date" in str(e.value)
    assert "YYYY-MM-DD" in str(e.value)


def test_WMS의_400_평문을_그대로_싣는다(monkeypatch):
    # WMS가 이미 모델이 읽을 수 있는 문장을 준다. 여기서 뭉개면 그 설계가 무의미해진다.
    monkeypatch.setattr(client, "_build_client",
                        lambda: _client_returning(400, text="시작일이 종료일보다 뒤입니다."))

    with pytest.raises(client.WmsError) as e:
        client.get_return_rates("2026-08-31", "2026-08-01")
    assert "시작일이 종료일보다 뒤입니다." in str(e.value)


def test_401은_자격증명_문제로_말한다(monkeypatch):
    monkeypatch.setattr(client, "_build_client", lambda: _client_returning(401, text=""))

    with pytest.raises(client.WmsError) as e:
        client.get_return_rates("2026-08-01", "2026-08-31")
    assert "WMS_USER" in str(e.value)


def test_연결_실패를_빈_결과와_구분한다(monkeypatch):
    # 이것이 이 계층이 존재하는 이유다. "창고에 반품이 없다"로 읽히면 보고서가 거짓이 된다.
    def handler(request):
        raise httpx.ConnectError("connection refused", request=request)
    monkeypatch.setattr(client, "_build_client",
                        lambda: httpx.Client(transport=httpx.MockTransport(handler),
                                             base_url="http://wms.test"))

    with pytest.raises(client.WmsError) as e:
        client.get_return_rates("2026-08-01", "2026-08-31")
    assert "연결" in str(e.value)
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

```bash
cd /Users/jo/study/jhg-wms-project/mcp-server && uv run pytest -q
```

Expected: **collection error** — `wms_mcp.client` 모듈이 없다.

- [ ] **Step 4: 클라이언트를 쓴다**

`mcp-server/wms_mcp/client.py`:

```python
"""WMS 반품 분석 REST 호출과 오류 번역.

MCP를 import하지 않는다 — 이 계층의 실질적인 로직은 오류 번역이고, 그것을
프로토콜 없이 테스트할 수 있어야 한다. server.py가 WmsError를 ToolError로 바꾼다.

계산하지 않는다. WMS가 이미 다 했고, 여기서 한 번 더 하면 코호트 정의가 둘이 된다.
"""

import os
from datetime import date

import httpx

BASE_URL = os.environ.get("WMS_BASE_URL", "http://localhost:8081")
TIMEOUT = 10.0


class WmsError(Exception):
    """예상한 실패. server.py가 이것만 잡아 ToolError로 바꾼다.

    예상하지 못한 예외는 여기로 오지 않는다 — 그건 크래시이고, 크래시는 크래시로 보여야 한다.
    """


def _build_client() -> httpx.Client:
    """테스트가 transport를 갈아끼우는 자리."""
    user = os.environ.get("WMS_USER")
    password = os.environ.get("WMS_PASSWORD")
    if not user or not password:
        raise WmsError("환경변수 WMS_USER·WMS_PASSWORD가 필요합니다. MCP 서버 설정을 확인하세요.")
    return httpx.Client(base_url=BASE_URL, auth=(user, password), timeout=TIMEOUT)


def _check_date(name: str, value: str) -> None:
    """도구 경계에서 판정한다. WMS까지 갔다 오면 왕복만 늘고 메시지도 덜 정확해진다."""
    try:
        date.fromisoformat(value)
    except ValueError:
        raise WmsError(f"{name}는 YYYY-MM-DD 형식이어야 합니다: {value!r}") from None


def _get(path: str, from_date: str, to_date: str, **extra):
    _check_date("from_date", from_date)
    _check_date("to_date", to_date)
    params = {"from": from_date, "to": to_date, **extra}   # from은 파이썬 예약어라 여기서 바꾼다
    try:
        with _build_client() as http:
            response = http.get(path, params=params)
    except httpx.ConnectError:
        # 빈 결과와 반드시 구분한다. "창고에 반품이 없다"로 읽히면 보고서가 거짓이 된다.
        raise WmsError(f"WMS({BASE_URL})에 연결할 수 없습니다. 서버가 떠 있는지 확인하세요.") from None
    except httpx.TimeoutException:
        raise WmsError(f"WMS({BASE_URL}) 응답이 {TIMEOUT}초 안에 오지 않았습니다.") from None

    if response.status_code == 401:
        raise WmsError("WMS 인증에 실패했습니다. 환경변수 WMS_USER·WMS_PASSWORD를 확인하세요.")
    if response.status_code == 400:
        # WMS가 이미 모델이 읽을 수 있는 평문을 준다. 뭉개지 않고 그대로 싣는다.
        raise WmsError(response.text.strip())
    if response.status_code != 200:
        raise WmsError(f"WMS가 {response.status_code}를 냈습니다: {response.text.strip()[:200]}")
    return response.json()


def get_return_rates(from_date: str, to_date: str) -> dict:
    return _get("/api/analytics/product-return-rates", from_date, to_date)


def get_category_breakdown(from_date: str, to_date: str) -> dict:
    return _get("/api/analytics/return-categories", from_date, to_date)


def get_details_by_product(product_id: int, from_date: str, to_date: str) -> list:
    return _get(f"/api/analytics/return-details/product/{product_id}", from_date, to_date)


def get_details_by_category(category: str, from_date: str, to_date: str) -> list:
    return _get(f"/api/analytics/return-details/category/{category}", from_date, to_date)
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

```bash
cd /Users/jo/study/jhg-wms-project/mcp-server && uv run pytest -q
```

Expected: **7 passed.**

- [ ] **Step 6: 변이 검증**

각각 바꿔보고 **실패하는지** 본 뒤 원복한다. 실패하지 않으면 그것이 발견이니 보고한다.

1. `except httpx.ConnectError` 블록을 지운다(예외가 그대로 나가게)
   → `test_연결_실패를_빈_결과와_구분한다`가 실패해야 한다.
2. `_get` 맨 앞의 `_check_date` 두 줄을 지운다
   → `test_날짜_형식이_틀리면_WMS를_부르기도_전에_거절한다`가 `AssertionError`로 실패해야 한다.
3. 400 분기에서 `response.text.strip()` 대신 `"잘못된 요청"`을 던진다
   → `test_WMS의_400_평문을_그대로_싣는다`가 실패해야 한다.

원복 후 7 passed 확인.

- [ ] **Step 7: WMS 테스트가 그대로인지 확인한다**

```bash
cd /Users/jo/study/jhg-wms-project
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: **428건 그린.** 이 태스크는 Java를 건드리지 않았으므로 숫자가 바뀌면 뭔가 잘못한 것이다.

- [ ] **Step 8: 커밋**

```bash
cd /Users/jo/study/jhg-wms-project
git add mcp-server/
git commit -F - <<'MSG'
feat(mcp): WMS 분석 REST 클라이언트와 오류 번역 (V6.0b)

MCP를 import하지 않는 계층으로 뺐다. 이 서버의 실질적인 로직은 오류 번역이고
그것을 프로토콜 없이 테스트할 수 있어야 한다.

연결 실패를 빈 결과와 구분하는 것이 이 계층이 존재하는 이유다. WMS가 안 떠 있는데
빈 목록으로 보이면 모델이 "창고에 반품이 없다"고 쓴다 — 보고서가 거짓이 된다.

날짜는 WMS까지 가기 전에 도구 경계에서 판정한다. 왕복이 줄고 메시지도 정확해진다.
WMS의 400 평문은 뭉개지 않고 그대로 싣는다 — 이미 모델이 읽을 수 있는 문장이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 2: 도구 넷과 stdio 서버

**Files:**
- Create: `mcp-server/wms_mcp/server.py`
- Create: `mcp-server/tests/test_tools.py`
- Modify: `mcp-server/pyproject.toml` (진입점 추가)

**Interfaces:**
- Consumes: Task 1의 `client.get_*` 넷과 `client.WmsError`
- Produces: `mcp` (모듈 수준 `MCPServer` 인스턴스), `main()`.
  도구 이름 넷: `product_return_rates`, `return_category_breakdown`,
  `return_details_by_product`, `return_details_by_category`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`mcp-server/tests/test_tools.py`:

```python
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from wms_mcp import client, server

EXPECTED_TOOLS = {
    "product_return_rates",
    "return_category_breakdown",
    "return_details_by_product",
    "return_details_by_category",
}


async def test_등록된_도구는_정확히_넷이다():
    # 이 표면이 곧 공격면이다. 쓰기 도구가 하나 붙으면 이 테스트가 실패한다.
    # 주석으로 적어둔 규칙은 지켜지지 않지만 실패하는 테스트는 지켜진다.
    tools = await server.mcp.list_tools()
    assert {t.name for t in tools} == EXPECTED_TOOLS


async def test_모든_도구가_기간을_필수로_받는다():
    # 기본값을 두지 않는다는 결정을 스키마 수준에서 고정한다.
    for tool in await server.mcp.list_tools():
        assert {"from_date", "to_date"} <= set(tool.input_schema["required"]), tool.name


async def test_모든_도구에_설명이_있다():
    # 설명이 모델이 도구를 고르는 유일한 근거다.
    for tool in await server.mcp.list_tools():
        assert tool.description and tool.description.strip(), tool.name


async def test_상품_상세_도구가_클라이언트에_그대로_위임한다(monkeypatch):
    seen = {}
    def fake(product_id, from_date, to_date):
        seen.update(product_id=product_id, from_date=from_date, to_date=to_date)
        return [{"rmaReturnId": 1, "category": None}]
    monkeypatch.setattr(client, "get_details_by_product", fake)

    result = await server.mcp.call_tool(
        "return_details_by_product",
        {"product_id": 11, "from_date": "2026-08-01", "to_date": "2026-08-31"})

    assert seen == {"product_id": 11, "from_date": "2026-08-01", "to_date": "2026-08-31"}
    assert "rmaReturnId" in str(result.content[0].text)


async def test_WmsError는_ToolError가_되어_메시지가_모델에_닿는다(monkeypatch):
    # 실측: 다른 예외를 던지면 모델은 "Error executing tool <name>"만 본다.
    # 이 설계의 "모델이 스스로 고친다"가 여기 달려 있다.
    def boom(from_date, to_date):
        raise client.WmsError("시작일이 종료일보다 뒤입니다.")
    monkeypatch.setattr(client, "get_return_rates", boom)

    with pytest.raises(ToolError) as e:
        await server.mcp.call_tool(
            "product_return_rates", {"from_date": "2026-08-31", "to_date": "2026-08-01"})
    assert "시작일이 종료일보다 뒤입니다." in str(e.value)


async def test_범주_도구가_UNCLASSIFIED를_그대로_넘긴다(monkeypatch):
    seen = {}
    def fake(category, from_date, to_date):
        seen["category"] = category
        return []
    monkeypatch.setattr(client, "get_details_by_category", fake)

    await server.mcp.call_tool(
        "return_details_by_category",
        {"category": "UNCLASSIFIED", "from_date": "2026-08-01", "to_date": "2026-08-31"})

    # enum에 없는 값이지만 WMS가 이 이름으로 받는다. 여기서 걸러내면 미분류를 못 본다.
    assert seen["category"] == "UNCLASSIFIED"
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
cd /Users/jo/study/jhg-wms-project/mcp-server && uv run pytest -q
```

Expected: `wms_mcp.server` 모듈이 없어 collection error. Task 1의 7건은 계속 통과.

- [ ] **Step 3: 서버를 쓴다**

`mcp-server/wms_mcp/server.py`:

```python
"""WMS 반품 분석 MCP 서버 — 읽기 전용 도구 넷.

두 얼굴이다. Claude Code 쪽으로는 MCP 서버(도구를 내놓는 쪽), WMS 쪽으로는
HTTP 클라이언트다. 그 사이에서 하는 일은 번역뿐이다.

도구가 전부 읽기 전용인 것은 취향이 아니라 안전 조건이다. 반품 사유는 고객이 쓴
자유 텍스트이고 그것이 모델 컨텍스트로 들어간다. 쓰기 도구가 하나라도 섞이면
고객이 사유란에 쓴 지시가 창고 데이터를 건드릴 경로가 된다.

from·to가 아니라 from_date·to_date인 이유: from은 파이썬 예약어다. REST 파라미터
이름으로 바꾸는 일은 client.py가 한다.
"""

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from wms_mcp import client

mcp = MCPServer("wms")


def _guard(call):
    """예상한 실패(WmsError)만 ToolError로 바꾼다.

    실측: ToolError의 메시지는 모델에게 닿고, 다른 예외는 'Error executing tool <name>'으로
    뭉개진다. 반대로 예상 못 한 예외를 여기서 삼키면 크래시가 조용한 오답이 된다 —
    그래서 WmsError만 잡는다.
    """
    try:
        return call()
    except client.WmsError as e:
        raise ToolError(str(e)) from None


@mcp.tool()
def product_return_rates(from_date: str, to_date: str) -> dict:
    """기간 내 출고된 주문을 모수로 한 상품별 반품률.

    관찰 경과일(observedDays)이 짧으면 반품이 아직 들어오는 중이라 실제보다 낮게 보인다.
    숫자를 인용하기 전에 경과일을 확인할 것. 날짜는 YYYY-MM-DD.
    """
    return _guard(lambda: client.get_return_rates(from_date, to_date))


@mcp.tool()
def return_category_breakdown(from_date: str, to_date: str) -> dict:
    """범주별 반품 건수와 소관 영역, 미분류 수, 전체 수.

    네 범주가 0건이어도 항상 나온다. unclassified는 분류가 없는 반품 수이고,
    분류된 건수만으로 전체를 말하면 분모가 틀린다. 날짜는 YYYY-MM-DD.
    """
    return _guard(lambda: client.get_category_breakdown(from_date, to_date))


@mcp.tool()
def return_details_by_product(product_id: int, from_date: str, to_date: str) -> list:
    """그 상품 반품의 사유 원문·범주·신뢰도.

    반품 하나가 상품 둘을 담으면 행이 둘이다 — 행 수는 반품 수가 아니라 품목 수다.
    category·confidence가 null이면 미분류다. 날짜는 YYYY-MM-DD.
    """
    return _guard(lambda: client.get_details_by_product(product_id, from_date, to_date))


@mcp.tool()
def return_details_by_category(category: str, from_date: str, to_date: str) -> list:
    """그 범주 반품의 사유 원문 목록.

    category는 DAMAGED, WRONG_ITEM, CHANGED_MIND, OTHER, UNCLASSIFIED 중 하나.
    UNCLASSIFIED는 분류가 없는 반품이다. 날짜는 YYYY-MM-DD.
    """
    return _guard(lambda: client.get_details_by_category(category, from_date, to_date))


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
```

`pyproject.toml`에 진입점을 더한다:

```toml
[project.scripts]
wms-mcp = "wms_mcp.server:main"
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```bash
cd /Users/jo/study/jhg-wms-project/mcp-server && uv run pytest -q
```

Expected: **13 passed** (Task 1의 7 + 이번 6).

- [ ] **Step 5: 변이 검증**

바꿔보고 **실패하는지** 확인한 뒤 원복한다.

1. `server.py`에 도구를 하나 더 붙인다 (`EXPECTED_TOOLS`는 건드리지 않는다):
   ```python
   @mcp.tool()
   def ping() -> str:
       """테스트용."""
       return "pong"
   ```
   → `test_등록된_도구는_정확히_넷이다`가 실패해야 한다. **이 테스트가 공격면을 지키는 방식이다.**
2. `_guard`의 `raise ToolError(str(e))`를 `return {}`로 바꾼다
   → `test_WmsError는_ToolError가_되어_메시지가_모델에_닿는다`가 실패해야 한다.
3. `return_details_by_category` 본문에서 `category`를 `""`로 치환해 넘긴다
   → `test_범주_도구가_UNCLASSIFIED를_그대로_넘긴다`가 실패해야 한다.

원복 후 13 passed 확인.

- [ ] **Step 6: 커밋**

```bash
cd /Users/jo/study/jhg-wms-project
git add mcp-server/
git commit -F - <<'MSG'
feat(mcp): 읽기 전용 도구 넷과 stdio 서버 (V6.0b)

도구 이름 집합을 테스트로 고정했다. 이 표면이 곧 공격면이고, 반품 사유는 고객이
쓴 자유 텍스트라 쓰기 도구가 하나라도 섞이면 고객이 창고 데이터를 건드릴 경로가
열린다. 주석으로 적어둔 규칙은 지켜지지 않지만 실패하는 테스트는 지켜진다.

WmsError만 ToolError로 바꾼다. 실측 확인: ToolError의 메시지는 모델에게 닿고
다른 예외는 "Error executing tool <name>"으로 뭉개진다. 반대로 예상 못 한 예외를
여기서 삼키면 크래시가 조용한 오답이 되므로 WmsError만 잡는다.

도구 설명에 판단 기준을 넣었다 — 관찰 경과일, 미분류 분모, 행 수는 품목 수라는 것.
모델이 도구를 고르고 결과를 읽는 유일한 근거가 이 문장이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 3: CI · 등록 설정 · 문서 · 실연결 확인

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `.mcp.json`, `mcp-server/README.md`
- Modify: `README.md` (루트)

**Interfaces:**
- Consumes: Task 1·2의 `mcp-server/` 전체
- Produces: CI 잡 `mcp-server`, Claude Code 등록 설정

- [ ] **Step 1: CI 잡을 더한다**

`.github/workflows/ci.yml`에 기존 `build-and-test`·`docker-build`와 같은 수준으로 잡 하나를 더한다.
**기존 두 잡은 건드리지 않는다.**

```yaml
  mcp-server:
    name: MCP server (Python)
    runs-on: ubuntu-latest
    # WMS를 띄우지 않는다. 이 테스트는 httpx MockTransport로 도는 단위 테스트라
    # PostgreSQL도 Java도 필요 없다. 잡을 나눠두면 파이썬이 깨졌을 때 바로 보인다.
    steps:
      - uses: actions/checkout@v5

      - name: Set up uv
        uses: astral-sh/setup-uv@v7
        with:
          enable-cache: true

      - name: Run tests
        working-directory: mcp-server
        run: uv run pytest -q
```

- [ ] **Step 2: `.mcp.json`을 만든다 — 자격증명 값은 넣지 않는다**

저장소 루트에 `.mcp.json`:

```json
{
  "mcpServers": {
    "wms": {
      "command": "uv",
      "args": ["run", "--directory", "mcp-server", "wms-mcp"],
      "env": {
        "WMS_BASE_URL": "http://localhost:8081",
        "WMS_USER": "${WMS_USER}",
        "WMS_PASSWORD": "${WMS_PASSWORD}"
      }
    }
  }
}
```

**이 파일은 커밋된다.** 그래서 값이 아니라 셸 환경변수 참조만 싣는다. 실제 값은 `~/.zshenv`에 둔다(이 저장소가 API 키를 다루는 방식과 같다).

- [ ] **Step 3: `mcp-server/README.md`를 쓴다**

````markdown
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
uv run wms-mcp       # 서버 (stdio — 보통은 Claude Code가 띄웁니다)
```

## 연결

저장소 루트의 `.mcp.json`이 등록을 담고 있습니다. `WMS_USER`·`WMS_PASSWORD`를
셸 환경에 두고 Claude Code를 이 저장소에서 실행하면 도구 넷이 보입니다.
````

- [ ] **Step 4: 루트 README에 한 문단 더한다**

`README.md`의 `### 반품 분석 조회 (V6.0) — 내부 도구용, OMS 채널 아님` 절 **끝에**:

```markdown
이 넷을 부르는 것은 `mcp-server/`의 Python MCP 서버입니다(별도 프로세스, stdio).
Claude Code가 그 서버에 붙어 반품 보고서를 씁니다 — WMS 안에는 LLM 호출이 없습니다.
자세한 내용은 [`mcp-server/README.md`](mcp-server/README.md).
```

- [ ] **Step 5: 전체 테스트 — 둘 다**

```bash
cd /Users/jo/study/jhg-wms-project/mcp-server && uv run pytest -q
cd /Users/jo/study/jhg-wms-project && JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```

Expected: **파이썬 13 passed, Java 428건 그린.**

- [ ] **Step 6: 실연결 확인 (자동 테스트가 못 재는 것)**

**이 단계는 생략할 수 없다.** 스펙의 열린 문제에 "Claude Code에서 실제로 붙어 도구가 보이는가는 수동 확인이 필요하다"고 적혀 있고, V6.0a에서도 실기동 curl이 슬라이스 테스트가 못 잡은 결함을 잡았다.

1. WMS를 띄운다(IDE 8081, 또는 `./gradlew bootRun --args='--server.port=8081'`).
2. `export WMS_USER=wms WMS_PASSWORD=wms`
3. 새 Claude Code 세션을 이 저장소에서 열고 `/mcp`로 `wms` 서버가 connected인지, 도구 넷이 보이는지 확인한다.
4. 도구를 하나 불러본다 — 2026-08-01~2026-09-02의 범주 분해. `unclassified`와 `totalReturns`가 나오는지 본다.
5. **오류 경로도 확인한다** — 날짜를 `2026-8-1`로 주고, 받는 메시지에 `YYYY-MM-DD`가 들어 있는지 본다. `ToolError` 경로가 실제로 도는지 보는 유일한 방법이다.
6. WMS를 내리고 도구를 다시 불러 **"연결할 수 없습니다"가 빈 결과와 구분되는지** 본다.

관찰한 것을 원장(`.superpowers/sdd/progress.md`)에 적는다.

- [ ] **Step 7: 커밋**

```bash
cd /Users/jo/study/jhg-wms-project
git add .github/workflows/ci.yml .mcp.json mcp-server/README.md README.md
git commit -F - <<'MSG'
docs(mcp): CI 잡·등록 설정·문서 (V6.0b)

CI 잡은 WMS를 띄우지 않는다. 도구 테스트가 httpx MockTransport로 도는 단위
테스트라 PostgreSQL도 Java도 필요 없다 — 잡을 나눠두면 파이썬이 깨졌을 때
어디가 깨졌는지 바로 보인다.

.mcp.json은 커밋되므로 자격증명 값이 아니라 셸 환경변수 참조만 싣는다.
그 자격증명이 /api/** 전체에 유효하다는 점도 문서에 적었다 — 읽기 전용은
이 서버가 부르는 URL 넷이 지키는 것이지 HTTP 계층이 지키는 것이 아니다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

## 안 하는 것 — 그리고 왜

- **창 크기·응답 크기 제한.** 스펙의 열린 문제가 "V6.0b가 조일 자리"라고 지목했지만 이번에는
  넣지 않는다. 얼마가 큰지 아직 모르고, 모르는 상태에서 정한 상한은 정상 질문을 막거나
  아무것도 막지 못한다. 대신 `client.py`의 `_get`에 천장을 명시하는 주석을 남긴다:

  ```python
  # ponytail: 창 크기 무제한. 모델이 from=2020-01-01을 부르면 사유 원문이 통째로 컨텍스트에
  # 들어간다. 실제 보고서를 몇 번 써 보고 토큰이 문제가 되면 그때 상한을 둔다(응답 행 수 기준).
  ```
  실사용에서 한 번이라도 문제가 되면 그때 V6.0c 이후 작업으로 연다.
- **재시도·캐시.** 읽기 전용이고 호출 빈도가 낮다. 필요해지면 그때.
- **HTTP 전송(streamable-http).** stdio로 충분하다. 원격에서 붙일 일이 생기면 그때 연다.
- **리소스·프롬프트 capability.** 도구만 연다(스펙 결정).

## 완료 조건

- [ ] `mcp-server`에서 `uv run pytest -q` → 13 passed
- [ ] WMS `./gradlew test` → 428건 그린 (이 작업으로 바뀌지 않아야 한다)
- [ ] `mcp-server/`에 DB 드라이버 의존성이 없다 — `grep -iE 'psycopg|sqlalchemy|asyncpg' mcp-server/pyproject.toml`이 비어 있음
- [ ] `client.py`에 GET 말고 다른 HTTP 메서드가 없다 — `grep -nE 'http\.(post|put|patch|delete)' mcp-server/wms_mcp/client.py`가 비어 있음
- [ ] `.mcp.json`에 자격증명 **값**이 없다
- [ ] Java 코드와 `settings.gradle`에 diff가 없다 — `git diff master --stat -- src/ settings.gradle`이 비어 있음
- [ ] Step 6의 실연결 확인 6항목을 전부 수행하고 원장에 기록

## 다음 단계 (이 계획서 밖)

V6.0c — `.claude/skills/wms-return-report/SKILL.md`. 보고서 작성 규약이고 스펙의 Skill 절에 담을 내용이 이미 정리돼 있다. 도구가 실제로 도는 것을 본 뒤에 쓴다.
