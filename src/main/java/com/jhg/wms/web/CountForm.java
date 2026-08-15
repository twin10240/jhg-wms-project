package com.jhg.wms.web;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 실사 실물 수량 입력 폼. countedQty는 Integer — 미입력(null)과 0을 구분해야 한다. */
@Getter @Setter
public class CountForm {
    private List<Item> items = new ArrayList<>();

    @Getter @Setter
    public static class Item {
        private Long itemId;
        private Integer countedQty;
    }
}
