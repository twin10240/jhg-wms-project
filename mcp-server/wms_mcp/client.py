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
