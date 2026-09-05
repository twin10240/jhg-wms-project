"""WMS 반품 분석 REST 호출과 오류 번역.

MCP를 import하지 않는다 — 이 계층의 실질적인 로직은 오류 번역이고, 그것을
프로토콜 없이 테스트할 수 있어야 한다. server.py가 WmsError를 ToolError로 바꾼다.

계산하지 않는다. WMS가 이미 다 했고, 여기서 한 번 더 하면 코호트 정의가 둘이 된다.
"""

import os
from datetime import date
from urllib.parse import quote

import httpx

BASE_URL = os.environ.get("WMS_BASE_URL", "http://localhost:8081")
TIMEOUT = 10.0

# 조회 구간 상한. 윤년 한 해를 그대로 담을 수 있는 값이라 정당한 연간 리뷰는 막지 않고,
# from=2020-01-01 같은 구간만 끊는다.
MAX_WINDOW_DAYS = 366


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


def _check_window(from_date: str, to_date: str) -> None:
    """구간 길이를 도구 경계에서 끊는다.

    호출자가 화면에서 모델로 바뀌면서 생긴 실패 모드다. 사람은 화면에서 달력을 집지만
    모델은 from=2020-01-01을 그럴듯하게 던진다(실측: 기간을 안 주고 보고서를 시키면
    최근 3개월짜리를 고르는 회차가 나온다). WMS는 구간 내 원장을 거르기 전에 메모리에
    올리므로, 소켓을 열기 전에 여기서 끊는 것이 서버와 토큰 예산 양쪽에 싸다.

    from > to는 검사하지 않는다 — WMS가 이미 400과 읽을 수 있는 평문으로 거절하고
    _get이 그 문구를 그대로 싣는다. 여기서 또 판정하면 메시지 출처가 둘이 된다.
    """
    days = (date.fromisoformat(to_date) - date.fromisoformat(from_date)).days
    if days > MAX_WINDOW_DAYS:
        raise WmsError(
            f"조회 구간이 너무 깁니다: {from_date}~{to_date} ({days}일). "
            f"최대 {MAX_WINDOW_DAYS}일까지만 조회할 수 있습니다. 구간을 좁혀 다시 부르세요."
        )


# ponytail: 응답 행 수에는 상한이 없다. 구간을 366일로 끊으면 명백한 실수는 막히지만,
# 반품이 많은 창고에서는 정당한 구간의 상세 조회도 사유 원문으로 토큰을 태울 수 있다.
# 실측(2026-09-03) 전 기간 상세가 50행·653자라 아직 발동할 여지가 없어 넣지 않았다.
# 필요해지면 상세 도구의 반환을 {"rows": [...], "truncated": bool, "total": int}로 바꾼다
# — 잘린 사실을 담지 않고 그냥 자르면 조용한 오답이 된다.
def _get(path: str, from_date: str, to_date: str):
    _check_date("from_date", from_date)
    _check_date("to_date", to_date)
    _check_window(from_date, to_date)
    params = {"from": from_date, "to": to_date}   # from은 파이썬 예약어라 여기서 바꾼다
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
        raise WmsError(response.text.strip() or "WMS가 요청을 거절했습니다(400).")
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
    # category는 모델이 채우는 문자열이다. 퍼센트 인코딩 없이 넣으면 "../"로 이 서버가
    # 부르는 URL 넷 바깥(같은 자격증명이 유효한 임의 /api 경로)으로 나갈 수 있다.
    return _get(f"/api/analytics/return-details/category/{quote(category, safe='')}", from_date, to_date)


def get_cycle_count_accuracy(from_date: str, to_date: str) -> dict:
    return _get("/api/analytics/cycle-count-accuracy", from_date, to_date)


def get_cycle_count_variances(from_date: str, to_date: str) -> list:
    return _get("/api/analytics/cycle-count-variances", from_date, to_date)


def get_inventory_ledger(product_id: int, from_date: str, to_date: str) -> dict:
    # int()로 강제해 경로 조작을 이 함수 안에서 막는다(category처럼 quote가 필요한 게 아니라
    # 애초에 숫자가 아니면 여기서 ValueError로 죽는다) — 스키마상 int라는 보장을 MCP 도구
    # 계층에 기대지 않는다. 이 모듈은 단독으로도 import될 수 있다.
    return _get(f"/api/analytics/inventory-ledger/product/{int(product_id)}", from_date, to_date)


def get_reservation_dwell(from_date: str, to_date: str) -> dict:
    return _get("/api/analytics/reservation-dwell", from_date, to_date)


def get_reservation_dwell_by_product(from_date: str, to_date: str) -> list:
    return _get("/api/analytics/reservation-dwell-by-product", from_date, to_date)
