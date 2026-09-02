package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.ReturnOwnerArea;
import com.jhg.wms.service.ReturnAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/** 는 apiChain(basic·CSRF 비활성·401)에 걸린다 — 모든 호출에 httpBasic("wms","wms").
// SecurityConfig가 webChain도 등록하고 webChain이 DbUserDetailsService를 요구하므로
// 슬라이스 컨텍스트 로딩용 목빈이 필요하다(직접 호출되지는 않는다).
@WebMvcTest(ReturnAnalyticsController.class)
@Import(SecurityConfig.class)
class ReturnAnalyticsControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Autowired MockMvc mockMvc;
    @MockitoBean ReturnAnalyticsService returnAnalyticsService;
    @MockitoBean DbUserDetailsService userDetailsService;

    @Test
    void 반품률_보고서를_그대로_낸다() throws Exception {
        var row = new ReturnAnalyticsService.ProductReturnRate(11L, "상품 11", 50, 7, 0.14);
        when(returnAnalyticsService.productReturnRates(FROM, TO))
                .thenReturn(new ReturnAnalyticsService.ReturnRateReport(FROM, TO, 2, List.of(row), 3));

        mockMvc.perform(get("/api/analytics/product-return-rates")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                // 날짜는 ISO 문자열이어야 한다. 타임스탬프로 나가면 MCP 서버가 다시 파싱해야 한다.
                .andExpect(jsonPath("$.from").value("2026-08-01"))
                .andExpect(jsonPath("$.to").value("2026-08-31"))
                .andExpect(jsonPath("$.observedDays").value(2))
                // 관찰 경과일과 주문 연결 불가 출고 수는 보고서가 분모를 밝히는 근거다. 빠지면 안 된다.
                .andExpect(jsonPath("$.unlinkedShipRows").value(3))
                .andExpect(jsonPath("$.rows[0].productId").value(11))
                .andExpect(jsonPath("$.rows[0].productName").value("상품 11"))
                .andExpect(jsonPath("$.rows[0].shippedQty").value(50))
                .andExpect(jsonPath("$.rows[0].returnedQty").value(7))
                .andExpect(jsonPath("$.rows[0].returnRate").value(0.14));
    }

    @Test
    void 범주_분해를_그대로_낸다() throws Exception {
        var damaged = new ReturnAnalyticsService.CategoryCount(
                ReturnCategory.DAMAGED, ReturnOwnerArea.PACKAGING, 4);
        var mind = new ReturnAnalyticsService.CategoryCount(
                ReturnCategory.CHANGED_MIND, ReturnOwnerArea.PRODUCT_INFO, 12);
        when(returnAnalyticsService.categoryBreakdown(FROM, TO))
                .thenReturn(new ReturnAnalyticsService.CategoryBreakdown(List.of(damaged, mind), 5, 21));

        mockMvc.perform(get("/api/analytics/return-categories")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                // enum은 name()으로 나가야 한다. 한글 label()로 나가면 모델이 범주를 문자열로 비교하지 못한다.
                .andExpect(jsonPath("$.counts[0].category").value("DAMAGED"))
                .andExpect(jsonPath("$.counts[0].ownerArea").value("PACKAGING"))
                .andExpect(jsonPath("$.counts[0].count").value(4))
                .andExpect(jsonPath("$.counts[1].category").value("CHANGED_MIND"))
                // 미분류와 전체는 분모를 밝히는 값이다 — Skill이 "미분류를 반드시 밝힌다"를 지키려면 있어야 한다.
                .andExpect(jsonPath("$.unclassified").value(5))
                .andExpect(jsonPath("$.totalReturns").value(21));
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/analytics/product-return-rates")
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void from이_없으면_400이다() throws Exception {
        // 기본값을 두지 않는다는 결정을 여기서 고정한다. 화면과 달리 보고서는 분모가 분명해야 한다.
        mockMvc.perform(get("/api/analytics/product-return-rates")
                        .with(httpBasic("wms", "wms"))
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                // 본문은 평문이고 모델이 스스로 고칠 수 있어야 한다 — 어느 파라미터가 필요한지 이름을 박는다.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("from")));
    }

    @Test
    void 상품_상세를_그대로_낸다() throws Exception {
        var row = new ReturnAnalyticsService.ReturnDetailRow(
                203L, 5001L, 11L, "상품 11", 2, "송장은 제 이름인데 다른 물건이 왔어요",
                ReturnCategory.WRONG_ITEM, Confidence.MEDIUM);
        when(returnAnalyticsService.detailsByProduct(11L, FROM, TO)).thenReturn(List.of(row));

        mockMvc.perform(get("/api/analytics/return-details/product/11")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rmaReturnId").value(203))
                .andExpect(jsonPath("$[0].orderId").value(5001))
                .andExpect(jsonPath("$[0].productId").value(11))
                .andExpect(jsonPath("$[0].productName").value("상품 11"))
                .andExpect(jsonPath("$[0].requestedQuantity").value(2))
                // 사유 원문이 이 도구의 존재 이유다. 빠지면 모델이 해석할 것이 없다.
                .andExpect(jsonPath("$[0].reason").value("송장은 제 이름인데 다른 물건이 왔어요"))
                .andExpect(jsonPath("$[0].category").value("WRONG_ITEM"))
                .andExpect(jsonPath("$[0].confidence").value("MEDIUM"));
    }

    @Test
    void 미분류_행은_category와_confidence가_null이다() throws Exception {
        // 분류는 V4.0부터 붙어서 그 이전 반품에는 없다. null이 사라지면 모델이 미분류를 못 센다.
        var row = new ReturnAnalyticsService.ReturnDetailRow(
                140L, 4002L, 9L, "상품 9", 1, "V2-0 재신청", null, null);
        when(returnAnalyticsService.detailsByProduct(9L, FROM, TO)).thenReturn(List.of(row));

        mockMvc.perform(get("/api/analytics/return-details/product/9")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").doesNotExist())
                .andExpect(jsonPath("$[0].confidence").doesNotExist())
                .andExpect(jsonPath("$[0].reason").value("V2-0 재신청"));
    }

    @Test
    void 범주_상세를_그대로_낸다() throws Exception {
        var row = new ReturnAnalyticsService.ReturnDetailRow(
                211L, 5010L, 17L, "상품 17", 1, "박스가 찌그러져 왔습니다",
                ReturnCategory.DAMAGED, Confidence.HIGH);
        when(returnAnalyticsService.detailsByCategory(ReturnCategory.DAMAGED, FROM, TO))
                .thenReturn(List.of(row));

        mockMvc.perform(get("/api/analytics/return-details/category/DAMAGED")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rmaReturnId").value(211))
                .andExpect(jsonPath("$[0].category").value("DAMAGED"))
                .andExpect(jsonPath("$[0].confidence").value("HIGH"));
    }

    @Test
    void UNCLASSIFIED는_null_범주로_위임된다() throws Exception {
        // enum에 없는 값이다. 그래도 이 이름으로 받는 이유는 다섯 칸이 같은 URL 모양으로
        // 열려야 모델이 분기 없이 순회할 수 있기 때문이다.
        var row = new ReturnAnalyticsService.ReturnDetailRow(
                140L, 4002L, 9L, "상품 9", 1, "통합검증", null, null);
        when(returnAnalyticsService.detailsByCategory(null, FROM, TO)).thenReturn(List.of(row));

        mockMvc.perform(get("/api/analytics/return-details/category/UNCLASSIFIED")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rmaReturnId").value(140))
                .andExpect(jsonPath("$[0].category").doesNotExist());

        // 응답만 보면 "빈 목록"과 구분이 안 된다. 위임 인자가 null인지 직접 못박는다.
        verify(returnAnalyticsService).detailsByCategory(null, FROM, TO);
    }

    @Test
    void 날짜_형식이_틀리면_400이다() throws Exception {
        // 모델이 스스로 고칠 수 있어야 한다 — 500이면 무엇을 고쳐야 할지 알 수 없다.
        mockMvc.perform(get("/api/analytics/return-details/product/11")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-8-1").param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                // 파라미터 이름과 기대 형식(YYYY-MM-DD)을 본문에 박아 재시도 없이 스스로 고치게 한다.
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("from"),
                        org.hamcrest.Matchers.containsString("YYYY-MM-DD"))));
    }

    @Test
    void 역전된_범위는_400이고_500이_아니다() throws Exception {
        // 서비스의 cohort()가 실제로 던지는 예외다. 매핑이 없으면 500이 되고,
        // 모델은 "내 인자가 틀렸다"와 "창고가 고장났다"를 구분하지 못한다.
        doThrow(new IllegalArgumentException("시작일이 종료일보다 뒤입니다."))
                .when(returnAnalyticsService).productReturnRates(TO, FROM);

        mockMvc.perform(get("/api/analytics/product-return-rates")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-31").param("to", "2026-08-01"))
                .andExpect(status().isBadRequest())
                // 본문은 평문이다(README의 기존 API 오류 계약). 무엇을 고쳐야 할지 읽혀야 한다.
                .andExpect(content().string("시작일이 종료일보다 뒤입니다."));
    }

    @Test
    void 알_수_없는_범주_이름은_400이다() throws Exception {
        // ReturnCategory.valueOf가 IllegalArgumentException을 던진다. 같은 핸들러가 받는다.
        mockMvc.perform(get("/api/analytics/return-details/category/NOPE")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                // FQCN("No enum constant com.jhg...")을 그대로 흘리지 않는다 — 받는 다섯 값을 이름으로 준다.
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.jhg")),
                        org.hamcrest.Matchers.containsString("DAMAGED"),
                        org.hamcrest.Matchers.containsString("WRONG_ITEM"),
                        org.hamcrest.Matchers.containsString("CHANGED_MIND"),
                        org.hamcrest.Matchers.containsString("OTHER"),
                        org.hamcrest.Matchers.containsString("UNCLASSIFIED"))));
    }

    @Test
    void 결과가_비어도_200이다() throws Exception {
        // 빈 결과와 오류를 섞으면 모델이 "반품이 없다"를 실패로 읽거나 그 반대가 된다.
        when(returnAnalyticsService.detailsByProduct(99L, FROM, TO)).thenReturn(List.of());

        mockMvc.perform(get("/api/analytics/return-details/product/99")
                        .with(httpBasic("wms", "wms"))
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
