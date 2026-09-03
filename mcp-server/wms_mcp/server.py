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
    숫자를 인용하기 전에 경과일을 확인할 것. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_return_rates(from_date, to_date))


@mcp.tool()
def return_category_breakdown(from_date: str, to_date: str) -> dict:
    """범주별 반품 건수와 소관 영역, 미분류 수, 전체 수.

    네 범주가 0건이어도 항상 나온다. unclassified는 분류가 없는 반품 수이고,
    분류된 건수만으로 전체를 말하면 분모가 틀린다. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_category_breakdown(from_date, to_date))


@mcp.tool()
def return_details_by_product(product_id: int, from_date: str, to_date: str) -> list:
    """그 상품 반품의 사유 원문·범주·신뢰도.

    반품 하나가 상품 둘을 담으면 행이 둘이다 — 행 수는 반품 수가 아니라 품목 수다.
    category·confidence가 null이면 미분류다. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    존재하지 않는 product_id도 오류가 아니라 빈 목록을 반환한다 — 이 도구는 상품 존재를
    확인하지 않는다. "반품 없음"으로 보고하기 전에 product_id 오타부터 의심할 것.
    """
    return _guard(lambda: client.get_details_by_product(product_id, from_date, to_date))


@mcp.tool()
def return_details_by_category(category: str, from_date: str, to_date: str) -> list:
    """그 범주 반품의 사유 원문 목록.

    category는 DAMAGED, WRONG_ITEM, CHANGED_MIND, OTHER, UNCLASSIFIED 중 하나.
    UNCLASSIFIED는 분류가 없는 반품이다. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_details_by_category(category, from_date, to_date))


@mcp.tool()
def cycle_count_accuracy(from_date: str, to_date: str) -> dict:
    """기간 내 실사의 계수 정확도와 세션 상태 분포.

    구간 판정은 실사를 시작한 시각(basis="createdAt")이지 승인 시각이 아니다.
    정확도의 분모는 승인된 세션의 항목뿐이다 — 반려된 세션은 "계수를 신뢰할 수 없다"고
    사람이 판정한 것이라 뺐고, 뺀 개수가 excludedRejectedItems로 함께 온다. 보고서는 그 수를 밝혀라.
    accuracy가 null이면 잴 것이 없다는 뜻이다. 0으로 읽지 마라 — 전부 틀린 것과 다르다.
    날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_cycle_count_accuracy(from_date, to_date))


@mcp.tool()
def cycle_count_variances(from_date: str, to_date: str) -> list:
    """차이가 난 상품 목록. 반복해서 틀린 상품이 먼저 온다.

    승인된 세션만 본다(정확도와 같은 모수). occurrences는 몇 번의 실사에서 차이가 났는지이고,
    netQty는 그 차이들의 합이다(부호가 상쇄될 수 있다 — 과다와 부족이 섞이면 작아 보인다).
    한 번 크게 틀린 것보다 여러 번 조금씩 틀리는 쪽이 로케이션·라벨 같은 구조적 원인을 가리킨다.
    빈 목록은 오류가 아니라 차이가 없었다는 뜻이다. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_cycle_count_variances(from_date, to_date))


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
