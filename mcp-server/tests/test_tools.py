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
