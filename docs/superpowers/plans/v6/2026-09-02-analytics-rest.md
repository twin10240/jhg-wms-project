# 반품 분석 REST (V6.0a) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ReturnAnalyticsService`의 조회 넷을 `/api/analytics/**` REST로 노출한다. 별도 프로세스로 뜰 Python MCP 서버(V6.0b)가 이것을 부른다.

**Architecture:** `ReturnAnalyticsController` 하나를 새로 만든다. 로직은 한 줄도 없다 — 서비스에 위임하고 레코드를 그대로 직렬화한다. 경로를 `/api/**` 안에 두면 기존 `apiChain`(basic 인증·CSRF 비활성·401 직접 응답)에 그대로 걸리므로 `SecurityConfig`를 건드리지 않는다. 응답 타입은 서비스가 이미 내놓는 `record`들이라 DTO를 새로 만들지 않는다.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring MVC, JUnit 5 + MockMvc(`@WebMvcTest` 슬라이스), Mockito, AssertJ, Gradle.

## Global Constraints

- **`SecurityConfig`를 수정하지 않는다.** 경로를 `/api/**` 안에 두는 것으로 끝낸다.
- **경로 접두사는 `/api/analytics`.** 다른 접두사를 쓰면 `apiChain`의 `securityMatcher("/api/**")`에 안 걸려 폼 로그인 체인으로 떨어진다.
- **`from`·`to`는 필수다. 기본값을 두지 않는다.** 화면(`WmsAdminController`)은 "안 넣으면 최근 30일"을 쓰지만 여기서는 쓰지 않는다 — 보고서는 분모가 분명해야 한다.
- **계산하지 않는다. 위임만 한다.** 컨트롤러 안에 집계·필터·환산이 들어가면 코호트 정의가 둘이 된다. 이 저장소가 내내 막아온 실패다.
- **읽기 전용.** `ReturnAnalyticsService`만 주입한다. `InventoryService`·`RmaService`는 이 컨트롤러 근처에 두지 않는다.
- **오류 본문은 평문(plain text).** README의 기존 API 오류 계약이 그렇다(`ResponseEntity.badRequest().body(e.getMessage())`).
- **응답 DTO를 새로 만들지 않는다.** `ReturnRateReport`·`CategoryBreakdown`·`ReturnDetailRow`를 그대로 낸다.
- **OMS 계약(README의 S1~S7 채널 표)에 넣지 않는다.** 이 넷은 내부 도구용이고 OMS는 부르지 않는다.
- 커밋 메시지는 한글, `feat(wms):`/`docs(wms):` 형식, 본문에 "왜 이 선택인가", 트레일러 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- 테스트는 실제 PostgreSQL 17이 떠 있어야 한다. 실행: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
- 브랜치: `feat/wms-mcp-server` (스펙·계획서가 이미 여기 있다). 새 브랜치를 만들지 않는다.
- **기준선: master 415건 그린.**

---

## File Structure

| 파일 | 책임 |
|---|---|
| `src/main/java/com/jhg/wms/web/ReturnAnalyticsController.java` | **생성.** `/api/analytics/**` 넷. 위임과 직렬화, 그리고 `IllegalArgumentException` → 400 매핑 |
| `src/test/java/com/jhg/wms/web/ReturnAnalyticsControllerTest.java` | **생성.** `@WebMvcTest` 슬라이스. 서비스는 목 |
| `README.md` | **수정.** `## API` 아래에 내부 도구용 절 추가, 테스트 수 현행화 |

새 파일이 둘뿐인 이유: 이 작업에 새 도메인 개념이 없다. 응답 타입도 서비스가 이미 갖고 있다.

## 서비스 API — 이미 존재하는 것 (읽기만 하고 고치지 않는다)

```java
// com.jhg.wms.service.ReturnAnalyticsService
public record ProductReturnRate(Long productId, String productName,
                                int shippedQty, int returnedQty, double returnRate) {}
public record ReturnRateReport(LocalDate from, LocalDate to, int observedDays,
                               List<ProductReturnRate> rows, int unlinkedShipRows) {}
public record CategoryCount(ReturnCategory category, ReturnOwnerArea ownerArea, int count) {}
public record CategoryBreakdown(List<CategoryCount> counts, int unclassified, int totalReturns) {}
public record ReturnDetailRow(Long rmaReturnId, Long orderId, Long productId, String productName,
                              int requestedQuantity, String reason,
                              ReturnCategory category, Confidence confidence) {}

public ReturnRateReport productReturnRates(LocalDate from, LocalDate to);
public CategoryBreakdown categoryBreakdown(LocalDate from, LocalDate to);
public List<ReturnDetailRow> detailsByProduct(Long productId, LocalDate from, LocalDate to);
public List<ReturnDetailRow> detailsByCategory(ReturnCategory category, LocalDate from, LocalDate to);
```

**`detailsByCategory(null, ...)`는 미분류만 낸다.** enum에 `UNCLASSIFIED`는 없다.

enum 값: `ReturnCategory` = `DAMAGED`·`WRONG_ITEM`·`CHANGED_MIND`·`OTHER`,
`ReturnOwnerArea` = `PICKING`·`PACKAGING`·`PRODUCT_INFO`·`OUTSIDE`,
`Confidence` = `HIGH`·`MEDIUM`·`LOW`.

**직렬화 기본값 확인됨**: `application.yml`에 jackson 설정이 없고 enum에 `@JsonValue`가 없다.
→ `LocalDate`는 ISO 문자열(`"2026-08-01"`), enum은 `name()`(`"DAMAGED"`)로 나간다.

---

### Task 1: 집계 REST 둘 — 반품률과 범주 분해

**Files:**
- Create: `src/main/java/com/jhg/wms/web/ReturnAnalyticsController.java`
- Create: `src/test/java/com/jhg/wms/web/ReturnAnalyticsControllerTest.java`

**Interfaces:**
- Consumes: `ReturnAnalyticsService.productReturnRates(LocalDate, LocalDate)`, `.categoryBreakdown(LocalDate, LocalDate)` (기존)
- Produces: `GET /api/analytics/product-return-rates?from&to` → `ReturnRateReport` JSON,
  `GET /api/analytics/return-categories?from&to` → `CategoryBreakdown` JSON.
  Task 2·3이 같은 컨트롤러 클래스에 메서드를 더한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/jhg/wms/web/ReturnAnalyticsControllerTest.java`:

```java
package com.jhg.wms.web;

import com.jhg.wms.config.DbUserDetailsService;
import com.jhg.wms.config.SecurityConfig;
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

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests 'com.jhg.wms.web.ReturnAnalyticsControllerTest'
```
Expected: **컴파일 실패** — `ReturnAnalyticsController` 심볼을 찾을 수 없다.

- [ ] **Step 3: 최소 구현을 쓴다**

`src/main/java/com/jhg/wms/web/ReturnAnalyticsController.java`:

```java
package com.jhg.wms.web;

import com.jhg.wms.service.ReturnAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 반품 분석 조회 REST. V6.0b의 Python MCP 서버가 이것을 부른다.
 *
 * 계산하지 않는다 — 서비스에 위임하고 레코드를 그대로 직렬화할 뿐이다. 여기에 집계를
 * 한 줄이라도 넣으면 화면과 보고서가 다른 숫자를 낼 수 있게 되고, 이 설계 전체가 그것을 막아왔다.
 *
 * 읽기 전용이다. 반품 사유는 고객이 쓴 자유 텍스트이고 그것이 모델 컨텍스트로 들어간다.
 * 쓰기 서비스를 여기 주입하면 고객이 창고 데이터를 건드릴 경로가 열린다.
 *
 * 경로가 /api/** 안인 것은 의도다 — apiChain이 basic 인증·CSRF 비활성·401 직접 응답을
 * 이미 갖고 있어 SecurityConfig를 고치지 않는다. 다른 접두사로 옮기면 폼 로그인 체인으로 떨어진다.
 *
 * from·to에 기본값을 두지 않는다. 화면은 "안 넣으면 최근 30일"이 친절하지만,
 * 보고서는 분모가 무엇인지 분명해야 한다.
 */
@RestController
@RequestMapping("/api/analytics")
public class ReturnAnalyticsController {

    private final ReturnAnalyticsService returnAnalyticsService;

    public ReturnAnalyticsController(ReturnAnalyticsService returnAnalyticsService) {
        this.returnAnalyticsService = returnAnalyticsService;
    }

    @GetMapping("/product-return-rates")
    public ReturnAnalyticsService.ReturnRateReport productReturnRates(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return returnAnalyticsService.productReturnRates(from, to);
    }

    @GetMapping("/return-categories")
    public ReturnAnalyticsService.CategoryBreakdown returnCategories(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return returnAnalyticsService.categoryBreakdown(from, to);
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests 'com.jhg.wms.web.ReturnAnalyticsControllerTest'
```
Expected: **PASS, 4건.**

- [ ] **Step 5: 변이 검증 — 테스트에 이빨이 있는지 확인한다**

두 가지를 각각 바꿔보고 **실패하는지** 본다. 확인 후 반드시 원복한다.

1. `@RequestMapping("/api/analytics")`를 `@RequestMapping("/analytics")`로 바꾼다
   → 네 테스트 전부 실패해야 한다(404 또는 302). 경로가 `/api/**` 밖으로 나가면
   `apiChain`에 안 걸린다는 사실이 테스트로 고정돼 있는지 보는 것이다.
2. `productReturnRates`의 `to` 파라미터에 `defaultValue`를 붙인다
   (`@RequestParam(defaultValue = "2026-01-01")`) 뒤 `from`에도 붙인다
   → `from이_없으면_400이다`가 실패해야 한다.

원복 후 다시 실행해 4건 그린을 확인한다.

- [ ] **Step 6: 전체 테스트를 돌린다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: **419건 그린**(415 + 4), 실패 0.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/ReturnAnalyticsController.java \
        src/test/java/com/jhg/wms/web/ReturnAnalyticsControllerTest.java
git commit -F - <<'MSG'
feat(wms): 반품 분석 집계 REST 둘을 연다 (V6.0a)

V6.0b의 Python MCP 서버가 부를 표면이다. 계산은 하지 않고 ReturnAnalyticsService에
위임만 한다 — 여기서 집계하면 화면과 보고서가 다른 숫자를 낼 수 있게 된다.

경로를 /api/analytics로 둔 것은 의도다. apiChain이 basic·CSRF 비활성·401을 이미
갖고 있어 SecurityConfig를 한 줄도 고치지 않는다. 다른 접두사면 폼 로그인 체인으로
떨어져 POST가 302되고 클라이언트는 원인을 알 수 없다.

from·to에 기본값을 두지 않는다. 화면은 최근 30일로 열어주지만 보고서는 분모가
무엇인지 분명해야 하고, 숨은 기본값은 모델도 사람도 어느 기간을 잰 것인지 모르게 한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 2: 상세 REST 둘 — 상품 축과 범주 축

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/ReturnAnalyticsController.java` (메서드 둘 추가)
- Modify: `src/test/java/com/jhg/wms/web/ReturnAnalyticsControllerTest.java` (테스트 다섯 추가)

**Interfaces:**
- Consumes: Task 1의 `ReturnAnalyticsController`, 상수 `FROM`·`TO`, 목빈 `returnAnalyticsService`
- Produces: `GET /api/analytics/return-details/product/{productId}?from&to` → `List<ReturnDetailRow>` JSON,
  `GET /api/analytics/return-details/category/{category}?from&to` → 같은 형태.
  `{category}`는 `DAMAGED`·`WRONG_ITEM`·`CHANGED_MIND`·`OTHER`·`UNCLASSIFIED`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ReturnAnalyticsControllerTest`에 import를 더한다:

```java
import com.jhg.wms.domain.Confidence;
import static org.mockito.Mockito.verify;
```

그리고 클래스 끝에 테스트 다섯을 더한다:

```java
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
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests 'com.jhg.wms.web.ReturnAnalyticsControllerTest'
```
Expected: 새 테스트 다섯이 **404로 실패**(엔드포인트 없음). Task 1의 넷은 계속 통과.

- [ ] **Step 3: 최소 구현을 쓴다**

`ReturnAnalyticsController`에 import를 더한다:

```java
import com.jhg.wms.domain.ReturnCategory;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
```

그리고 메서드 둘을 더한다:

```java
    @GetMapping("/return-details/product/{productId}")
    public List<ReturnAnalyticsService.ReturnDetailRow> detailsByProduct(
            @PathVariable Long productId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return returnAnalyticsService.detailsByProduct(productId, from, to);
    }

    /**
     * UNCLASSIFIED는 ReturnCategory enum에 없는 값이다. 그래도 이 이름으로 받는 이유는
     * 범주 다섯 칸이 전부 같은 URL 모양으로 열리게 하기 위해서다 — 미분류만 다른 경로를
     * 쓰면 호출자가 분기를 하나 더 가져야 하고, 그 분기가 조용히 어긋난다.
     * 화면(WmsAdminController.returnReportDetail)이 같은 규약을 쓴다.
     */
    @GetMapping("/return-details/category/{category}")
    public List<ReturnAnalyticsService.ReturnDetailRow> detailsByCategory(
            @PathVariable String category,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReturnCategory parsed = "UNCLASSIFIED".equals(category) ? null : ReturnCategory.valueOf(category);
        return returnAnalyticsService.detailsByCategory(parsed, from, to);
    }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests 'com.jhg.wms.web.ReturnAnalyticsControllerTest'
```
Expected: **PASS, 9건.**

- [ ] **Step 5: 변이 검증**

바꿔보고 **실패하는지** 확인한 뒤 원복한다.

1. `detailsByCategory`에서 UNCLASSIFIED 분기를 뺀다
   (`ReturnCategory parsed = ReturnCategory.valueOf(category);`)
   → `UNCLASSIFIED는_null_범주로_위임된다`가 실패해야 한다.
2. `detailsByProduct`의 반환을 `List.of()`로 바꾼다
   → `상품_상세를_그대로_낸다`와 `미분류_행은_...`이 실패해야 한다.
   (`verify` 없이 응답만 보는 테스트가 빈 목록을 통과시키지 않는지 확인하는 것이다.)

원복 후 9건 그린 확인.

- [ ] **Step 6: 전체 테스트를 돌린다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: **424건 그린**(419 + 5), 실패 0.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/ReturnAnalyticsController.java \
        src/test/java/com/jhg/wms/web/ReturnAnalyticsControllerTest.java
git commit -F - <<'MSG'
feat(wms): 반품 상세 REST 둘을 연다 — 상품 축과 범주 축 (V6.0a)

사유 원문이 모델 컨텍스트로 들어가는 통로다. category·confidence의 null을 그대로
내보낸다 — 분류는 V4.0부터 붙어서 그 이전 반품에는 없고, null이 사라지면 모델이
미분류를 세지 못한다.

UNCLASSIFIED를 경로 값으로 받는다. enum에 없는 값이지만 범주 다섯 칸이 같은 URL
모양으로 열려야 호출자가 분기 없이 순회한다. 화면이 이미 같은 규약을 쓴다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 3: 오류 계약 — 400이지 500이 아니다

**왜 별도 태스크인가**: `ReturnAnalyticsService.cohort()`는 `from.isAfter(to)`에
`IllegalArgumentException`을 던진다. `AdminDataAccessAdvice`는 `DataAccessException`만 받고
(`assignableTypes`에 이 컨트롤러가 없기도 하다), 이 컨트롤러엔 핸들러가 없다.
**지금 상태로는 역전 범위가 500이 된다.** V7.0에서 똑같은 원인으로 실제 회귀가 났다(원장 Task 4).
잘못된 범주 이름(`ReturnCategory.valueOf`)도 같은 예외로 500이 된다.

500과 400의 차이는 MCP 서버에서 커진다 — 모델이 "내가 인자를 잘못 줬다"와 "창고가 고장났다"를
구분해야 스스로 고친다.

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/ReturnAnalyticsController.java` (핸들러 추가)
- Modify: `src/test/java/com/jhg/wms/web/ReturnAnalyticsControllerTest.java` (테스트 셋 추가)
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1·2의 컨트롤러와 테스트 클래스
- Produces: 400 응답 + 평문 본문. V6.0b의 MCP 서버가 이 계약에 기대어 오류를 번역한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

import를 더한다:

```java
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
```

테스트 셋을 더한다:

```java
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
                .andExpect(status().isBadRequest());
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
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests 'com.jhg.wms.web.ReturnAnalyticsControllerTest'
```
Expected: `역전된_범위는_...`과 `알_수_없는_범주_이름은_400이다`가 **500으로 실패**.
`결과가_비어도_200이다`는 이미 통과한다(회귀 방지용으로 남긴다).

- [ ] **Step 3: 핸들러를 더한다**

`ReturnAnalyticsController`에 import를 더한다:

```java
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
```

클래스 끝에 핸들러를 더한다:

```java
    /**
     * 400이지 500이 아니다. 역전된 기간(서비스의 cohort())과 알 수 없는 범주 이름
     * (ReturnCategory.valueOf)이 여기로 온다.
     *
     * 이 구분이 MCP 서버에서 커진다 — 모델이 "내가 인자를 잘못 줬다"와 "창고가 고장났다"를
     * 구분할 수 있어야 스스로 고친다. 본문은 평문이다(README의 기존 API 오류 계약).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  ./gradlew test --tests 'com.jhg.wms.web.ReturnAnalyticsControllerTest'
```
Expected: **PASS, 12건.**

- [ ] **Step 5: 변이 검증**

`@ExceptionHandler` 애노테이션을 주석 처리한다
→ `역전된_범위는_400이고_500이_아니다`와 `알_수_없는_범주_이름은_400이다`가 실패해야 한다.
원복 후 12건 그린 확인.

- [ ] **Step 6: README를 고친다**

(1) 16행의 테스트 수를 현행화한다. `| 테스트 | 395개 (` → `| 테스트 | 427개 (` (뒤의 괄호 설명은
그대로 둔다). Step 7에서 실제 실행 수가 427이 아니면 그 수로 맞춘다.

(2) `### 반품 사유 자동 분류 (V4.0)` 절이 끝나고 `### OMS 재고보충 통지 (S3, 채널3)` 절이
시작되기 직전에 아래를 삽입한다:

```markdown
### 반품 분석 조회 (V6.0) — 내부 도구용, OMS 채널 아님

**이 넷은 S1~S7 채널이 아닙니다.** OMS는 부르지 않습니다. 별도 프로세스로 뜨는 MCP 서버가
Claude에게 반품 보고서를 쓰게 하려고 부르는 내부 표면입니다. 인증은 다른 `/api/**`와 같은
서비스 계정 Basic입니다.

| 엔드포인트 | 내용 |
|---|---|
| `GET /api/analytics/product-return-rates?from&to` | 상품별 출고·반품·반품률, 관찰 경과일, 주문 연결 불가 출고 수 |
| `GET /api/analytics/return-categories?from&to` | 범주별 건수와 소관, 미분류 수, 전체 수 |
| `GET /api/analytics/return-details/product/{productId}?from&to` | 그 상품 반품의 사유 원문·범주·신뢰도 |
| `GET /api/analytics/return-details/category/{category}?from&to` | 그 범주 반품 목록. `{category}`에 `UNCLASSIFIED` 허용 |

- **`from`·`to`는 필수입니다.** 기본값이 없습니다 — 보고서는 분모가 무엇인지 분명해야 합니다.
  날짜는 ISO(`2026-08-01`). 누락·형식 오류·역전된 범위는 **400**이고 본문은 평문입니다.
- **전부 읽기 전용입니다.** 반품 사유는 고객이 쓴 자유 텍스트이고 그것이 모델 컨텍스트로
  들어갑니다. 쓰기 표면이 섞이면 고객이 창고 데이터를 건드릴 경로가 생깁니다.
- 계산은 `ReturnAnalyticsService`가 합니다. 이 REST는 위임만 하므로 화면과 보고서의
  반품률 정의가 갈라지지 않습니다.
```

- [ ] **Step 7: 전체 테스트를 돌린다**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: **427건 그린**(424 + 3), 실패 0.
이 수를 README 16행에 반영했는지 확인한다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/ReturnAnalyticsController.java \
        src/test/java/com/jhg/wms/web/ReturnAnalyticsControllerTest.java README.md
git commit -F - <<'MSG'
feat(wms): 분석 REST의 잘못된 인자를 400으로 되돌린다 (V6.0a)

역전된 기간과 알 수 없는 범주 이름이 지금은 500이 된다. 서비스가 던지는
IllegalArgumentException을 AdminDataAccessAdvice가 받지 않고(DataAccessException만 받는다)
이 컨트롤러에도 핸들러가 없기 때문이다. V7.0에서 똑같은 원인으로 실제 회귀가 났었다.

이 구분이 MCP 서버에서 커진다. 모델이 "내가 인자를 잘못 줬다"와 "창고가 고장났다"를
구분할 수 있어야 스스로 고친다. 빈 결과는 200으로 남는다 — 오류와 섞이면 모델이
"반품이 없다"를 실패로 읽는다.

README에 내부 도구용 절을 더했다. S1~S7 채널 표에는 넣지 않는다 — OMS는 부르지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

## 완료 조건

- [ ] 427건 그린, 워킹트리 깨끗
- [ ] `SecurityConfig`에 diff가 없다 (`git diff master -- src/main/java/com/jhg/wms/config/SecurityConfig.java`가 비어 있음)
- [ ] `ReturnAnalyticsController`에 `ReturnAnalyticsService` 말고 다른 서비스 의존이 없다
- [ ] 컨트롤러에 집계·필터·환산 코드가 없다 (위임과 예외 매핑뿐)
- [ ] 수동 확인: WMS를 띄우고 네 엔드포인트를 curl로 호출해 JSON을 눈으로 본다
  ```bash
  curl -su wms:wms 'http://localhost:8081/api/analytics/return-categories?from=2026-08-01&to=2026-09-02'
  ```
  개발 DB의 `DEMO-` 반품 30건이 시드돼 있어 의미 있는 숫자가 나와야 한다.

## 다음 단계 (이 계획서 밖)

V6.0b — `mcp-server/` Python MCP 서버. **이 넷의 실제 JSON 응답을 확인한 뒤에** 계획서를 쓴다.
계약을 추측하지 않기 위해서다.
