package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.ReturnClassification;
import com.jhg.wms.domain.RmaDisposition;
import com.jhg.wms.domain.RmaReturn;
import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.ReturnClassificationService;
import com.jhg.wms.service.RmaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RmaAdminController.class)
@Import({SecurityConfig.class, AdminDataAccessAdvice.class})
class RmaAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RmaService rmaService;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean ReturnClassificationService classificationService;
    @MockitoBean DbUserDetailsService userDetailsService;

    // 행 전체 클릭은 tr의 data-href에 의존한다 — 이 속성이 빠지면 클릭이 조용히 죽으므로 렌더링을 검증한다.
    @Test
    void 반품목록의_각_행은_상세_링크를_data_href로_들고_있다() throws Exception {
        RmaReturn rma = RmaReturn.create("RMA-100-1", 100L, "상품 불량");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 7L);
        when(rmaService.findAll(any())).thenReturn(List.of(rma));
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/returns").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/returns"))
                .andExpect(content().string(containsString("data-href=\"/admin/returns/7\"")))
                .andExpect(content().string(containsString("<a href=\"/admin/returns/7\"")));
    }

    // th:switch의 case를 하나라도 틀리면 조용히 '—'로 렌더링된다 — 값이 아니라 한글 라벨이 나오는지 본다.
    @Test
    void 완료된_반품_상세는_처분을_한글로_보여준다() throws Exception {
        RmaReturn rma = RmaReturn.create("RMA-100-1", 100L, "상품 불량");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 7L);
        rma.receive();
        rma.getItems().get(0).inspect(2, RmaDisposition.RESTOCKED);
        rma.complete();
        when(rmaService.findById(7L)).thenReturn(rma);
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/returns/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("재입고")))
                .andExpect(content().string(not(containsString("RESTOCKED"))));
    }

    // 검수 완료는 되돌릴 수 없다 — 손대지 않고 제출하면 전량 거절로 확정되던 기본값이 다시 생기지 않게 고정한다.
    @Test
    void 검수폼은_승인수량과_처분에_기본값을_두지_않는다() throws Exception {
        RmaReturn rma = RmaReturn.create("RMA-100-1", 100L, "상품 불량");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 7L);
        rma.receive();
        when(rmaService.findById(7L)).thenReturn(rma);
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/returns/7").with(user("mgr").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("선택하세요")))
                .andExpect(content().string(not(containsString("value=\"0\""))));
    }

    // 서버 인가는 SecurityConfig가 막지만, 눌러야 403을 알게 되는 버튼은 그 자체로 결함이다.
    @Test
    void OPERATOR에게는_입고_취소_버튼이_보이지_않는다() throws Exception {
        RmaReturn rma = RmaReturn.create("RMA-100-1", 100L, "상품 불량");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 7L);
        when(rmaService.findById(7L)).thenReturn(rma);
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/returns/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("rmaReceive"))))
                .andExpect(content().string(not(containsString("rmaCancel"))));
    }

    @Test
    void OPERATOR에게는_검수_완료_폼이_보이지_않는다() throws Exception {
        RmaReturn rma = RmaReturn.create("RMA-100-1", 100L, "상품 불량");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 7L);
        rma.receive();
        when(rmaService.findById(7L)).thenReturn(rma);
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));

        mockMvc.perform(get("/admin/returns/7").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("rmaComplete"))))
                .andExpect(content().string(not(containsString("선택하세요"))));
    }

    // required는 브라우저만 막는다 — curl·자동화가 그대로 통과하면 안 된다.
    @Test
    void 승인수량이_비어_있으면_검수를_확정하지_않는다() throws Exception {
        mockMvc.perform(post("/admin/returns/7/complete")
                        .with(user("mgr").roles("MANAGER")).with(csrf())
                        .param("items[0].itemId", "1")
                        .param("items[0].disposition", "RESTOCKED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        verify(rmaService, never()).complete(any(), anyMap());
    }

    @Test
    void 승인수량과_처분이_모두_있으면_검수가_확정된다() throws Exception {
        mockMvc.perform(post("/admin/returns/7/complete")
                        .with(user("mgr").roles("MANAGER")).with(csrf())
                        .param("items[0].itemId", "1")
                        .param("items[0].acceptedQuantity", "2")
                        .param("items[0].disposition", "RESTOCKED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/returns/7"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(rmaService).complete(eq(7L), anyMap());
    }

    // DB 계층 예외는 업무 예외가 아니라 컨트롤러 catch에 안 걸린다 — 흰 500 대신 목록으로 돌려보내는지 검증.
    @Test
    void 검수완료중_DB오류가_나면_500대신_목록으로_돌아간다() throws Exception {
        doThrow(new DataIntegrityViolationException("Value not permitted for column"))
                .when(rmaService).complete(eq(1L), anyMap());

        mockMvc.perform(post("/admin/returns/1/complete")
                        .with(user("mgr").roles("MANAGER")).with(csrf())
                        .param("items[0].itemId", "1")
                        .param("items[0].acceptedQuantity", "1")
                        .param("items[0].disposition", "RESTOCKED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/returns"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 없는_RMA_상세는_500이_아니라_404_화면이다() throws Exception {
        when(rmaService.findById(999L)).thenThrow(new RmaService.RmaNotFoundException(999L));

        mockMvc.perform(get("/admin/returns/999").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    // 조회는 되돌릴 곳이 자기 자신이라 리다이렉트하면 무한 루프다 — 화면을 직접 그리는지 고정한다.
    @Test
    void 반품목록_DB오류는_자기자신으로_리다이렉트하지_않는다() throws Exception {
        when(rmaService.findAll(any()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(get("/admin/returns").with(user("op").roles("OPERATOR")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"));
    }

    private RmaReturn receivedRma() {
        RmaReturn rma = RmaReturn.create("RMA-100-9", 100L, "받았는데 모서리가 깨져 있어요");
        rma.addItem(501L, 1L, 2);
        ReflectionTestUtils.setField(rma, "id", 9L);
        ReflectionTestUtils.setField(rma.getItems().get(0), "id", 91L);
        rma.receive();
        return rma;
    }

    private void stubDetail(RmaReturn rma, ReturnClassification classification) {
        when(rmaService.findById(9L)).thenReturn(rma);
        when(inventoryService.findAllRows()).thenReturn(
                List.of(new InventoryRowResponse(1L, "상품 1", 10, 3, 7)));
        when(classificationService.findByRmaId(9L))
                .thenReturn(Optional.ofNullable(classification));
    }

    @Test
    void 분류가_있으면_한글_라벨로_참고영역을_렌더한다() throws Exception {
        stubDetail(receivedRma(), ReturnClassification.create(9L, ReturnCategory.DAMAGED,
                Confidence.HIGH, "모서리가 깨져 있어요", RmaDisposition.DISPOSED,
                "claude-haiku-4-5", 400, 120));

        mockMvc.perform(get("/admin/returns/9").with(user("mgr").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("분류 제안")))
                .andExpect(content().string(containsString("파손")))
                .andExpect(content().string(containsString("높음")))
                .andExpect(content().string(containsString("모서리가 깨져 있어요")))
                .andExpect(content().string(containsString("폐기")))
                // enum 원문을 화면에 흘리지 않는다
                .andExpect(content().string(not(containsString("DAMAGED"))));
    }

    // 빈 껍데기를 두지 않는다 — 분류가 없으면 영역 자체가 없어야 한다.
    @Test
    void 분류가_없으면_참고영역을_렌더하지_않는다() throws Exception {
        stubDetail(receivedRma(), null);

        mockMvc.perform(get("/admin/returns/9").with(user("mgr").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("분류 제안"))))
                .andExpect(content().string(not(containsString("참고용입니다"))));
    }

    // V2.1 원칙: 되돌릴 수 없는 확정에 기본값을 두지 않는다. AI 제안도 예외가 아니다.
    @Test
    void 분류가_있어도_검수_입력칸은_여전히_비어_있다() throws Exception {
        stubDetail(receivedRma(), ReturnClassification.create(9L, ReturnCategory.DAMAGED,
                Confidence.HIGH, "모서리가 깨져 있어요", RmaDisposition.DISPOSED,
                "claude-haiku-4-5", 400, 120));

        String html = mockMvc.perform(get("/admin/returns/9").with(user("mgr").roles("MANAGER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 승인 수량 input에 value가 채워지면 안 된다.
        // 속성 순서에 기대지 않도록 <input ...> 태그 전체를 뽑아서 확인한다
        // (value가 name보다 앞에 와도 놓치지 않는다).
        Pattern acceptedQuantityInputPattern = Pattern.compile(
                "<input[^>]*name=\"items\\[0\\]\\.acceptedQuantity\"[^>]*>");
        Matcher acceptedQuantityInputMatcher = acceptedQuantityInputPattern.matcher(html);
        assertThat(acceptedQuantityInputMatcher.find())
                .as("승인 수량 input이 렌더되어야 한다")
                .isTrue();
        assertThat(acceptedQuantityInputMatcher.group()).doesNotContain("value=");

        // 처분 select에서 선택된 것은 플레이스홀더뿐이어야 한다.
        // select 블록을 통째로 뽑은 뒤 그 안의 <option> 태그들을 개별로 검사한다 —
        // selected가 value보다 앞에 와도 놓치지 않는다.
        Pattern dispositionSelectPattern = Pattern.compile(
                "<select[^>]*name=\"items\\[0\\]\\.disposition\"[^>]*>(.*?)</select>",
                Pattern.DOTALL);
        Matcher dispositionSelectMatcher = dispositionSelectPattern.matcher(html);
        assertThat(dispositionSelectMatcher.find())
                .as("처분 select가 렌더되어야 한다")
                .isTrue();
        String dispositionSelectHtml = dispositionSelectMatcher.group(1);
        assertThat(dispositionSelectHtml)
                .contains("<option value=\"\" disabled selected>선택하세요</option>");

        Pattern optionPattern = Pattern.compile("<option[^>]*>");
        Matcher optionMatcher = optionPattern.matcher(dispositionSelectHtml);
        List<String> wronglySelectedOptions = new ArrayList<>();
        while (optionMatcher.find()) {
            String option = optionMatcher.group();
            boolean isPlaceholder = option.contains("value=\"\"");
            if (!isPlaceholder && option.contains("selected")) {
                wronglySelectedOptions.add(option);
            }
        }
        assertThat(wronglySelectedOptions).isEmpty();
    }
}
