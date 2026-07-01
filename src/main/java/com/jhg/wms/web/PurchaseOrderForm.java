package com.jhg.wms.web;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class PurchaseOrderForm {
    private List<Item> items = new ArrayList<>();
    private String memo;

    @Getter @Setter
    public static class Item {
        private Long productId;
        private int quantity;
    }
}
