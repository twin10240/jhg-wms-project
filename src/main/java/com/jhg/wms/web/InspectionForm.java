package com.jhg.wms.web;

import com.jhg.wms.domain.RmaDisposition;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class InspectionForm {
    private List<Item> items = new ArrayList<>();

    // 승인 수량은 Integer — primitive면 미입력이 0(전량 거절)으로 조용히 바뀐다.
    // 되돌릴 수 없는 전이라서 "입력하지 않음"과 "0을 입력함"은 서버에서 구분해야 한다.
    @Getter @Setter
    public static class Item {
        private Long itemId;
        private Integer acceptedQuantity;
        private RmaDisposition disposition;
    }
}
