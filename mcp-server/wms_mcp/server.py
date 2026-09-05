"""WMS 분석 MCP 서버 — 읽기 전용 도구 아홉.

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


@mcp.tool()
def inventory_ledger(product_id: int, from_date: str, to_date: str) -> dict:
    """한 상품의 재고 원장. 기간 내 이동을 시간 오름차순으로 준다.

    beforeQty→afterQty가 행마다 이어붙는다. 사슬이 끊긴 자리는 원장 밖 이동이 있었다는
    뜻이지만, 그것이 무엇인지는 이 데이터로 말할 수 없다.
    ADJUST가 있으면 사람이 이미 조정한 것이다 — 실사 차이와 겹쳐 읽으면 이중 계상이 된다.
    빈 목록은 오류가 아니라 그 기간에 기록된 이동이 없었다는 뜻이다.
    truncated가 true면 500행에서 잘린 것이다 — 남은 것은 최근 500행이고, 전체 수는 total이다.
    행위자(actor)는 주지 않는다. 사람 확인이 필요하면 원장 화면으로 넘겨라.
    날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_inventory_ledger(product_id, from_date, to_date))


@mcp.tool()
def reservation_dwell(from_date: str, to_date: str) -> dict:
    """기간 내 끝난 예약이 재고를 붙들고 있던 시간의 분포.

    체류는 예약 생성부터 종료까지다. 구간 판정은 종료 시각(basis="endedAt")이지 생성 시각이 아니다.
    shipped와 released는 따로 온다 — 합쳐 읽지 마라. 출고 체류는 정상 처리에 걸린 시간이고
    해제 체류는 헛되이 묶여 있던 시간이라 조치할 곳이 다르다.
    count가 0이면 median·p90·max는 null이다. 0분과 잴 것이 없는 것은 다르다.
    stillOpen은 구간 끝에 아직 안 끝난 예약 수이고 생존 편향의 크기다 — 오래 붙들린 예약일수록
    아직 안 끝나 이 분포에 안 잡히므로, 이 수를 밝히지 않고 중앙값을 인용하지 마라.
    stillOpen은 from_date에 의존하지 않는다 — 구간 안에서 센 것이 아니라 구간 끝 시점의
    잔량(그 시각에 아직 안 끝난 예약 수)이라, 표본 크기와 직접 비교할 수치가 아니다.
    excludedMissingCreatedAt은 생성 시각이 없어 잴 수 없었던 예약 수다. 보고서는 그 수를 밝혀라.
    단위는 분이다. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_reservation_dwell(from_date, to_date))


@mcp.tool()
def reservation_dwell_by_product(from_date: str, to_date: str) -> list:
    """상품별 체류 묶음. 반복해서 오래 붙들린 상품이 먼저 온다.

    집계 도구와 같은 모수다(기간 내 끝난 예약, 생성 시각이 있는 것만).
    occurrences는 그 상품이 든 예약 중 체류를 잰 건수다 — 예약 하나가 상품 여럿을 담으면
    담은 수만큼 계상되므로 occurrences의 합은 예약 건수가 아니다.
    medianMinutes·maxMinutes는 출고와 해제를 합친 값이다 — 경로별로 갈라 주지 않는다.
    어느 쪽이 얼마나 섞였는지는 shippedCount·releasedCount로만 알 수 있고,
    경로를 갈라 본 분포가 필요하면 reservation_dwell을 써라.
    한 번 아주 오래 걸린 것보다 여러 번 반복해서 오래 걸리는 쪽이 로케이션·재고 부족 같은
    구조적 원인을 가리킨다 — 정렬이 그 순서다.
    빈 목록은 오류가 아니라 그 기간에 끝난 예약이 없었다는 뜻이다.
    단위는 분이다. 날짜는 YYYY-MM-DD이고 구간은 최대 366일이다.
    """
    return _guard(lambda: client.get_reservation_dwell_by_product(from_date, to_date))


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
