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


def test_자격증명이_없으면_소켓을_열기_전에_거절한다(monkeypatch):
    # _build_client의 실제 본문을 도는 유일한 테스트다. 나머지는 이 함수를 통째로 갈아끼운다.
    monkeypatch.delenv("WMS_USER", raising=False)
    monkeypatch.delenv("WMS_PASSWORD", raising=False)

    with pytest.raises(client.WmsError) as e:
        client.get_return_rates("2026-08-01", "2026-08-31")
    assert "WMS_USER" in str(e.value)


def test_클라이언트가_베이스URL과_자격증명을_제대로_싣는다(monkeypatch):
    # (user, password) 순서가 뒤바뀌거나 base_url을 빠뜨려도 다른 테스트는 전부 통과한다.
    import base64

    monkeypatch.setenv("WMS_USER", "wms-user")
    monkeypatch.setenv("WMS_PASSWORD", "wms-secret")

    http = client._build_client()
    try:
        # base_url 검증
        assert str(http.base_url) == client.BASE_URL

        # timeout 검증
        assert http.timeout.read == client.TIMEOUT

        # 자격증명 순서 검증: BasicAuth의 미리 계산된 헤더를 검사한다.
        auth_header = http._auth._auth_header
        assert auth_header.startswith("Basic ")
        encoded = auth_header[6:]
        decoded = base64.b64decode(encoded).decode('utf-8')
        user, password = decoded.split(':', 1)
        assert user == "wms-user"
        assert password == "wms-secret"
    finally:
        http.close()
