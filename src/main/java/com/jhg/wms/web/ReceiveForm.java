package com.jhg.wms.web;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class ReceiveForm {
    private List<Item> items = new ArrayList<>();

    @Getter @Setter
    public static class Item {
        private Long itemId;
        private int quantity;
    }
}
