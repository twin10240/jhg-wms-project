package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.CycleCount;
import com.jhg.wms.service.CycleCountService;
import com.jhg.wms.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CycleCountAdminController.class)
@Import({SecurityConfig.class, AdminDataAccessAdvice.class})
class CycleCountAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CycleCountService cycleCountService;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean DbUserDetailsService userDetailsService;

    /** OPEN 상태 세션 — 실물 수량 입력 화면을 렌더한다. */
    private CycleCount open() {
        CycleCount c = CycleCount.open("operator", "8월 순환 실사");
        c.addItem(1L, 15);
        ReflectionTestUtils.setField(c, "id", 7L);
        ReflectionTestUtils.setField(c.getItems().get(0), "id", 101L);
        return c;
    }

    /** 제출자를 지정한 SUBMITTED 세션 — 자기승인 노출 여부를 보려면 제출자가 누구인지가 중요하다. */
    private CycleCount submittedBy(String actor) {
        CycleCount c = open();
        c.recordCount(101L, 14);
        c.submit(actor);
        return c;
    }

    // 이미 저장된 값이 있는 칸을 비우고 제출하면, 화면은 비어 있는데 예전 값으로 확정된다.
    // 제출은 되돌릴 수 없으므로 빈 칸을 건너뛰지 않고 거부해야 한다.
    @Test
    void 빈_칸을_남기고_제출하면_거부된다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/counts")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("action", "submit")
                        .param("items[0].itemId", "101"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        verify(cycleCountService, never()).saveCounts(anyLong(), any());
        verify(cycleCountService, never()).submit(anyLong());
    }

    // 저장만 할 때는 나눠 세는 것을 허용한다 — 빈 칸은 건너뛰고 입력된 것만 저장한다.
    @Test
    void 저장만_할_때는_빈_칸을_건너뛴다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/counts")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("items[0].itemId", "101"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        verify(cycleCountService).saveCounts(7L, Map.of());
        verify(cycleCountService, never()).submit(anyLong());
    }

    // 서비스가 자기승인을 거부하므로, 제출자에게 승인 버튼이 보이면 눌러야 알게 된다.
    @Test
    void 제출자_본인에게는_승인_버튼이_보이지_않는다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submittedBy("mgr"));
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("mgr").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("ccApprove"))))
                .andExpect(content().string(containsString("직접 제출한 실사는 승인할 수 없습니다")))
                .andExpect(content().string(containsString("ccReject")));   // 반려는 그대로 가능
    }

    @Test
    void 다른_관리자에게는_승인_버튼이_보인다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submittedBy("operator"));
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("mgr").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ccApprove")))
                .andExpect(content().string(not(containsString("직접 제출한 실사는"))));
    }

    private CycleCount submitted() {
        CycleCount c = CycleCount.open("operator", "8월 순환 실사");
        c.addItem(1L, 15);
        ReflectionTestUtils.setField(c, "id", 7L);
        ReflectionTestUtils.setField(c.getItems().get(0), "id", 101L);
        c.recordCount(101L, 14);
        c.submit("operator");
        return c;
    }

    @Test
    void 상세는_상태를_한글로_보여준다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submitted());
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("승인 대기")))
                .andExpect(content().string(not(containsString("SUBMITTED"))));
    }

    // 서버 인가로 막히지만, 눌러야 403을 알게 되는 버튼은 그 자체로 결함이다.
    @Test
    void OPERATOR에게는_승인_반려_버튼이_보이지_않는다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submitted());
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(content().string(not(containsString("ccApprove"))))
                .andExpect(content().string(not(containsString("ccReject"))));
    }

    @Test
    void OPERATOR가_승인을_직접_POST하면_403() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/approve")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().isForbidden());

        verify(cycleCountService, never()).approve(anyLong());
    }

    @Test
    void MANAGER는_승인할_수_있다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/approve")
                        .with(user("mgr").roles("MANAGER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/cycle-counts/7"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(cycleCountService).approve(7L);
    }

    @Test
    void 반려_사유는_모달_안에서_받는다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(submittedBy("operator"));
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        String html = mockMvc.perform(get("/admin/cycle-counts/7").with(user("mgr").roles("MANAGER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 사유 입력칸이 <dialog> 밖에 상시 노출되면 버튼과 떨어져 어디 적는지 보이지 않는다.
        assertThat(html).contains("<dialog id=\"ccRejectDialog\"");
        assertThat(html).contains("showModal()");
        assertThat(html.indexOf("name=\"reason\"")).isGreaterThan(html.indexOf("ccRejectDialog"));
    }

    @Test
    void OPERATOR가_반려를_직접_POST하면_403() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/reject")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("reason", "계수 오류"))
                .andExpect(status().isForbidden());

        verify(cycleCountService, never()).reject(anyLong(), any());
    }

    @Test
    void MANAGER는_반려할_수_있다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/reject")
                        .with(user("mgr").roles("MANAGER")).with(csrf())
                        .param("reason", "계수 오류"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/cycle-counts/7"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(cycleCountService).reject(7L, "계수 오류");
    }

    @Test
    void 승인된_세션은_품목별_반영_결과를_보여준다() throws Exception {
        CycleCount approved = submitted();
        approved.approve("manager");
        when(cycleCountService.findById(7L)).thenReturn(approved);
        when(cycleCountService.appliedDeltas(7L)).thenReturn(java.util.Map.of(1L, -1));
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 14, 0, 14)));

        mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("반영 결과")))
                .andExpect(content().string(containsString("-1")));
    }

    @Test
    void 없는_실사_상세는_500이_아니라_404_화면이다() throws Exception {
        when(cycleCountService.findById(999L))
                .thenThrow(new CycleCountService.CycleCountNotFoundException(999L));

        mockMvc.perform(get("/admin/cycle-counts/999").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void 겹치는_세션_생성은_에러메시지로_돌아간다() throws Exception {
        when(cycleCountService.open(any(), any()))
                .thenThrow(new IllegalStateException("이미 진행 중인 실사에 포함된 상품입니다. productId=[1]"));

        mockMvc.perform(post("/admin/cycle-counts")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("productIds", "1").param("memo", "겹치는 실사"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    // I-1: 제출 버튼은 저장 폼(ccCounts)의 action=submit 제출자다 — 화면의 값을 건너뛰고
    // 예전 값으로 확정되는 경로가 없어야 한다. 값이 먼저 저장된 뒤에야 제출이 일어나는지 본다.
    @Test
    void 제출을_누르면_화면의_값이_먼저_저장된_뒤_제출된다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/counts")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("items[0].itemId", "101")
                        .param("items[0].countedQty", "9")
                        .param("action", "submit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/cycle-counts/7"))
                .andExpect(flash().attributeExists("successMessage"));

        InOrder order = inOrder(cycleCountService);
        order.verify(cycleCountService).saveCounts(eq(7L), eq(Map.of(101L, 9)));
        order.verify(cycleCountService).submit(7L);
    }

    // action 파라미터가 없는 일반 저장은 제출로 이어지지 않는다.
    @Test
    void 저장만_누르면_제출되지_않는다() throws Exception {
        mockMvc.perform(post("/admin/cycle-counts/7/counts")
                        .with(user("op").roles("OPERATOR")).with(csrf())
                        .param("items[0].itemId", "101")
                        .param("items[0].countedQty", "9"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/cycle-counts/7"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(cycleCountService).saveCounts(eq(7L), eq(Map.of(101L, 9)));
        verify(cycleCountService, never()).submit(anyLong());
    }

    // I-2: 목록 화면 렌더 — 상태 한글 표시와 진행률 표현식이 실제로 평가되는지 고정한다.
    @Test
    void 목록은_상태를_한글로_보여주고_진행률을_계산한다() throws Exception {
        CycleCount c = CycleCount.open("operator", "8월 순환 실사");
        c.addItem(1L, 15);
        c.addItem(2L, 30);
        ReflectionTestUtils.setField(c, "id", 7L);
        ReflectionTestUtils.setField(c.getItems().get(0), "id", 101L);
        ReflectionTestUtils.setField(c.getItems().get(1), "id", 102L);
        c.recordCount(101L, 14);   // 품목 2개 중 1개만 입력됨

        when(cycleCountService.findAll(any())).thenReturn(List.of(c));

        mockMvc.perform(get("/admin/cycle-counts").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("작성 중")))
                // 탭 링크(?status=OPEN)에는 값이 남아 있어도 된다 — 배지 안에는 한글만 있어야 한다.
                .andExpect(content().string(not(containsString(">OPEN<"))))
                .andExpect(content().string(containsString("1 / 2")));
    }

    // 행 클릭이 의존하는 data-href가 렌더되는지만 본다. 클릭이 실제로 동작하는지는
    // 브라우저 없이 확인할 수 없어 이 테스트의 범위가 아니다 — 스크립트가 빠지면 이 테스트는 통과한 채
    // 커서만 손가락 모양이고 클릭은 죽는다.
    @Test
    void 목록의_각_행은_상세_링크를_data_href로_들고_있다() throws Exception {
        CycleCount c = CycleCount.open("operator", "8월 순환 실사");
        c.addItem(1L, 15);
        ReflectionTestUtils.setField(c, "id", 7L);
        when(cycleCountService.findAll(any())).thenReturn(List.of(c));

        mockMvc.perform(get("/admin/cycle-counts").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-href=\"/admin/cycle-counts/7\"")));
    }

    // 상세 화면 테스트가 전부 SUBMITTED 세션을 써서, 입력 폼과 저장·제출 버튼은 한 번도 렌더되지 않았다.
    // 하필 여기가 실제로 사고가 났던 자리다 — 제출 버튼이 빈 폼을 가리켜 화면 입력이 버려졌다.
    @Test
    void 작성중_상세는_제출_버튼이_입력_폼을_가리킨다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(open());
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 13, 0, 13)));

        String html = mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("작성 중")))
                .andReturn().getResponse().getContentAsString();

        // 저장과 제출이 같은 폼을 쓴다 — 제출이 화면 값을 건너뛸 수 없다는 것이 이 화면의 계약이다.
        assertThat(html).containsPattern("form=\"ccCounts\"[^>]*>실물 수량 저장<");
        assertThat(html).containsPattern("form=\"ccCounts\"[^>]*name=\"action\"[^>]*value=\"submit\"");
        assertThat(html).doesNotContain("ccSubmit");   // 저장을 건너뛰던 옛 경로
    }

    // 미입력과 0("세어보니 없었다")을 구분해야 안 센 품목이 조용히 확정되지 않는다.
    @Test
    void 작성중_상세의_실물_수량칸에는_기본값이_없다() throws Exception {
        when(cycleCountService.findById(7L)).thenReturn(open());
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 13, 0, 13)));

        String html = mockMvc.perform(get("/admin/cycle-counts/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 입력칸 태그만 잘라서 본다 — 페이지 전체에서 문자열을 찾으면 다른 태그에 우연히 매칭된다.
        int start = html.indexOf("name=\"items[0].countedQty\"");
        assertThat(start).as("실물 수량 입력칸이 렌더돼야 한다").isNotNegative();
        String input = html.substring(start, html.indexOf('>', start));
        // Thymeleaf는 null인 th:value를 value=""로 렌더한다(속성 생략 아님). 빈 값은 정상이고,
        // 값이 들어 있으면 담당자가 세지 않은 수량이 기본값으로 확정된다.
        assertThat(input).as("갓 연 세션의 입력칸은 비어 있어야 한다")
                .doesNotContainPattern("value=\"[^\"]+\"");
        // 세션 시작 장부(15)와 현재 장부(13)를 함께 보여준다 — 실사 중 이동을 눈으로 확인하는 자리다.
        assertThat(html).contains(">15<").contains(">13<");
    }

    // I-2: 생성 화면에 렌더 테스트가 없었다 — 대상 후보 목록이 실제로 그려지는지 고정한다.
    @Test
    void 생성_화면은_재고_행을_후보로_보여준다() throws Exception {
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 15, 0, 15)));

        mockMvc.perform(get("/admin/cycle-counts/new").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("상품 1")))
                .andExpect(content().string(containsString("name=\"productIds\"")));
    }
}
