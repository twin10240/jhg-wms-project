# 반품 사유 자동 분류 구현 계획 (WMS V4.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 반품 접수 시 고객이 쓴 자유 텍스트 사유를 Claude로 분류해 별도 엔티티에 저장하고, 반품 상세 화면에 **참고 정보**로만 표시한다. 재고·상태·검증 규칙에는 일절 영향이 없다.

**Architecture:** 접수 트랜잭션 커밋 후 전용 스레드 풀에서 분류를 시도한다(요청 스레드를 LLM 지연에 묶지 않는다). 응답은 구조화 출력(JSON 스키마)으로 강제하고, 그 JSON을 SDK와 분리된 순수 파서가 방어적으로 읽는다. 실패는 전부 `Optional.empty()` — 저장하지 않고 경고 로그만 남긴다. API 키가 없으면 항상 empty를 내는 구현으로 대체되어 기동은 정상이다.

**Tech Stack:** Java 21 · Spring Boot 3.5.5 · PostgreSQL 17 · `com.anthropic:anthropic-java:2.34.0` · Jackson(기존 Spring Boot 제공) · Thymeleaf

**근거 스펙:** `docs/superpowers/specs/v4/2026-08-30-return-reason-classification-design.md` (커밋 `6f380cc`)

---

## Global Constraints

- 브랜치: `feat/wms-ai-return-classification` (이미 체크아웃됨)
- 테스트 실행: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test`
  - 전제: 로컬 PostgreSQL 17 기동 중, `wms_test` DB 준비됨
- **자동 테스트에서 실제 Anthropic API를 호출하지 않는다.** 현재 325건이 외부 의존 없이 도는 성질을 지킨다. 테스트 코드에 `AnthropicOkHttpClient`를 생성하는 지점이 있으면 안 된다.
- 테스트 baseline: **325건 그린**. 매 태스크 끝에서 전체 스위트가 그린이어야 하고, 건수는 증가만 한다.
- 커밋 메시지: 한국어, 무엇이 아니라 **왜**를 쓴다. 마지막 줄에 트레일러:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  ```
- 코드 스타일: Java는 4스페이스, `build.gradle`은 **탭 들여쓰기 + 작은따옴표**(기존 파일과 동일)
- 주석은 "왜"만 쓴다. 코드가 이미 말하는 "무엇"은 쓰지 않는다.
- enum은 `@JdbcTypeCode(SqlTypes.VARCHAR)` + `@Enumerated(EnumType.STRING)` (기존 도메인과 동일 — DB 네이티브 ENUM 금지)
- 모델 ID는 정확히 `claude-haiku-4-5` (날짜 접미사 없음). 스펙의 비용 산정이 Haiku 기준이며 설정으로 교체 가능하다.
- 분류 실패는 **접수를 절대 막지 않는다.** 어떤 태스크에서도 분류 경로의 예외가 `createReturn` 밖으로 새면 안 된다.

---

## File Structure

**신규 (production)**

| 파일 | 책임 |
|------|------|
| `src/main/java/com/jhg/wms/domain/ReturnCategory.java` | 분류 범주 enum |
| `src/main/java/com/jhg/wms/domain/Confidence.java` | 신뢰도 enum |
| `src/main/java/com/jhg/wms/domain/ReturnClassification.java` | 분류 결과 엔티티 (RMA와 1:1, 별도 테이블) |
| `src/main/java/com/jhg/wms/repository/ReturnClassificationRepository.java` | 조회·존재확인 |
| `src/main/java/com/jhg/wms/service/ReturnReasonClassifier.java` | 분류기 인터페이스 + `Classification` 레코드 |
| `src/main/java/com/jhg/wms/service/ReturnClassificationService.java` | 분류 호출 → 저장, 실패 흡수 |
| `src/main/java/com/jhg/wms/service/ReturnClassificationTrigger.java` | 커밋 후 → 전용 스레드 위임 |
| `src/main/java/com/jhg/wms/config/ClassificationExecutorConfig.java` | 분류 전용 스레드 풀 |
| `src/main/java/com/jhg/wms/config/AiConfig.java` | 키 유무에 따라 실제 분류기 / 비활성 분류기 |
| `src/main/java/com/jhg/wms/client/ClassificationJsonParser.java` | 응답 JSON → 값. SDK 무관, 순수 함수 |
| `src/main/java/com/jhg/wms/client/ClaudeReturnReasonClassifier.java` | Anthropic SDK 어댑터 |
| `src/main/resources/prompts/return-classification.txt` | 시스템 프롬프트 |
| `src/main/resources/prompts/return-classification-schema.json` | 구조화 출력 스키마 |

**수정**

| 파일 | 변경 |
|------|------|
| `src/main/java/com/jhg/wms/service/RmaService.java` | 생성자에 트리거 추가, `createReturn` 신규 저장 직후 호출 |
| `src/main/java/com/jhg/wms/web/RmaAdminController.java` | 상세에 `classification` 모델 속성 추가 |
| `src/main/resources/templates/admin/returndetail.html` | 참고 영역 추가 |
| `src/main/resources/static/css/admin.css` | `.ai-hint` 스타일 |
| `src/main/resources/application.yml` | `wms.ai.*` 블록 |
| `build.gradle` | `com.anthropic:anthropic-java:2.34.0` |
| `src/test/java/com/jhg/wms/service/RmaServiceTest.java` | 생성자 인자 추가 + 트리거 호출 검증 |
| `src/test/java/com/jhg/wms/web/RmaAdminControllerTest.java` | 참고 영역 렌더 검증 |
| `README.md`, `docs/oms-wms-manual-verification.md` | 현행화 + 수동 스모크 |

**분해 근거:** 분류 경계(`ReturnReasonClassifier`)를 사이에 두고 **서비스 쪽**(태스크 1~3)과 **어댑터 쪽**(태스크 4~5)이 서로를 모른다. 그래서 SDK 없이도 태스크 3까지 완성·테스트되고, 파서는 SDK 없이 단독으로 검증된다.

---

## 스펙 대비 한 가지 보강 — 왜 별도 스레드인가

스펙의 호출 경계 다이어그램은 `201 응답 → afterCommit: 분류 시도` 순서를 그리고, 그 이유로 "OMS 응답이 LLM 지연에 묶이면 안 된다"를 든다. 그런데 `TransactionSynchronization.afterCommit`은 **커밋 뒤이긴 하지만 여전히 요청 스레드**다. 여기서 LLM을 그대로 부르면 `POST /api/returns`의 응답이 분류 시간(수 초)만큼 늦어지고, OMS의 read-timeout에 걸리면 OMS는 접수를 실패로 보고 재시도한다(멱등이라 데이터는 안전하지만 관측이 오염된다).

접수는 이미 커밋됐으므로 분류를 요청 밖으로 내보내도 잃는 것이 없다. 그래서 `afterCommit` **안에서 전용 executor에 넘긴다**. 스펙의 의도를 그대로 지키되 실제로 지켜지게 만드는 보강이고, 재시도 스윕을 두지 않는다는 결정은 그대로다.

---

### Task 1: 분류 도메인 — enum · 엔티티 · 리포지토리

**Files:**
- Create: `src/main/java/com/jhg/wms/domain/ReturnCategory.java`
- Create: `src/main/java/com/jhg/wms/domain/Confidence.java`
- Create: `src/main/java/com/jhg/wms/domain/ReturnClassification.java`
- Create: `src/main/java/com/jhg/wms/repository/ReturnClassificationRepository.java`
- Test: `src/test/java/com/jhg/wms/domain/ReturnClassificationTest.java`

**Interfaces:**
- Consumes: 기존 `RmaDisposition`(`RESTOCKED`/`DISPOSED`/`REJECTED`)
- Produces:
  - `enum ReturnCategory { DAMAGED, WRONG_ITEM, CHANGED_MIND, OTHER }`
  - `enum Confidence { HIGH, MEDIUM, LOW }`
  - `ReturnClassification.create(Long rmaReturnId, ReturnCategory category, Confidence confidence, String evidence, RmaDisposition suggestedDisposition, String model, int inputTokens, int outputTokens)` → `ReturnClassification`
  - getter: `getId/getRmaReturnId/getCategory/getConfidence/getEvidence/getSuggestedDisposition/getModel/getInputTokens/getOutputTokens/getClassifiedAt`
  - `ReturnClassificationRepository.findByRmaReturnId(Long)` → `Optional<ReturnClassification>`, `existsByRmaReturnId(Long)` → `boolean`

**마이그레이션 메모(코드 아님, 커밋 메시지에 남길 것):** `return_classification`은 **신규 테이블**이라 `ddl-auto: update`가 만든다. `docs/wms-enum-schema-migration.md`가 다루는 "기존 컬럼의 check 제약이 새 enum 값을 거부하는" 문제는 여기 해당하지 않는다 — 그건 기존 컬럼에 값을 **추가**할 때의 이야기다.

- [ ] **Step 1: baseline 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL. `build/reports/tests/test/index.html`의 테스트 수를 기록해 둔다(예상 325).

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/jhg/wms/domain/ReturnClassificationTest.java`:
```java
package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnClassificationTest {

    private ReturnClassification create(String evidence) {
        return ReturnClassification.create(7L, ReturnCategory.DAMAGED, Confidence.HIGH,
                evidence, RmaDisposition.DISPOSED, "claude-haiku-4-5", 400, 120);
    }

    @Test
    void 생성시_분류시각이_기록된다() {
        ReturnClassification c = create("모서리가 깨져 있어요");

        assertThat(c.getRmaReturnId()).isEqualTo(7L);
        assertThat(c.getCategory()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(c.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(c.getEvidence()).isEqualTo("모서리가 깨져 있어요");
        assertThat(c.getSuggestedDisposition()).isEqualTo(RmaDisposition.DISPOSED);
        assertThat(c.getModel()).isEqualTo("claude-haiku-4-5");
        assertThat(c.getInputTokens()).isEqualTo(400);
        assertThat(c.getOutputTokens()).isEqualTo(120);
        assertThat(c.getClassifiedAt()).isNotNull();
    }

    // 근거는 모델이 원문에서 인용하는 값이라 길이를 우리가 통제하지 못한다.
    // 컬럼 길이를 넘기면 배경 스레드에서 DataIntegrityViolation이 나므로 경계에서 자른다.
    @Test
    void 근거가_500자를_넘으면_잘라_저장한다() {
        ReturnClassification c = create("가".repeat(600));

        assertThat(c.getEvidence()).hasSize(500);
    }

    @Test
    void 근거는_없어도_된다() {
        assertThat(create(null).getEvidence()).isNull();
    }

    @Test
    void 필수값이_없으면_거부한다() {
        assertThatThrownBy(() -> ReturnClassification.create(null, ReturnCategory.OTHER,
                Confidence.LOW, "x", RmaDisposition.REJECTED, "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReturnClassification.create(7L, null,
                Confidence.LOW, "x", RmaDisposition.REJECTED, "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReturnClassification.create(7L, ReturnCategory.OTHER,
                null, "x", RmaDisposition.REJECTED, "m", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReturnClassification.create(7L, ReturnCategory.OTHER,
                Confidence.LOW, "x", RmaDisposition.REJECTED, " ", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnClassificationTest'
```
Expected: 컴파일 실패 — `cannot find symbol: class ReturnClassification`

- [ ] **Step 4: enum 두 개 작성**

`src/main/java/com/jhg/wms/domain/ReturnCategory.java`:
```java
package com.jhg.wms.domain;

public enum ReturnCategory { DAMAGED, WRONG_ITEM, CHANGED_MIND, OTHER }
```

`src/main/java/com/jhg/wms/domain/Confidence.java`:
```java
package com.jhg.wms.domain;

public enum Confidence { HIGH, MEDIUM, LOW }
```

- [ ] **Step 5: 엔티티 작성**

`src/main/java/com/jhg/wms/domain/ReturnClassification.java`:
```java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 반품 사유 자동 분류 결과. RmaReturn의 필드가 아니라 별도 테이블에 둔다 —
 * 분류는 RMA의 상태가 아니라 참고 정보이고, 도메인에 섞으면
 * "이 필드가 업무 규칙인가 힌트인가"가 흐려진다.
 */
@Entity
@Table(name = "return_classification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnClassification {

    private static final int EVIDENCE_MAX = 500;

    @Id @GeneratedValue
    @Column(name = "return_classification_id")
    private Long id;

    // 연관(@OneToOne)이 아니라 ID만 든다 — RmaReturn을 읽을 때 분류가 딸려오지 않게 해서
    // 업무 경로와 참고 경로를 물리적으로 갈라 둔다.
    @Column(nullable = false, unique = true)
    private Long rmaReturnId;

    @JdbcTypeCode(SqlTypes.VARCHAR)   // DB 네이티브 ENUM 대신 VARCHAR — 값 추가 시 기존 컬럼이 거부하는 사고 방지
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReturnCategory category;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Confidence confidence;

    @Column(length = EVIDENCE_MAX)
    private String evidence;

    // 기존 RmaDisposition을 재사용한다 — 나중에 실제 검수 결과와 대조할 때
    // 별도 enum이면 매핑이 필요해지지만 같은 타입이면 그냥 비교된다.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    private RmaDisposition suggestedDisposition;

    @Column(nullable = false)
    private String model;

    // "AI 기능을 붙였는데 얼마 드는지 모른다"를 피하려고 건별로 남긴다.
    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private Instant classifiedAt;

    public static ReturnClassification create(Long rmaReturnId, ReturnCategory category,
                                              Confidence confidence, String evidence,
                                              RmaDisposition suggestedDisposition,
                                              String model, int inputTokens, int outputTokens) {
        if (rmaReturnId == null) throw new IllegalArgumentException("rmaReturnId는 필수입니다.");
        if (category == null) throw new IllegalArgumentException("category는 필수입니다.");
        if (confidence == null) throw new IllegalArgumentException("confidence는 필수입니다.");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model은 필수입니다.");

        ReturnClassification c = new ReturnClassification();
        c.rmaReturnId = rmaReturnId;
        c.category = category;
        c.confidence = confidence;
        // 근거 길이는 모델이 정하므로 우리가 통제하지 못한다 — 컬럼 길이에 맞춰 자른다.
        c.evidence = (evidence != null && evidence.length() > EVIDENCE_MAX)
                ? evidence.substring(0, EVIDENCE_MAX) : evidence;
        c.suggestedDisposition = suggestedDisposition;
        c.model = model;
        c.inputTokens = inputTokens;
        c.outputTokens = outputTokens;
        c.classifiedAt = Instant.now();
        return c;
    }
}
```

- [ ] **Step 6: 리포지토리 작성**

`src/main/java/com/jhg/wms/repository/ReturnClassificationRepository.java`:
```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.ReturnClassification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReturnClassificationRepository extends JpaRepository<ReturnClassification, Long> {

    Optional<ReturnClassification> findByRmaReturnId(Long rmaReturnId);

    boolean existsByRmaReturnId(Long rmaReturnId);
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnClassificationTest'
```
Expected: PASS (4개)

- [ ] **Step 8: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 329건 (325 + 4)

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/jhg/wms/domain/ReturnCategory.java \
        src/main/java/com/jhg/wms/domain/Confidence.java \
        src/main/java/com/jhg/wms/domain/ReturnClassification.java \
        src/main/java/com/jhg/wms/repository/ReturnClassificationRepository.java \
        src/test/java/com/jhg/wms/domain/ReturnClassificationTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 반품 사유 분류 결과를 별도 엔티티로 둔다

분류는 RMA의 상태가 아니라 참고 정보다. RmaReturn에 필드로 섞으면 어느 값이
업무 규칙이고 어느 값이 힌트인지 흐려지고, 없을 수 있는 값 여덟 개가 도메인에
얹힌다. 연관 대신 rmaReturnId만 들어 RMA를 읽을 때 분류가 딸려오지 않게 했다.

근거(evidence) 길이는 모델이 정하므로 우리가 통제할 수 없다. 컬럼을 넘기면
배경 스레드에서 DataIntegrityViolation이 나므로 생성 시점에 자른다.

return_classification은 신규 테이블이라 ddl-auto: update가 만든다 —
기존 컬럼에 enum 값을 추가할 때의 check 제약 문제는 여기 해당하지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 분류 경계와 저장 서비스

**Files:**
- Create: `src/main/java/com/jhg/wms/service/ReturnReasonClassifier.java`
- Create: `src/main/java/com/jhg/wms/service/ReturnClassificationService.java`
- Test: `src/test/java/com/jhg/wms/service/ReturnClassificationServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `ReturnClassification`, `ReturnClassificationRepository`, `ReturnCategory`, `Confidence`
- Produces:
  - `interface ReturnReasonClassifier { Optional<Classification> classify(String reason); }`
  - `record ReturnReasonClassifier.Classification(ReturnCategory category, Confidence confidence, String evidence, RmaDisposition suggestedDisposition, String model, int inputTokens, int outputTokens)`
  - `ReturnClassificationService.classifyAndSave(Long rmaReturnId, String reason)` → `void`
  - `ReturnClassificationService.findByRmaId(Long)` → `Optional<ReturnClassification>`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jhg/wms/service/ReturnClassificationServiceTest.java`:
```java
package com.jhg.wms.service;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import com.jhg.wms.repository.ReturnClassificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
class ReturnClassificationServiceTest {

    @Autowired ReturnClassificationRepository repository;

    AtomicInteger calls;
    AtomicReference<String> lastReason;

    @BeforeEach
    void setUp() {
        calls = new AtomicInteger();
        lastReason = new AtomicReference<>();
    }

    /** 실제 API를 부르지 않는 가짜 분류기. 인터페이스를 둔 이유가 이것이다. */
    private ReturnClassificationService serviceReturning(
            ReturnReasonClassifier.Classification result) {
        return new ReturnClassificationService(reason -> {
            calls.incrementAndGet();
            lastReason.set(reason);
            return Optional.ofNullable(result);
        }, repository);
    }

    private ReturnReasonClassifier.Classification sample() {
        return new ReturnReasonClassifier.Classification(
                ReturnCategory.DAMAGED, Confidence.HIGH, "모서리가 깨져 있어요",
                RmaDisposition.DISPOSED, "claude-haiku-4-5", 412, 118);
    }

    @Test
    void 분류에_성공하면_저장한다() {
        serviceReturning(sample()).classifyAndSave(11L, "받았는데 모서리가 깨져 있어요");

        var saved = repository.findByRmaReturnId(11L).orElseThrow();
        assertThat(saved.getCategory()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(saved.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(saved.getSuggestedDisposition()).isEqualTo(RmaDisposition.DISPOSED);
        assertThat(saved.getInputTokens()).isEqualTo(412);
        assertThat(saved.getOutputTokens()).isEqualTo(118);
        assertThat(lastReason.get()).isEqualTo("받았는데 모서리가 깨져 있어요");
    }

    @Test
    void 분류에_실패하면_저장하지_않는다() {
        serviceReturning(null).classifyAndSave(12L, "그냥요");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(repository.findByRmaReturnId(12L)).isEmpty();
    }

    // 빈 사유로 부르는 건 확정적으로 쓸모없는 호출이다 — 토큰을 쓰기 전에 막는다.
    @Test
    void 사유가_비어_있으면_분류기를_부르지_않는다() {
        var service = serviceReturning(sample());

        service.classifyAndSave(13L, null);
        service.classifyAndSave(14L, "   ");

        assertThat(calls.get()).isZero();
        assertThat(repository.findByRmaReturnId(13L)).isEmpty();
        assertThat(repository.findByRmaReturnId(14L)).isEmpty();
    }

    // rmaReturnId에 유니크 제약이 걸려 있어, 두 번째 호출이 그대로 들어가면
    // 배경 스레드에서 제약 위반으로 터진다. 부르기 전에 막는다.
    @Test
    void 이미_분류가_있으면_다시_부르지_않는다() {
        var service = serviceReturning(sample());
        service.classifyAndSave(15L, "깨졌어요");
        service.classifyAndSave(15L, "깨졌어요");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(repository.findAll()).hasSize(1);
    }

    // 분류는 참고 정보다. 분류기가 뭘 던지든 그게 호출자(접수 경로)로 새면 안 된다.
    @Test
    void 분류기가_예외를_던져도_밖으로_새지_않는다() {
        var service = new ReturnClassificationService(reason -> {
            throw new IllegalStateException("모델 호출 폭발");
        }, repository);

        assertThatCode(() -> service.classifyAndSave(16L, "깨졌어요"))
                .doesNotThrowAnyException();
        assertThat(repository.findByRmaReturnId(16L)).isEmpty();
    }

    @Test
    void 저장된_분류를_rmaId로_찾는다() {
        var service = serviceReturning(sample());
        service.classifyAndSave(17L, "깨졌어요");

        assertThat(service.findByRmaId(17L)).isPresent();
        assertThat(service.findByRmaId(999L)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnClassificationServiceTest'
```
Expected: 컴파일 실패 — `cannot find symbol: class ReturnReasonClassifier`

- [ ] **Step 3: 인터페이스 작성**

`src/main/java/com/jhg/wms/service/ReturnReasonClassifier.java`:
```java
package com.jhg.wms.service;

import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;

import java.util.Optional;

/**
 * 반품 사유 텍스트 분류기.
 * 인터페이스를 두는 이유는 둘이다 — 서비스가 특정 SDK에 묶이지 않게,
 * 그리고 테스트가 실제 API를 호출하지 않아도 되게.
 * 실패(타임아웃·스키마 위반·키 미설정)는 전부 empty다. 예외로 알리지 않는다.
 */
public interface ReturnReasonClassifier {

    Optional<Classification> classify(String reason);

    record Classification(ReturnCategory category,
                          Confidence confidence,
                          String evidence,
                          RmaDisposition suggestedDisposition,
                          String model,
                          int inputTokens,
                          int outputTokens) {}
}
```

- [ ] **Step 4: 서비스 작성**

`src/main/java/com/jhg/wms/service/ReturnClassificationService.java`:
```java
package com.jhg.wms.service;

import com.jhg.wms.domain.ReturnClassification;
import com.jhg.wms.repository.ReturnClassificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReturnClassificationService {

    private final ReturnReasonClassifier classifier;
    private final ReturnClassificationRepository repository;

    /**
     * 분류를 시도해 성공하면 저장한다. 접수 트랜잭션이 이미 커밋된 뒤 별도 스레드에서 불린다.
     * 어떤 실패도 밖으로 던지지 않는다 — 분류는 참고 정보라 실패가 다른 것을 망가뜨려선 안 된다.
     */
    @Transactional
    public void classifyAndSave(Long rmaReturnId, String reason) {
        if (reason == null || reason.isBlank()) return;
        // 유니크 제약이 터지기 전에 막는다. 재실행돼도 토큰을 다시 쓰지 않는다.
        if (repository.existsByRmaReturnId(rmaReturnId)) return;

        try {
            classifier.classify(reason).ifPresentOrElse(
                    c -> {
                        repository.save(ReturnClassification.create(rmaReturnId, c.category(),
                                c.confidence(), c.evidence(), c.suggestedDisposition(),
                                c.model(), c.inputTokens(), c.outputTokens()));
                        // 건당 토큰을 로그에도 남긴다 — 엔티티만 보면 총량을 세기 번거롭다.
                        log.info("반품 사유 분류: rmaId={} category={} confidence={} model={} in={} out={}",
                                rmaReturnId, c.category(), c.confidence(), c.model(),
                                c.inputTokens(), c.outputTokens());
                    },
                    () -> log.warn("반품 사유 분류 없음(무시): rmaId={}", rmaReturnId));
        } catch (Exception e) {
            log.warn("반품 사유 분류 실패(무시): rmaId={}", rmaReturnId, e);
        }
    }

    public Optional<ReturnClassification> findByRmaId(Long rmaReturnId) {
        return repository.findByRmaReturnId(rmaReturnId);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnClassificationServiceTest'
```
Expected: PASS (6개)

- [ ] **Step 6: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 335건

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/wms/service/ReturnReasonClassifier.java \
        src/main/java/com/jhg/wms/service/ReturnClassificationService.java \
        src/test/java/com/jhg/wms/service/ReturnClassificationServiceTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 분류 경계를 인터페이스로 끊고 저장 서비스를 붙인다

분류기를 인터페이스로 둔 이유는 둘이다. 서비스가 특정 SDK에 묶이지 않고,
테스트가 실제 API를 부르지 않아도 된다. 실패는 예외가 아니라 empty로 알린다 —
호출부가 try/catch로 분기하지 않고 "분류가 없다"를 한 가지 모양으로 다루게 하려고.

빈 사유는 부르기 전에 걸러 토큰을 쓰지 않고, 이미 분류가 있으면 다시 부르지
않는다(유니크 제약이 배경 스레드에서 터지는 것을 앞에서 막는다).
분류기가 무엇을 던지든 삼킨다 — 참고 정보의 실패가 접수 경로를 흔들면 안 된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 접수 연결 — 커밋 후 · 요청 스레드 밖

**Files:**
- Create: `src/main/java/com/jhg/wms/config/ClassificationExecutorConfig.java`
- Create: `src/main/java/com/jhg/wms/service/ReturnClassificationTrigger.java`
- Modify: `src/main/java/com/jhg/wms/service/RmaService.java`
- Test: `src/test/java/com/jhg/wms/service/ReturnClassificationTriggerTest.java`
- Modify test: `src/test/java/com/jhg/wms/service/RmaServiceTest.java`

**Interfaces:**
- Consumes: Task 2의 `ReturnClassificationService.classifyAndSave(Long, String)`
- Produces:
  - `@Bean("classificationExecutor") ExecutorService`
  - `ReturnClassificationTrigger(ReturnClassificationService, Executor)` — 생성자
  - `ReturnClassificationTrigger.classifyAfterCommit(Long rmaReturnId, String reason)` → `void`
  - `RmaService` 생성자가 5인자로 확장: `(RmaReturnRepository, ReservationRepository, InventoryService, OmsReturnStatusNotifier, ReturnClassificationTrigger)`

- [ ] **Step 1: 실패하는 트리거 테스트 작성**

`src/test/java/com/jhg/wms/service/ReturnClassificationTriggerTest.java`:
```java
package com.jhg.wms.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ReturnClassificationTriggerTest {

    ReturnClassificationService service;
    List<Runnable> submitted;
    ReturnClassificationTrigger trigger;

    @BeforeEach
    void setUp() {
        service = mock(ReturnClassificationService.class);
        submitted = new ArrayList<>();
        // executor에 실제로 넘어갔는지 보려고 실행을 가로챈다 — 요청 스레드에서
        // 바로 돌면 OMS 응답이 분류 지연에 묶이므로, 그 위임 자체가 검증 대상이다.
        trigger = new ReturnClassificationTrigger(service, submitted::add);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    private void fireAfterCommit() {
        List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                .forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void 커밋_전에는_분류하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        trigger.classifyAfterCommit(7L, "깨졌어요");

        assertThat(submitted).isEmpty();
        verifyNoInteractions(service);
    }

    @Test
    void 커밋_후에_executor로_넘긴다() {
        TransactionSynchronizationManager.initSynchronization();
        trigger.classifyAfterCommit(7L, "깨졌어요");
        fireAfterCommit();

        assertThat(submitted).hasSize(1);
        verifyNoInteractions(service);   // 아직 executor가 실행하지 않았다

        submitted.get(0).run();
        verify(service).classifyAndSave(7L, "깨졌어요");
    }

    @Test
    void 사유가_비면_동기화_등록_자체를_하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        trigger.classifyAfterCommit(7L, null);
        trigger.classifyAfterCommit(8L, "  ");

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnClassificationTriggerTest'
```
Expected: 컴파일 실패 — `cannot find symbol: class ReturnClassificationTrigger`

- [ ] **Step 3: executor 빈 작성**

`src/main/java/com/jhg/wms/config/ClassificationExecutorConfig.java`:
```java
package com.jhg.wms.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class ClassificationExecutorConfig {

    /**
     * 분류 전용 작은 풀. 큐가 차면 그냥 버린다 —
     * CallerRuns로 되돌리면 막으려던 것(요청 스레드가 분류를 기다림)이 그대로 일어나고,
     * 무제한 큐로 두면 밀린 분류가 메모리로 쌓인다. 분류는 빠져도 업무가 막히지 않으므로 버리는 쪽이 맞다.
     */
    @Bean(name = "classificationExecutor", destroyMethod = "shutdown")
    public ExecutorService classificationExecutor() {
        return new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50),
                runnable -> {
                    Thread t = new Thread(runnable, "rma-classify");
                    t.setDaemon(true);
                    return t;
                },
                (runnable, executor) ->
                        log.warn("반품 사유 분류 대기열 포화 — 이번 건은 분류하지 않습니다."));
    }
}
```

- [ ] **Step 4: 트리거 작성**

`src/main/java/com/jhg/wms/service/ReturnClassificationTrigger.java`:
```java
package com.jhg.wms.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

@Component
public class ReturnClassificationTrigger {

    private final ReturnClassificationService service;
    private final Executor executor;

    public ReturnClassificationTrigger(ReturnClassificationService service,
                                       @Qualifier("classificationExecutor") Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    /**
     * 접수 커밋 후, 요청 스레드 밖에서 분류한다.
     *
     * afterCommit만으로는 부족하다 — 커밋 뒤에 돌긴 하지만 여전히 요청 스레드라,
     * 거기서 LLM을 부르면 OMS의 POST /api/returns 응답이 분류 지연만큼 늦어지고
     * OMS read-timeout에 걸리면 접수가 실패로 관측된다. 접수는 이미 커밋됐으니
     * 분류를 요청 밖으로 내보내도 잃는 것이 없다.
     */
    public void classifyAfterCommit(Long rmaReturnId, String reason) {
        if (reason == null || reason.isBlank()) return;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> service.classifyAndSave(rmaReturnId, reason));
            }
        });
    }
}
```

- [ ] **Step 5: 트리거 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ReturnClassificationTriggerTest'
```
Expected: PASS (3개)

- [ ] **Step 6: RmaService 접수 경로 테스트 추가**

`src/test/java/com/jhg/wms/service/RmaServiceTest.java` 수정 — 세 곳:

(1) 필드 추가 (`OmsReturnStatusNotifier returnNotifier;` 아래):
```java
    ReturnClassificationTrigger classificationTrigger;
```

(2) `setUp()`의 `rmaService = ...` 줄을 두 줄로 교체:
```java
        classificationTrigger = mock(ReturnClassificationTrigger.class);
        rmaService = new RmaService(rmaRepo, reservationRepo, inventoryService, returnNotifier,
                classificationTrigger);
```

(3) `// ── 접수 ─` 섹션 끝에 테스트 두 개 추가:
```java
    // 분류는 접수 커밋 후 시도한다 — 접수 응답이 LLM 지연에 묶이지 않게.
    @Test
    void 접수하면_사유_분류를_커밋후로_예약한다() {
        seedAndShip(140L, Map.of(1L, 5));
        rmaService.createReturn(req(UUID.randomUUID().toString(), 140L,
                "모서리가 깨져 있어요", items(541, 1, 1)));

        verify(classificationTrigger).classifyAfterCommit(any(), eq("모서리가 깨져 있어요"));
    }

    // 멱등 재접수는 새 RMA를 만들지 않으므로 분류도 다시 시도하지 않는다.
    @Test
    void 같은_키로_재접수하면_분류를_다시_예약하지_않는다() {
        seedAndShip(141L, Map.of(1L, 5));
        String key = UUID.randomUUID().toString();
        rmaService.createReturn(req(key, 141L, "깨졌어요", items(542, 1, 1)));
        reset(classificationTrigger);

        rmaService.createReturn(req(key, 141L, "깨졌어요", items(542, 1, 1)));

        verifyNoInteractions(classificationTrigger);
    }
```

임포트 추가 (기존 import 블록에):
```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
```

- [ ] **Step 7: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*RmaServiceTest'
```
Expected: 컴파일 실패 — `constructor RmaService cannot be applied to given types`

- [ ] **Step 8: RmaService 연결**

`src/main/java/com/jhg/wms/service/RmaService.java` — 두 곳.

(1) 필드 추가 (`private final OmsReturnStatusNotifier omsReturnStatusNotifier;` 아래):
```java
    private final ReturnClassificationTrigger returnClassificationTrigger;
```

(2) `createReturn`의 마지막 `return` 문을 교체:
```java
        RmaReturn saved = rmaReturnRepository.save(rma);
        // 분류는 참고 정보라 접수와 한 트랜잭션에 묶지 않는다 —
        // 외부 LLM 장애가 반품 접수를 막으면 안 된다.
        returnClassificationTrigger.classifyAfterCommit(saved.getId(), saved.getReason());
        return new CreateResult(true, saved);
```
(즉 기존 `return new CreateResult(true, rmaReturnRepository.save(rma));` 한 줄을 위 네 줄로 바꾼다.)

- [ ] **Step 9: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*RmaServiceTest' --tests '*ReturnClassificationTriggerTest'
```
Expected: PASS

- [ ] **Step 10: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 340건

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/jhg/wms/config/ClassificationExecutorConfig.java \
        src/main/java/com/jhg/wms/service/ReturnClassificationTrigger.java \
        src/main/java/com/jhg/wms/service/RmaService.java \
        src/test/java/com/jhg/wms/service/ReturnClassificationTriggerTest.java \
        src/test/java/com/jhg/wms/service/RmaServiceTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 접수 커밋 후 요청 스레드 밖에서 사유를 분류한다

afterCommit만으로는 부족했다. 커밋 뒤에 돌긴 하지만 여전히 요청 스레드라,
거기서 LLM을 부르면 OMS의 POST /api/returns 응답이 분류 지연만큼 늦어지고
OMS read-timeout에 걸리면 접수가 실패로 관측된다(멱등이라 데이터는 안전하지만
관측이 오염된다). 접수는 이미 커밋됐으므로 분류를 요청 밖으로 내보내도
잃는 것이 없어, afterCommit 안에서 전용 executor에 넘긴다.

큐가 차면 버린다. CallerRuns로 되돌리면 막으려던 대기가 그대로 일어나고,
무제한 큐는 밀린 분류를 메모리로 쌓는다. 분류는 빠져도 업무가 막히지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 프롬프트·스키마 리소스와 응답 파서

**Files:**
- Create: `src/main/resources/prompts/return-classification.txt`
- Create: `src/main/resources/prompts/return-classification-schema.json`
- Create: `src/main/java/com/jhg/wms/client/ClassificationJsonParser.java`
- Test: `src/test/java/com/jhg/wms/client/ClassificationJsonParserTest.java`

**Interfaces:**
- Consumes: Task 1의 `ReturnCategory`, `Confidence`, 기존 `RmaDisposition`
- Produces:
  - `ClassificationJsonParser(ObjectMapper)` — 생성자 (public)
  - `ClassificationJsonParser.parse(String json)` → `Optional<Parsed>`
  - `record ClassificationJsonParser.Parsed(ReturnCategory category, Confidence confidence, String evidence, RmaDisposition suggestedDisposition)`
- 이 태스크는 Anthropic SDK에 의존하지 않는다. Jackson만 쓴다.

**JSON 필드명:** 응답 JSON은 snake_case(`suggested_disposition`)를 쓴다. 스키마 파일과 파서가 같은 이름을 써야 한다.

- [ ] **Step 1: 실패하는 파서 테스트 작성**

`src/test/java/com/jhg/wms/client/ClassificationJsonParserTest.java`:
```java
package com.jhg.wms.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구조화 출력을 쓰더라도 파싱은 방어한다. 검증 대상은 "모델이 스키마를 어겼을 때
 * 우리 코드가 어떻게 행동하는가"이고, 그건 실제 호출로는 재현할 수 없다.
 */
class ClassificationJsonParserTest {

    private final ClassificationJsonParser parser = new ClassificationJsonParser(new ObjectMapper());

    @Test
    void 정상_응답을_값으로_읽는다() {
        String json = """
                {"category":"DAMAGED","confidence":"HIGH",
                 "evidence":"모서리가 깨져 있어요","suggested_disposition":"DISPOSED"}
                """;

        var parsed = parser.parse(json).orElseThrow();
        assertThat(parsed.category()).isEqualTo(ReturnCategory.DAMAGED);
        assertThat(parsed.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(parsed.evidence()).isEqualTo("모서리가 깨져 있어요");
        assertThat(parsed.suggestedDisposition()).isEqualTo(RmaDisposition.DISPOSED);
    }

    @Test
    void enum에_없는_값이면_버린다() {
        String json = """
                {"category":"BROKEN_MAYBE","confidence":"HIGH",
                 "evidence":"깨짐","suggested_disposition":"DISPOSED"}
                """;

        assertThat(parser.parse(json)).isEmpty();
    }

    @Test
    void 필수_필드가_없으면_버린다() {
        String json = """
                {"category":"DAMAGED","confidence":"HIGH","evidence":"깨짐"}
                """;

        assertThat(parser.parse(json)).isEmpty();
    }

    // max_tokens에 걸려 응답이 중간에 끊기는 경우.
    @Test
    void 잘린_JSON이면_버린다() {
        assertThat(parser.parse("{\"category\":\"DAMAGED\",\"conf")).isEmpty();
    }

    @Test
    void 빈_응답이면_버린다() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    // 근거는 참고의 참고다. 없어도 분류 자체는 쓸 수 있으므로 통과시킨다.
    @Test
    void 근거가_없어도_분류는_살린다() {
        String json = """
                {"category":"CHANGED_MIND","confidence":"LOW","suggested_disposition":"RESTOCKED"}
                """;

        var parsed = parser.parse(json).orElseThrow();
        assertThat(parsed.category()).isEqualTo(ReturnCategory.CHANGED_MIND);
        assertThat(parsed.evidence()).isNull();
    }

    // 필드가 문자열이 아닌 타입으로 오는 경우(스키마 위반의 다른 얼굴).
    @Test
    void 필드_타입이_다르면_버린다() {
        String json = """
                {"category":1,"confidence":"HIGH","evidence":"깨짐","suggested_disposition":"DISPOSED"}
                """;

        assertThat(parser.parse(json)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ClassificationJsonParserTest'
```
Expected: 컴파일 실패 — `cannot find symbol: class ClassificationJsonParser`

- [ ] **Step 3: 파서 작성**

`src/main/java/com/jhg/wms/client/ClassificationJsonParser.java`:
```java
package com.jhg.wms.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.RmaDisposition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 모델 응답 JSON을 값으로 읽는다. SDK를 모른다 — 고정 JSON으로 단독 검증하기 위해서다.
 * 구조화 출력을 쓰더라도 여기서 한 번 더 막는다: 스키마가 보장하지 못하는 것(잘린 응답)이
 * 남아 있고, 스키마를 어겼을 때 우리 코드가 어떻게 행동하는지는 실제 호출로 재현할 수 없다.
 */
@Slf4j
@RequiredArgsConstructor
public class ClassificationJsonParser {

    private final ObjectMapper objectMapper;

    public Optional<Parsed> parse(String json) {
        if (json == null || json.isBlank()) {
            log.warn("분류 응답이 비어 있음(무시)");
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            ReturnCategory category = enumOf(ReturnCategory.class, text(node, "category"));
            Confidence confidence = enumOf(Confidence.class, text(node, "confidence"));
            RmaDisposition disposition =
                    enumOf(RmaDisposition.class, text(node, "suggested_disposition"));

            if (category == null || confidence == null || disposition == null) {
                log.warn("분류 응답이 스키마를 벗어남(무시): {}", abbreviate(json));
                return Optional.empty();
            }
            return Optional.of(new Parsed(category, confidence, text(node, "evidence"), disposition));
        } catch (Exception e) {
            log.warn("분류 응답 파싱 실패(무시): {}", abbreviate(json));
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || !value.isTextual()) ? null : value.asText();
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // 로그에 응답 전문을 쏟지 않는다 — 사유 원문이 인용돼 들어올 수 있다.
    private static String abbreviate(String json) {
        return json.length() <= 200 ? json : json.substring(0, 200) + "…";
    }

    public record Parsed(ReturnCategory category,
                         Confidence confidence,
                         String evidence,
                         RmaDisposition suggestedDisposition) {}
}
```

- [ ] **Step 4: 프롬프트 작성**

`src/main/resources/prompts/return-classification.txt`:
```
너는 물류창고의 반품 검수 담당자를 돕는 분류기다. 고객이 자유 텍스트로 쓴 반품 사유를 읽고
아래 네 값을 JSON으로 낸다. 검수자의 판단을 대신하는 것이 아니라, 읽기를 돕는 참고 정보다.

category — 반품 사유의 범주
  DAMAGED       물건이 깨지거나 훼손된 상태로 도착했다
  WRONG_ITEM    주문한 것과 다른 상품·옵션·수량이 왔다
  CHANGED_MIND  물건 자체에는 문제가 없고 고객이 마음을 바꿨다(사이즈 안 맞음, 필요 없어짐)
  OTHER         위 어디에도 분명히 들어가지 않는다

confidence — 그 판단의 확실함
  HIGH    사유에 근거가 분명히 적혀 있다
  MEDIUM  약간의 추론이 필요하지만 근거가 있다
  LOW     사유가 짧거나 모호해 추측에 가깝다

evidence — 판단 근거를 사유 원문에서 그대로 인용한다.
  원문에 없는 말을 지어내지 않는다. 인용할 만한 대목이 없으면 빈 문자열로 둔다.

suggested_disposition — 검수자가 실물을 보고 확정할 처분의 후보다. 확정이 아니다.
  RESTOCKED  재판매 가능해 보인다(주로 단순 변심·미개봉)
  DISPOSED   재판매 불가해 보인다(주로 파손)
  REJECTED   반품을 받아주기 어려워 보인다(사용 흔적·기간 경과 등이 사유에 드러난 경우)

파손 정도는 실물을 봐야 알 수 있다. 사유에 적힌 것 이상을 단정하지 마라.
범주를 억지로 맞추지 말고, 애매하면 OTHER를 쓰고 confidence를 낮춰라.
```

- [ ] **Step 5: 스키마 작성**

`src/main/resources/prompts/return-classification-schema.json`:
```json
{
  "type": "object",
  "properties": {
    "category": {
      "type": "string",
      "enum": ["DAMAGED", "WRONG_ITEM", "CHANGED_MIND", "OTHER"]
    },
    "confidence": {
      "type": "string",
      "enum": ["HIGH", "MEDIUM", "LOW"]
    },
    "evidence": {
      "type": "string"
    },
    "suggested_disposition": {
      "type": "string",
      "enum": ["RESTOCKED", "DISPOSED", "REJECTED"]
    }
  },
  "required": ["category", "confidence", "evidence", "suggested_disposition"],
  "additionalProperties": false
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*ClassificationJsonParserTest'
```
Expected: PASS (7개)

- [ ] **Step 7: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 347건

- [ ] **Step 8: 커밋**

```bash
git add src/main/resources/prompts/ \
        src/main/java/com/jhg/wms/client/ClassificationJsonParser.java \
        src/test/java/com/jhg/wms/client/ClassificationJsonParserTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 분류 프롬프트·스키마를 리소스로 두고 응답 파서를 분리한다

프롬프트를 코드 상수로 두면 프롬프트 변경이 diff에서 안 보인다. 프롬프트는
코드만큼 자주 바뀌므로 리소스 파일로 뺐다.

파서를 SDK에서 떼어낸 이유는 검증 대상 때문이다. 구조화 출력이 스키마를
강제해도 잘린 응답은 남고, "모델이 스키마를 어겼을 때 우리가 어떻게 행동하는가"는
실제 호출로 재현할 수 없다. 고정 JSON으로 정상·enum 이탈·필드 누락·잘림·
타입 불일치를 단독 검증한다.

로그에 응답 전문을 쏟지 않는다 — 고객이 쓴 사유 원문이 인용돼 들어올 수 있다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Anthropic SDK 어댑터와 설정

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/com/jhg/wms/client/ClaudeReturnReasonClassifier.java`
- Create: `src/main/java/com/jhg/wms/config/AiConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/jhg/wms/config/AiConfigTest.java`

**Interfaces:**
- Consumes: Task 2의 `ReturnReasonClassifier`/`Classification`, Task 4의 `ClassificationJsonParser`
- Produces: `@Bean ReturnReasonClassifier returnReasonClassifier(...)` — 애플리케이션 컨텍스트에 항상 존재하는 단일 분류기 빈

**SDK 시그니처(실제 jar `anthropic-java-core:2.34.0`으로 확인함 — 추정 아님):**
```
MessageCreateParams.Builder.model(String) / .maxTokens(long) / .system(String)
                           .addUserMessage(String) / .outputConfig(OutputConfig)
OutputConfig.Builder.format(JsonOutputFormat)
JsonOutputFormat.Builder.schema(JsonOutputFormat.Schema) / .type(JsonValue)
JsonOutputFormat.Schema.Builder.putAdditionalProperty(String, JsonValue)   // 자유형 맵
JsonValue.from(Object)
Message.content() → List<ContentBlock>;  ContentBlock.text() → Optional<TextBlock>;  TextBlock.text() → String
Message.model() → Model;  Model.asString() → String
Message.usage().inputTokens() / .outputTokens() → long
AnthropicOkHttpClient.builder().apiKey(String).timeout(Duration).maxRetries(int).build()
```

**모델 선택:** `claude-haiku-4-5`. 스펙의 비용 산정($1/$5 per 1M, 건당 약 1.4원)이 Haiku 기준이고 분류는 짧은 단일 호출이다. Haiku 4.5는 구조화 출력을 지원하지만 **`effort` 파라미터를 거부**하고 확장 사고가 기본으로 꺼져 있어, `thinking`·`effort`를 아예 설정하지 않는다. 모델을 Opus/Sonnet 5 계열로 바꾸면 사고가 기본으로 켜지므로 `max-tokens`를 함께 올려야 한다 — yml 주석에 남긴다.

- [ ] **Step 1: 의존성 추가**

`build.gradle`의 `dependencies` 블록에서 `implementation 'org.redisson:redisson:3.37.0'` 아래 줄에 추가(탭 들여쓰기 유지):
```gradle
	// 반품 사유 자동 분류(V4.0). 키가 없으면 AiConfig가 비활성 구현으로 대체하므로 기동은 정상.
	implementation 'com.anthropic:anthropic-java:2.34.0'
```

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew dependencies --configuration runtimeClasspath | grep anthropic
```
Expected: `com.anthropic:anthropic-java:2.34.0` 및 `anthropic-java-core`, `anthropic-java-client-okhttp`가 해석됨

- [ ] **Step 2: 실패하는 설정 테스트 작성**

`src/test/java/com/jhg/wms/config/AiConfigTest.java`:
```java
package com.jhg.wms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.service.ReturnReasonClassifier;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 API를 부르지 않는다 — 검증 대상은 "키가 없을 때 기동이 막히지 않는가"다.
 * wms.basic·oms.callback은 없으면 통신이 전면 실패하므로 기동을 막지만,
 * 분류는 없어도 창고 업무가 돈다.
 */
class AiConfigTest {

    private final AiConfig config = new AiConfig();

    @Test
    void 키가_없으면_항상_빈_결과를_내는_분류기가_된다() {
        ReturnReasonClassifier classifier = config.returnReasonClassifier(
                "", "claude-haiku-4-5", 1024L, Duration.ofSeconds(20), new ObjectMapper());

        assertThat(classifier.classify("모서리가 깨져 있어요")).isEmpty();
    }

    @Test
    void 키가_공백만_있어도_비활성이다() {
        ReturnReasonClassifier classifier = config.returnReasonClassifier(
                "   ", "claude-haiku-4-5", 1024L, Duration.ofSeconds(20), new ObjectMapper());

        assertThat(classifier.classify("깨졌어요")).isEmpty();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*AiConfigTest'
```
Expected: 컴파일 실패 — `cannot find symbol: class AiConfig`

- [ ] **Step 4: 어댑터 작성**

`src/main/java/com/jhg/wms/client/ClaudeReturnReasonClassifier.java`:
```java
package com.jhg.wms.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.service.ReturnReasonClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class ClaudeReturnReasonClassifier implements ReturnReasonClassifier {

    private final AnthropicClient client;
    private final ClassificationJsonParser parser;
    private final String model;
    private final long maxTokens;
    private final String systemPrompt;
    private final JsonOutputFormat.Schema schema;

    public ClaudeReturnReasonClassifier(AnthropicClient client, ObjectMapper objectMapper,
                                        String model, long maxTokens) {
        this.client = client;
        this.parser = new ClassificationJsonParser(objectMapper);
        this.model = model;
        this.maxTokens = maxTokens;
        // 프롬프트·스키마는 기동 시 한 번 읽는다. 파일이 없으면 그건 배포 사고라 기동에서 드러나야 한다.
        this.systemPrompt = readResource("prompts/return-classification.txt");
        this.schema = toSchema(objectMapper, readResource("prompts/return-classification-schema.json"));
    }

    @Override
    public Optional<Classification> classify(String reason) {
        if (reason == null || reason.isBlank()) return Optional.empty();

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    // 자유 텍스트를 파싱하지 않는다 — 비결정적 응답을 스키마 안에 가두는 것이 이 기능의 핵심이다.
                    .outputConfig(OutputConfig.builder()
                            .format(JsonOutputFormat.builder().schema(schema).build())
                            .build())
                    .addUserMessage(reason)
                    .build();

            Message message = client.messages().create(params);
            String json = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());

            return parser.parse(json).map(parsed -> new Classification(
                    parsed.category(), parsed.confidence(), parsed.evidence(),
                    parsed.suggestedDisposition(),
                    message.model().asString(),
                    (int) message.usage().inputTokens(),
                    (int) message.usage().outputTokens()));
        } catch (Exception e) {
            // 타임아웃·연결 실패·SDK 예외를 전부 여기서 끊는다 — 인터페이스 계약이 "실패는 empty"다.
            log.warn("반품 사유 분류 호출 실패(무시): {}", e.toString());
            return Optional.empty();
        }
    }

    private static String readResource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("분류 리소스를 읽지 못했습니다: " + path, e);
        }
    }

    /** Schema는 자유형 맵이라 JSON 스키마 파일을 그대로 얹는다. */
    private static JsonOutputFormat.Schema toSchema(ObjectMapper objectMapper, String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
            map.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("분류 스키마가 올바른 JSON이 아닙니다.", e);
        }
    }
}
```

- [ ] **Step 5: 설정 클래스 작성**

`src/main/java/com/jhg/wms/config/AiConfig.java`:
```java
package com.jhg.wms.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.wms.client.ClaudeReturnReasonClassifier;
import com.jhg.wms.service.ReturnReasonClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Configuration
public class AiConfig {

    /**
     * API 키가 없으면 항상 empty를 내는 구현으로 대체한다.
     * wms.basic·oms.callback은 없으면 통신이 전면 실패하므로 prod에서 기동을 막지만,
     * 분류는 없어도 창고 업무가 돌아간다 — 여기서 기동을 막으면 잃는 것이 더 크다.
     */
    @Bean
    public ReturnReasonClassifier returnReasonClassifier(
            @Value("${wms.ai.api-key:}") String apiKey,
            @Value("${wms.ai.model}") String model,
            @Value("${wms.ai.max-tokens}") long maxTokens,
            @Value("${wms.ai.timeout}") Duration timeout,
            ObjectMapper objectMapper) {

        if (apiKey == null || apiKey.isBlank()) {
            log.info("ANTHROPIC_API_KEY 미설정 — 반품 사유 자동 분류를 끈 채로 기동합니다.");
            return reason -> Optional.empty();
        }

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                // SDK 기본 타임아웃은 10분이다. 참고 정보 하나 때문에 스레드를 그만큼 붙잡을 이유가 없다.
                .timeout(timeout)
                // 실패해도 재시도 스윕이 없는 설계라 여기서 한 번만 더 시도한다.
                .maxRetries(1)
                .build();
        log.info("반품 사유 자동 분류 활성: model={} maxTokens={} timeout={}", model, maxTokens, timeout);
        return new ClaudeReturnReasonClassifier(client, objectMapper, model, maxTokens);
    }
}
```

- [ ] **Step 6: application.yml에 설정 추가**

`src/main/resources/application.yml`의 `wms:` 블록 안, `seed-users:` 블록 **아래**에 추가(들여쓰기 2칸):
```yaml
  # 반품 사유 자동 분류(V4.0). 키가 없으면 분류만 꺼진 채로 기동한다 —
  # basic·callback과 달리 분류는 없어도 창고 업무가 돌아가므로 fail-fast 대상이 아니다.
  ai:
    api-key: ${ANTHROPIC_API_KEY:}
    # 분류는 짧은 단일 호출이라 Haiku로 충분하다(입력 약 400·출력 약 120 토큰, 건당 약 1.4원).
    # Opus/Sonnet 5 계열로 바꾸면 확장 사고가 기본으로 켜져 max-tokens를 함께 올려야 한다.
    model: ${WMS_AI_MODEL:claude-haiku-4-5}
    max-tokens: 1024
    timeout: 20s
```

`src/test/resources/application.yml`에도 같은 블록을 추가하되 키는 비운다 — 테스트가 실제 API를 부르지 않는다는 성질을 설정으로 못 박는다. `wms:` 블록의 `seed-users:` 아래:
```yaml
  # 테스트는 실제 API를 부르지 않는다 — 키를 비워 AiConfig가 비활성 분류기를 만들게 한다.
  ai:
    api-key: ""
    model: claude-haiku-4-5
    max-tokens: 1024
    timeout: 20s
```

- [ ] **Step 7: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*AiConfigTest'
```
Expected: PASS (2개)

- [ ] **Step 8: 컨텍스트 기동 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests 'JhgWmsApplicationTests'
```
Expected: PASS — 키 없이도 컨텍스트가 뜨고, 로그에 `ANTHROPIC_API_KEY 미설정` 한 줄이 보인다.

- [ ] **Step 9: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 349건

- [ ] **Step 10: 커밋**

```bash
git add build.gradle \
        src/main/java/com/jhg/wms/client/ClaudeReturnReasonClassifier.java \
        src/main/java/com/jhg/wms/config/AiConfig.java \
        src/main/resources/application.yml src/test/resources/application.yml \
        src/test/java/com/jhg/wms/config/AiConfigTest.java
git commit -m "$(cat <<'EOF'
feat(wms): Anthropic SDK 어댑터를 붙이고 키 없이도 기동하게 한다

출력은 구조화 출력(JSON 스키마)으로 강제한다. 자유 텍스트를 파싱하면 모델이
말투를 바꿀 때마다 우리 코드가 깨지므로, 비결정적 응답을 결정적 경계 안에 가둔다.

키가 없으면 항상 empty를 내는 구현으로 대체한다. wms.basic·oms.callback은
없으면 통신이 전면 실패해 prod 기동을 막지만, 분류는 없어도 창고 업무가 돈다.

SDK 기본 타임아웃 10분을 20초로 줄였다. 참고 정보 하나 때문에 배경 스레드를
10분 붙잡을 이유가 없고, 재시도 스윕이 없는 설계라 재시도도 1회로 제한한다.

테스트 설정에서 키를 비워, 자동 테스트가 실제 API를 부르지 않는다는 성질을
설정 파일에 못 박았다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 반품 상세 화면 — 참고 영역

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/RmaAdminController.java`
- Modify: `src/main/resources/templates/admin/returndetail.html`
- Modify: `src/main/resources/static/css/admin.css`
- Modify test: `src/test/java/com/jhg/wms/web/RmaAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 2의 `ReturnClassificationService.findByRmaId(Long)`
- Produces: 모델 속성 `classification` (`ReturnClassification` 또는 `null`)

**불변 원칙:** 검수 폼의 `승인 수량` input과 `처분` select는 분류가 있든 없든 **여전히 비어 있다.** V2.1에서 "되돌릴 수 없는 확정에 기본값을 두지 않는다"를 세웠고, AI 제안이 그 예외가 되지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jhg/wms/web/RmaAdminControllerTest.java`에 추가.

(1) 필드 추가 (`@MockitoBean InventoryService inventoryService;` 아래):
```java
    @MockitoBean ReturnClassificationService classificationService;
```

(2) 임포트 추가:
```java
import com.jhg.wms.domain.Confidence;
import com.jhg.wms.domain.ReturnCategory;
import com.jhg.wms.domain.ReturnClassification;
import com.jhg.wms.service.ReturnClassificationService;
import java.util.Optional;
```

(3) 테스트 세 개 추가 (클래스 끝):
```java
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

        // 승인 수량 input에 value가 채워지면 안 된다
        assertThat(html).containsPattern("name=\"items\\[0\\]\\.acceptedQuantity\"[^>]*")
                .doesNotContainPattern(
                        "name=\"items\\[0\\]\\.acceptedQuantity\"[^>]*value=\"[^\"]+\"");
        // 처분 select에서 선택된 것은 플레이스홀더뿐이다
        assertThat(html).contains("<option value=\"\" disabled selected>선택하세요</option>");
        assertThat(html).doesNotContainPattern("<option value=\"DISPOSED\"[^>]*selected");
    }
```

임포트 추가:
```java
import static org.assertj.core.api.Assertions.assertThat;
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*RmaAdminControllerTest'
```
Expected: 새 테스트 3개 중 최소 2개 FAIL — `분류 제안`이 렌더되지 않음

- [ ] **Step 3: 컨트롤러 수정**

`src/main/java/com/jhg/wms/web/RmaAdminController.java`:

(1) 임포트 추가:
```java
import com.jhg.wms.service.ReturnClassificationService;
```

(2) 필드 추가 (`private final InventoryService inventoryService;` 아래):
```java
    private final ReturnClassificationService returnClassificationService;
```

(3) `detail` 메서드에 한 줄 추가 (`model.addAttribute("productNames", ...)` 아래, `return` 위):
```java
        // 없으면 null — 템플릿이 참고 영역을 통째로 건너뛴다.
        model.addAttribute("classification",
                returnClassificationService.findByRmaId(id).orElse(null));
```

- [ ] **Step 4: 템플릿 수정**

`src/main/resources/templates/admin/returndetail.html`에서 상태를 보여주는 `</p>` 바로 다음, `<!-- RECEIVED 상태: 검수 완료 폼 -->` 앞에 삽입:
```html
  <!-- AI 분류 참고 영역. 분류가 없으면 통째로 렌더하지 않는다 — 빈 껍데기를 두지 않기 위해서다.
       검수 폼과 시각적으로 갈라 둔다: 제안이 입력칸의 기본값처럼 보이면 원칙이 무너진다. -->
  <section class="ai-hint" th:if="${classification != null}">
    <h3>분류 제안 <span class="ai-hint__tag">참고</span></h3>
    <dl>
      <dt>분류</dt>
      <dd>
        <span th:switch="${classification.category.name()}">
          <span th:case="'DAMAGED'">파손</span>
          <span th:case="'WRONG_ITEM'">오배송</span>
          <span th:case="'CHANGED_MIND'">변심</span>
          <span th:case="*">기타</span>
        </span>
        · 신뢰도
        <span th:switch="${classification.confidence.name()}">
          <span th:case="'HIGH'">높음</span>
          <span th:case="'MEDIUM'">보통</span>
          <span th:case="*">낮음</span>
        </span>
      </dd>
      <dt>근거</dt>
      <dd th:text="${#strings.isEmpty(classification.evidence) ? '—' : classification.evidence}">—</dd>
      <dt>처분 제안</dt>
      <dd th:switch="${classification.suggestedDisposition?.name()}">
        <span th:case="'RESTOCKED'">재입고</span>
        <span th:case="'DISPOSED'">폐기</span>
        <span th:case="'REJECTED'">거절</span>
        <span th:case="*">—</span>
      </dd>
    </dl>
    <p class="ai-hint__note">참고용입니다. 확정은 아래에서 직접 입력하세요.</p>
  </section>
```

- [ ] **Step 5: CSS 추가**

`src/main/resources/static/css/admin.css` 끝에 추가:
```css
/* AI 분류 참고 영역 — 검수 폼과 시각적으로 갈라 둔다.
   제안이 입력의 기본값처럼 보이면 "확정에 기본값을 두지 않는다"가 무너진다. */
.ai-hint {
  margin: 16px 0;
  padding: 14px 18px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius);
  background: #fbfbfd;
}
.ai-hint h3 { margin: 0 0 10px; font-size: 1em; }
.ai-hint__tag {
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: 10px;
  background: var(--color-border);
  color: var(--color-text-muted);
  font-size: .78em;
  font-weight: 500;
}
.ai-hint dl { display: grid; grid-template-columns: 76px 1fr; gap: 6px 12px; margin: 0; }
.ai-hint dt { color: var(--color-text-muted); font-size: .9em; }
.ai-hint dd { margin: 0; }
.ai-hint__note { margin: 12px 0 0; color: var(--color-text-muted); font-size: .88em; }
```

- [ ] **Step 6: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*RmaAdminControllerTest'
```
Expected: PASS (기존 + 3개)

- [ ] **Step 7: 단언이 진짜 무는지 확인**

`returndetail.html`의 `<h3>분류 제안` 을 잠시 `<h3>AI 결과` 로 바꾸고 실행:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests '*RmaAdminControllerTest'
```
Expected: `분류가_있으면_한글_라벨로_참고영역을_렌더한다` FAIL.
확인 후 **원래대로 되돌린다**.

- [ ] **Step 8: 전체 스위트 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL, 352건

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/jhg/wms/web/RmaAdminController.java \
        src/main/resources/templates/admin/returndetail.html \
        src/main/resources/static/css/admin.css \
        src/test/java/com/jhg/wms/web/RmaAdminControllerTest.java
git commit -m "$(cat <<'EOF'
feat(wms): 반품 상세에 분류 제안을 참고 정보로만 표시한다

제안과 입력칸을 시각적으로 갈라 뒀다(점선 박스·"참고" 태그). 제안이 입력의
기본값처럼 보이면 V2.1에서 세운 "되돌릴 수 없는 확정에 기본값을 두지 않는다"가
무너진다. 승인 수량과 처분은 분류가 있든 없든 여전히 비어 있고, 테스트로 고정했다.

분류가 없으면 영역을 통째로 렌더하지 않는다 — 빈 껍데기가 남으면 "분류가 실패했다"와
"분류가 무의미하다"를 화면에서 구분할 수 없다.

enum 원문은 노출하지 않는다(파손/오배송/변심/기타, 높음/보통/낮음).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 문서 현행화와 수동 스모크

**Files:**
- Modify: `README.md`
- Modify: `docs/oms-wms-manual-verification.md`

**Interfaces:**
- Consumes: 앞선 여섯 태스크 전부
- Produces: 없음(문서)

- [ ] **Step 1: 테스트 수 확인**

Run:
```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test
```
`build/reports/tests/test/index.html`에서 최종 건수를 읽는다. 아래 문서 수정에서 **실제 값**을 쓴다(예상 352이나 실측을 우선한다).

- [ ] **Step 2: README 갱신**

(1) 16번째 줄 근처 테스트 수 갱신:
```
| 테스트 | <실측값>개 (도메인 · 서비스 · MockMvc 슬라이스 · 실서블릿 보안 통합 · **실제 동시 요청 경합**) |
```

(2) 기능 설명 섹션에 항목 추가(반품/RMA를 설명하는 절 끝):
```markdown
### 반품 사유 자동 분류 (V4.0)

고객이 자유 텍스트로 쓴 반품 사유를 Claude로 분류해 반품 상세 화면에 **참고 정보**로 표시합니다.
분류는 재고를 바꾸지도, 상태를 전이시키지도 않고, 검수 폼의 입력칸을 채우지도 않습니다 —
되돌릴 수 없는 확정은 사람이 물건을 보고 입력합니다.

- 접수 트랜잭션 **커밋 후 별도 스레드**에서 시도합니다. `POST /api/returns`는 OMS가 부르는 API라,
  응답이 LLM 지연에 묶이면 안 됩니다.
- 응답은 **구조화 출력(JSON 스키마)** 으로 강제하고, 그 JSON도 방어적으로 파싱합니다.
  enum 이탈·필드 누락·잘린 응답은 전부 "분류 없음"으로 떨어집니다.
- `ANTHROPIC_API_KEY`가 없으면 **분류만 꺼진 채로 기동**합니다.
  `wms.basic`·`oms.callback`과 달리 분류는 없어도 창고 업무가 돌아가기 때문입니다.
- 건별 입·출력 토큰을 엔티티와 로그에 남깁니다("얼마 드는지 모른다"를 피하려고).

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `ANTHROPIC_API_KEY` | (없음) | 미설정 시 분류 비활성 |
| `WMS_AI_MODEL` | `claude-haiku-4-5` | 짧은 단일 호출이라 Haiku로 충분 |
| `wms.ai.max-tokens` | `1024` | 모델을 상위 계열로 바꾸면 함께 올릴 것 |
| `wms.ai.timeout` | `20s` | SDK 기본 10분을 줄임 |
```

- [ ] **Step 3: 수동 검증 항목 추가**

`docs/oms-wms-manual-verification.md` 끝에 추가:
```markdown
## 2026-08-30 — V4.0 반품 사유 자동 분류

### ⑪ 실제 API 스모크 (분류 1건)

**전제**: `export ANTHROPIC_API_KEY=...` 후 WMS 기동. OMS는 내려도 된다.

1. 기동 로그에 `반품 사유 자동 분류 활성: model=claude-haiku-4-5 ...` 가 보이는지 확인
2. 반품 접수(OMS 경유 또는 직접):
   ```bash
   curl -u wms:wms -X POST http://localhost:8081/api/returns \
     -H 'Content-Type: application/json' \
     -d '{"requestKey":"RMA-SMOKE-1","orderId":<출고된 주문>,"reason":"받았는데 모서리가 깨져 있어요","items":[{"orderItemId":1,"productId":1,"quantity":1}]}'
   ```
3. **응답이 즉시 온다**(분류를 기다리지 않는다) — 이것이 커밋 후 별도 스레드로 뺀 이유다
4. 잠시 후 로그에서 확인:
   `반품 사유 분류: rmaId=.. category=DAMAGED confidence=HIGH model=claude-haiku-4-5 in=.. out=..`
5. `/admin/returns/{id}` 에서 참고 영역이 한글 라벨로 보이고, **승인 수량·처분 입력칸이 비어 있는지** 확인

**기록할 것**: 분류 결과(category/confidence/evidence/처분 제안), 입력·출력 토큰, 응답 체감 지연

| 항목 | 결과 |
|------|------|
| 접수 응답 지연 | |
| category / confidence | |
| evidence | |
| 처분 제안 | |
| 입력 / 출력 토큰 | |
| 입력칸 비어 있음 | |

### ⑫ 키 없이 기동

`ANTHROPIC_API_KEY`를 지우고 기동 → 로그에 `ANTHROPIC_API_KEY 미설정 — ... 끈 채로 기동합니다.`
접수는 정상 동작하고, 반품 상세에 참고 영역이 아예 렌더되지 않는지 확인.

### ⑬ 사유가 빈 반품

`"reason"` 없이 접수 → 분류 호출 자체가 없어야 한다(로그에 분류 관련 줄이 없음).
```

- [ ] **Step 4: 커밋**

```bash
git add README.md docs/oms-wms-manual-verification.md
git commit -m "$(cat <<'EOF'
docs(wms): V4.0 반품 사유 분류를 README와 수동 검증에 반영

자동 테스트가 증명하지 못하는 것을 수동 항목으로 남겼다 — 실제 모델이 무엇을
내놓는지, 접수 응답이 정말 분류를 기다리지 않는지, 키가 없을 때 기동이 멀쩡한지.
고정 JSON 테스트는 "스키마를 어겼을 때 우리가 어떻게 행동하는가"를 보지만,
"모델이 이 사유를 어떻게 읽는가"는 실제 호출로만 알 수 있다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## 구현 중 확인이 필요한 지점 하나

`JsonOutputFormat.builder()`에는 `type(JsonValue)` 세터가 있고, 기본값이 `json_schema`로 채워질 것으로 보인다(jar에서 `_type()`이 기본값을 가진 `JsonValue` 필드로 확인됨). **Task 5의 수동 스모크(항목 ⑪)에서 400 `invalid_request_error`가 나면** 그 때 명시한다:

```java
JsonOutputFormat.builder()
        .type(JsonValue.from("json_schema"))
        .schema(schema)
        .build()
```

자동 테스트는 실제 호출을 하지 않으므로 이 지점은 스모크에서만 드러난다. 스모크 전에는 코드를 바꾸지 않는다.

## Self-Review

**스펙 커버리지**

| 스펙 절 | 태스크 |
|---------|--------|
| 데이터 모델(별도 엔티티, `RmaDisposition` 재사용, 토큰 저장) | 1 |
| 호출 경계(인터페이스, 실패는 empty) | 2 |
| 언제 부르는가(커밋 후), 빈 사유 스킵, 재시도 스윕 없음 | 2·3 |
| 실패했을 때 4종(타임아웃·스키마 위반·빈 사유·키 미설정) | 2·4·5 |
| 프롬프트 리소스 파일 · 구조화 출력 | 4·5 |
| 비용 관측(엔티티 + 로그) | 1·2 |
| 화면(참고 영역, 없으면 미렌더, 한글 라벨, 입력칸 유지) | 6 |
| 테스트 4계층(서비스·어댑터·화면·수동) | 2·4·6·7 |
| 비범위(eval·재분류 UI·자동 처분·다국어·캐싱·배치) | 어느 태스크에도 없음 ✓ |

**타입 일관성**: `Classification`(7필드)은 Task 2에서 정의되고 Task 5가 생성한다. `Parsed`(4필드)는 Task 4에서 정의되고 Task 5가 `Classification`으로 승격한다. `classifyAndSave(Long, String)` 시그니처가 Task 2·3에서 동일하다. `findByRmaId`는 Task 2에서 정의되고 Task 6이 소비한다.

**플레이스홀더 없음**: 모든 코드 스텝에 완전한 코드, 모든 실행 스텝에 정확한 명령과 기대 출력이 들어 있다.
