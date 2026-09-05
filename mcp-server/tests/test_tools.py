import pytest
from mcp.server.mcpserver.exceptions import ToolError

from wms_mcp import client, server

EXPECTED_TOOLS = {
    "product_return_rates",
    "return_category_breakdown",
    "return_details_by_product",
    "return_details_by_category",
    "cycle_count_accuracy",
    "cycle_count_variances",
    "inventory_ledger",
    "reservation_dwell",
    "reservation_dwell_by_product",
}


async def test_등록된_도구는_정확히_아홉이다():
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


async def test_실사_정확도_도구가_클라이언트에_그대로_위임한다(monkeypatch):
    seen = {}
    def fake(from_date, to_date):
        seen.update(from_date=from_date, to_date=to_date)
        return {"accuracy": 0.68, "excludedRejectedItems": 3}
    monkeypatch.setattr(client, "get_cycle_count_accuracy", fake)

    result = await server.mcp.call_tool(
        "cycle_count_accuracy", {"from_date": "2026-09-01", "to_date": "2026-09-30"})

    assert seen == {"from_date": "2026-09-01", "to_date": "2026-09-30"}
    assert "excludedRejectedItems" in str(result.content[0].text)


async def test_잴_것이_없을_때의_null_정확도를_그대로_통과시킨다(monkeypatch):
    # 0.0으로 바꾸면 "전부 틀렸다"가 된다. 잴 것이 없는 것과 다 틀린 것은 다르다 —
    # 이 계층은 번역만 하고 판단하지 않는다.
    monkeypatch.setattr(client, "get_cycle_count_accuracy",
                        lambda f, t: {"accuracy": None, "countedItems": 0})

    result = await server.mcp.call_tool(
        "cycle_count_accuracy", {"from_date": "2026-09-01", "to_date": "2026-09-30"})

    assert "null" in str(result.content[0].text)


async def test_실사_차이_도구가_빈_목록도_정상으로_돌려준다(monkeypatch):
    # 차이가 없는 것은 오류가 아니라 좋은 소식이다.
    monkeypatch.setattr(client, "get_cycle_count_variances", lambda f, t: [])

    result = await server.mcp.call_tool(
        "cycle_count_variances", {"from_date": "2026-09-01", "to_date": "2026-09-30"})

    assert result.content is not None


async def test_원장_도구가_잘림_표시를_그대로_통과시킨다(monkeypatch):
    # 잘린 사실을 삼키면 모델이 받은 것을 전량으로 읽고 "이동이 없었다"고 쓴다.
    monkeypatch.setattr(client, "get_inventory_ledger",
                        lambda p, f, t: {"rows": [], "truncated": True, "total": 812})

    result = await server.mcp.call_tool(
        "inventory_ledger",
        {"product_id": 11, "from_date": "2026-09-01", "to_date": "2026-09-03"})

    assert "truncated" in str(result.content[0].text)
    assert "812" in str(result.content[0].text)
