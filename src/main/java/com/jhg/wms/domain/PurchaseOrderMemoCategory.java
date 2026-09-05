package com.jhg.wms.domain;

/**
 * 발주 메모 범주.
 *
 * 범주를 나눈 기준은 "메모 문구가 비슷한가"가 아니라 <b>범주마다 볼 곳이 다른가</b>이다.
 * 문구로 나누면 분류기가 잘 맞히는 범주가 만들어질 뿐, 맞혀도 할 일이 없다.
 *
 * 라벨을 여기 둔다 — {@link ReturnCategory}와 같은 이유로, 화면마다 매핑을 따로 두면 갈라진다.
 */
public enum PurchaseOrderMemoCategory {

    /** 재고가 바닥나서 급히 넣은 발주. 근거 패널이 늦었거나 아무도 보지 않았다는 신호다. */
    URGENT_STOCKOUT("결품 대응"),

    /** 특별한 사정 없이 주기적으로 넣는 발주. 볼 것이 없다는 것이 이 범주의 쓸모다. */
    ROUTINE("정기 보충"),

    /** 행사·시즌·신상품 초도처럼 예정된 수요를 위한 발주. 과거 일평균으로 예측되지 않는다. */
    DEMAND_EVENT("수요 이벤트"),

    /** 최소발주수량·단가 인상·거래처 휴무·리드타임 변경 등. 발주량이 실제 필요량과 다르다는 신호다. */
    SUPPLIER("거래처 사정"),

    /** 불량 교체분·반품 재입고 지연 보전. 품질 쪽으로 넘길 신호다. */
    QUALITY("품질 보전"),

    /** 위 어디에도 분명히 들어가지 않는다. 메모가 짧거나("추가") 사내 맥락에 기댄 경우가 여기 온다. */
    OTHER("기타");

    private final String label;

    PurchaseOrderMemoCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
