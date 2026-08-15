package com.jhg.wms.web;

import com.jhg.wms.domain.RmaDisposition;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class InspectionForm {
    private List<Item> items = new ArrayList<>();

    @Getter @Setter
    public static class Item {
        private Long itemId;
        private int acceptedQuantity;
        private RmaDisposition disposition;
    }
}
