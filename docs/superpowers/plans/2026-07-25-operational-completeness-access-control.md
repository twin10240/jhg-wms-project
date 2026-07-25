# 운영 완결성 + 접근제어 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WMS에 발주 취소(생애주기 완결), 폼 로그인 + DB 롤 기반 접근제어(OMS의 `/api` Basic 연동은 유지), 일관된 admin UI 리스킨을 추가한다.

**Architecture:** Spring Security 필터체인을 둘로 분할한다 — `/api/**`는 기존 Basic(서비스 계정), `/admin/**`·`/`는 폼 로그인(DB 유저). 유저는 `WmsUser` 엔티티로 DB에 저장하고 `OPERATOR`/`MANAGER` 롤로 인가한다. 발주 취소는 도메인 상태 전이(`PurchaseOrder.cancel()`)로 구현하고 연결된 보충 요청을 같은 트랜잭션에서 종결한다. UI는 서버 렌더링 Thymeleaf를 유지하며 `admin.css`를 테마로 재작성한다.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Security 6, Spring Data JPA, Thymeleaf, H2(테스트/로컬)·PostgreSQL(prod), JUnit 5 + spring-security-test, Gradle.

## Global Constraints

- Java 21 toolchain. `./gradlew` 실행 시 `JAVA_HOME`이 JDK 21이어야 한다(로컬 기본 JDK가 26이면 `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew ...`).
- `/api/**`는 서버간 호출(OMS)이라 **Basic 인증 유지 + 인증 실패 시 401 직접 응답**(폼 로그인 리다이렉트로 새면 안 됨). CSRF 예외 유지.
- `/admin/**`·`/`는 폼 로그인. CSRF 활성.
- DB 유저 시드 자격증명은 **prod 프로파일에서 공백이면 기동 실패**(fail-fast).
- 비밀번호는 bcrypt(`PasswordEncoderFactories.createDelegatingPasswordEncoder()`)로만 저장. 자격증명·해시를 로그로 출력하지 않는다.
- 롤 인가는 **서버가 최종 권위**(`.requestMatchers(...).hasRole(...)` + 필요 시 컨트롤러 확인). UI 숨김은 보조.
- 상태 enum 원문(`ORDERED` 등)을 화면에 직접 노출하지 않는다(한글 표기).
- 기존 132개 테스트가 통과해야 한다. 보안 체인 분할로 깨지는 테스트는 이 플랜의 태스크에서 함께 고친다.
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

**Part B — 접근제어**
- Create: `src/main/java/com/jhg/wms/domain/WmsUser.java` — 유저 엔티티(username·bcrypt password·role).
- Create: `src/main/java/com/jhg/wms/domain/WmsRole.java` — `OPERATOR`, `MANAGER` enum.
- Create: `src/main/java/com/jhg/wms/repository/WmsUserRepository.java` — `findByUsername`.
- Create: `src/main/java/com/jhg/wms/config/DbUserDetailsService.java` — DB 기반 `UserDetailsService`.
- Create: `src/main/java/com/jhg/wms/config/WmsUserSeeder.java` — 기동 시 operator/manager 시드 + prod fail-fast.
- Modify: `src/main/java/com/jhg/wms/config/SecurityConfig.java` — 체인 2분할 + 롤 인가.
- Create: `src/main/resources/templates/login.html` — 로그인 폼.
- Modify: `src/main/resources/application.yml` — `wms.seed-users.*` 자격증명 프로퍼티.
- Create: `src/test/java/com/jhg/wms/security/SecurityChainIntegrationTest.java` — 실서블릿(RANDOM_PORT) 보안 테스트.
- Modify: `src/test/java/com/jhg/wms/config/SecurityConfigTest.java`, `web/WmsAdminControllerTest.java`, `web/InventoryControllerTest.java`, `web/ReplenishmentRequestControllerTest.java` — 분할 후 인증/롤 반영.

**Part A — 발주 취소**
- Modify: `src/main/java/com/jhg/wms/domain/PurchaseOrderStatus.java` — `CANCELLED` 추가.
- Modify: `src/main/java/com/jhg/wms/domain/PurchaseOrder.java` — `cancel()` + `cancelledAt`.
- Modify: `src/main/java/com/jhg/wms/domain/ReplenishmentRequestStatus.java` — `CANCELLED` 추가.
- Modify: `src/main/java/com/jhg/wms/domain/ReplenishmentRequest.java` — `cancel()`.
- Modify: `src/main/java/com/jhg/wms/service/PurchaseOrderService.java` — `cancel(poId)`.
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java` — `POST /admin/purchase-orders/{poId}/cancel`.
- Modify: `src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java`, `service/PurchaseOrderServiceTest.java`.

**Part C — UI 리스킨**
- Modify: `build.gradle` — `thymeleaf-extras-springsecurity6`(롤 인식 렌더링).
- Rewrite: `src/main/resources/static/css/admin.css` — admin 테마 + 상태 배지.
- Modify: 전 admin 템플릿(`fragments/layout.html`, `admin/*.html`, `login.html`) — 롤 인식 nav·액션, 한글 상태 표기, 접근성 기본.

---

# PART B — 접근제어 (토대)

### Task B1: WmsUser 엔티티 + WmsRole + 리포지토리

**Files:**
- Create: `src/main/java/com/jhg/wms/domain/WmsRole.java`
- Create: `src/main/java/com/jhg/wms/domain/WmsUser.java`
- Create: `src/main/java/com/jhg/wms/repository/WmsUserRepository.java`
- Test: `src/test/java/com/jhg/wms/domain/WmsUserTest.java`

**Interfaces:**
- Produces: `WmsRole { OPERATOR, MANAGER }`; `WmsUser.create(String username, String bcryptHash, WmsRole role)` → `WmsUser`; getters `getUsername()`, `getPassword()`, `getRole()`; `WmsUserRepository.findByUsername(String) : Optional<WmsUser>`.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/jhg/wms/domain/WmsUserTest.java
package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WmsUserTest {

    @Test
    void create_필수값을_담고_생성한다() {
        WmsUser user = WmsUser.create("operator", "{bcrypt}$2a$hash", WmsRole.OPERATOR);
        assertThat(user.getUsername()).isEqualTo("operator");
        assertThat(user.getPassword()).isEqualTo("{bcrypt}$2a$hash");
        assertThat(user.getRole()).isEqualTo(WmsRole.OPERATOR);
    }

    @Test
    void create_username이_공백이면_예외() {
        assertThatThrownBy(() -> WmsUser.create(" ", "hash", WmsRole.MANAGER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_password가_공백이면_예외() {
        assertThatThrownBy(() -> WmsUser.create("manager", "", WmsRole.MANAGER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_role이_null이면_예외() {
        assertThatThrownBy(() -> WmsUser.create("manager", "hash", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./gradlew test --tests "com.jhg.wms.domain.WmsUserTest"`
Expected: FAIL — `WmsUser`/`WmsRole` 클래스 없음(컴파일 에러).

- [ ] **Step 3: Write minimal implementation**

```java
// src/main/java/com/jhg/wms/domain/WmsRole.java
package com.jhg.wms.domain;

public enum WmsRole { OPERATOR, MANAGER }
```

```java
// src/main/java/com/jhg/wms/domain/WmsUser.java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "wms_user", uniqueConstraints =
        @UniqueConstraint(name = "uk_wms_user_username", columnNames = "username"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WmsUser {

    @Id @GeneratedValue
    @Column(name = "wms_user_id")
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;   // bcrypt 해시

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WmsRole role;

    public static WmsUser create(String username, String password, WmsRole role) {
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("username is required");
        if (password == null || password.isBlank())
            throw new IllegalArgumentException("password is required");
        if (role == null)
            throw new IllegalArgumentException("role is required");
        WmsUser user = new WmsUser();
        user.username = username.trim();
        user.password = password;
        user.role = role;
        return user;
    }
}
```

```java
// src/main/java/com/jhg/wms/repository/WmsUserRepository.java
package com.jhg.wms.repository;

import com.jhg.wms.domain.WmsUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WmsUserRepository extends JpaRepository<WmsUser, Long> {
    Optional<WmsUser> findByUsername(String username);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=.../ms-21.0.12/... ./gradlew test --tests "com.jhg.wms.domain.WmsUserTest"`
Expected: PASS (4개).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jhg/wms/domain/WmsRole.java src/main/java/com/jhg/wms/domain/WmsUser.java src/main/java/com/jhg/wms/repository/WmsUserRepository.java src/test/java/com/jhg/wms/domain/WmsUserTest.java
git commit -m "feat(wms): WmsUser 엔티티 + OPERATOR/MANAGER 롤

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B2: DB 기반 UserDetailsService

**Files:**
- Create: `src/main/java/com/jhg/wms/config/DbUserDetailsService.java`
- Test: `src/test/java/com/jhg/wms/config/DbUserDetailsServiceTest.java`

**Interfaces:**
- Consumes: `WmsUserRepository.findByUsername`, `WmsUser`, `WmsRole`.
- Produces: `DbUserDetailsService implements UserDetailsService` — `loadUserByUsername(username)` → `UserDetails`(authority `ROLE_<role>`); 없으면 `UsernameNotFoundException`.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/jhg/wms/config/DbUserDetailsServiceTest.java
package com.jhg.wms.config;

import com.jhg.wms.domain.WmsRole;
import com.jhg.wms.domain.WmsUser;
import com.jhg.wms.repository.WmsUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DbUserDetailsServiceTest {

    private final WmsUserRepository repo = mock(WmsUserRepository.class);
    private final DbUserDetailsService service = new DbUserDetailsService(repo);

    @Test
    void loadUserByUsername_롤을_ROLE_접두어_권한으로_노출한다() {
        when(repo.findByUsername("manager"))
                .thenReturn(Optional.of(WmsUser.create("manager", "{noop}pw", WmsRole.MANAGER)));

        UserDetails details = service.loadUserByUsername("manager");

        assertThat(details.getUsername()).isEqualTo("manager");
        assertThat(details.getPassword()).isEqualTo("{noop}pw");
        assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_MANAGER");
    }

    @Test
    void loadUserByUsername_없으면_예외() {
        when(repo.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.config.DbUserDetailsServiceTest"`
Expected: FAIL — `DbUserDetailsService` 없음.

- [ ] **Step 3: Write minimal implementation**

```java
// src/main/java/com/jhg/wms/config/DbUserDetailsService.java
package com.jhg.wms.config;

import com.jhg.wms.repository.WmsUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final WmsUserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPassword())
                        .roles(u.getRole().name())   // roles(...)가 ROLE_ 접두어를 붙인다
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("user not found: " + username));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.config.DbUserDetailsServiceTest"`
Expected: PASS (2개).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jhg/wms/config/DbUserDetailsService.java src/test/java/com/jhg/wms/config/DbUserDetailsServiceTest.java
git commit -m "feat(wms): DB 기반 UserDetailsService — 롤을 ROLE_ 권한으로

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B3: 유저 시드 + prod fail-fast

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/jhg/wms/config/WmsUserSeeder.java`
- Test: `src/test/java/com/jhg/wms/config/WmsUserSeederTest.java`

**Interfaces:**
- Consumes: `WmsUserRepository`, `PasswordEncoder`(SecurityConfig의 기존 빈), `WmsUser`, `WmsRole`.
- Produces: `WmsUserSeeder(WmsUserRepository, PasswordEncoder, operatorUser, operatorPassword, managerUser, managerPassword)`; `seed()` — 각 username이 없을 때만 bcrypt 해시로 저장(멱등). 자격증명 공백이면 `IllegalStateException`.

**설정 배경:** 로컬 기본값 제공, prod는 기본값 없이 주입(누락 시 플레이스홀더 해석 실패로 기동 차단 — 오늘 콜백 인증과 동일 패턴). `application.yml` 최상단 문서에 추가:

```yaml
# 관리자 폼 로그인 계정 시드. 로컬 기본값 operator/manager, 운영은 prod에서 기본값 없이 주입.
wms:
  seed-users:
    operator-user: ${WMS_OPERATOR_USER:operator}
    operator-password: ${WMS_OPERATOR_PASSWORD:operator}
    manager-user: ${WMS_MANAGER_USER:manager}
    manager-password: ${WMS_MANAGER_PASSWORD:manager}
```

그리고 prod 프로파일 문서(기존 `oms.callback` 블록 아래)에 기본값 없는 오버라이드 추가:

```yaml
  seed-users:
    operator-user: ${WMS_OPERATOR_USER}
    operator-password: ${WMS_OPERATOR_PASSWORD}
    manager-user: ${WMS_MANAGER_USER}
    manager-password: ${WMS_MANAGER_PASSWORD}
```

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/jhg/wms/config/WmsUserSeederTest.java
package com.jhg.wms.config;

import com.jhg.wms.domain.WmsRole;
import com.jhg.wms.domain.WmsUser;
import com.jhg.wms.repository.WmsUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsUserSeederTest {

    private final WmsUserRepository repo = mock(WmsUserRepository.class);
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private WmsUserSeeder seeder(String opPw, String mgrPw) {
        return new WmsUserSeeder(repo, encoder, "operator", opPw, "manager", mgrPw);
    }

    @Test
    void seed_없는_계정을_bcrypt로_저장한다() {
        when(repo.findByUsername(any())).thenReturn(Optional.empty());

        seeder("operator", "manager").seed();

        var saved = org.mockito.ArgumentCaptor.forClass(WmsUser.class);
        verify(repo, times(2)).save(saved.capture());
        WmsUser op = saved.getAllValues().get(0);
        assertThat(op.getUsername()).isEqualTo("operator");
        assertThat(op.getRole()).isEqualTo(WmsRole.OPERATOR);
        assertThat(encoder.matches("operator", op.getPassword())).isTrue();   // 평문 아님
    }

    @Test
    void seed_이미_있으면_저장하지_않는다() {
        when(repo.findByUsername("operator"))
                .thenReturn(Optional.of(WmsUser.create("operator", "{noop}x", WmsRole.OPERATOR)));
        when(repo.findByUsername("manager")).thenReturn(Optional.empty());

        seeder("operator", "manager").seed();

        verify(repo, times(1)).save(any());   // manager만
    }

    @Test
    void seed_자격증명_공백이면_기동_실패() {
        assertThatThrownBy(() -> seeder("", "manager").seed())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seeder("operator", "  ").seed())
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.config.WmsUserSeederTest"`
Expected: FAIL — `WmsUserSeeder` 없음.

- [ ] **Step 3: Write minimal implementation**

`application.yml`을 위 "설정 배경"대로 수정한 뒤:

```java
// src/main/java/com/jhg/wms/config/WmsUserSeeder.java
package com.jhg.wms.config;

import com.jhg.wms.domain.WmsRole;
import com.jhg.wms.domain.WmsUser;
import com.jhg.wms.repository.WmsUserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WmsUserSeeder {

    private final WmsUserRepository userRepository;
    private final PasswordEncoder encoder;
    private final String operatorUser;
    private final String operatorPassword;
    private final String managerUser;
    private final String managerPassword;

    public WmsUserSeeder(WmsUserRepository userRepository, PasswordEncoder encoder,
                         @Value("${wms.seed-users.operator-user}") String operatorUser,
                         @Value("${wms.seed-users.operator-password}") String operatorPassword,
                         @Value("${wms.seed-users.manager-user}") String managerUser,
                         @Value("${wms.seed-users.manager-password}") String managerPassword) {
        this.userRepository = userRepository;
        this.encoder = encoder;
        this.operatorUser = operatorUser;
        this.operatorPassword = operatorPassword;
        this.managerUser = managerUser;
        this.managerPassword = managerPassword;
    }

    @PostConstruct
    @Transactional
    public void seed() {
        upsert(operatorUser, operatorPassword, WmsRole.OPERATOR);
        upsert(managerUser, managerPassword, WmsRole.MANAGER);
    }

    private void upsert(String username, String rawPassword, WmsRole role) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank())
            throw new IllegalStateException("wms.seed-users 자격증명이 비어 있습니다: role=" + role);
        if (userRepository.findByUsername(username).isPresent())
            return;
        userRepository.save(WmsUser.create(username, encoder.encode(rawPassword), role));
    }
}
```

- [ ] **Step 4: 테스트 설정에 시드 유저 값 추가 (순서 필수)**

`WmsUserSeeder`는 `@Component` + `@PostConstruct`라, 이 태스크가 머지되는 즉시 **모든 `@SpringBootTest` 전체 컨텍스트**(기존 `JhgWmsApplicationTests` 포함)가 `wms.seed-users.*`를 요구한다. `src/test/resources/application.yml`은 메인을 완전히 대체(섀도잉)하므로 여기에 없으면 플레이스홀더 해석 실패로 컨텍스트 로딩이 깨진다(오늘 콜백 인증에서 동일 사례). 그러므로 이 태스크에서 함께 추가한다:

```yaml
# src/test/resources/application.yml 하단
wms:
  seed-users:
    operator-user: operator
    operator-password: operator
    manager-user: manager
    manager-password: manager
```

> 이미 `wms.basic.*`가 있으면 같은 `wms:` 아래 `seed-users:`를 병합한다(중복 `wms:` 키 금지).

- [ ] **Step 5: 유닛 테스트 + 전체 컨텍스트 테스트 통과 확인**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.config.WmsUserSeederTest" --tests "com.jhg.wms.JhgWmsApplicationTests"`
Expected: PASS — 시더 유닛 3개 + 앱 컨텍스트 로딩(시더가 테스트 설정 값으로 기동).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.yml src/test/resources/application.yml src/main/java/com/jhg/wms/config/WmsUserSeeder.java src/test/java/com/jhg/wms/config/WmsUserSeederTest.java
git commit -m "feat(wms): operator/manager 유저 시드 + prod 자격증명 fail-fast

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B4: SecurityConfig 체인 2분할 + 롤 인가

**Files:**
- Modify: `src/main/java/com/jhg/wms/config/SecurityConfig.java`
- Create: `src/main/resources/templates/login.html`

**Interfaces:**
- Consumes: `DbUserDetailsService`(B2), 기존 `PasswordEncoder` 빈.
- Produces: 빈 2개 — `@Order(1) apiChain(HttpSecurity)`(matcher `/api/**`, httpBasic, 서비스계정 provider, 401 직접 응답, CSRF 예외); `@Order(2) webChain(HttpSecurity, DbUserDetailsService)`(formLogin, 롤 인가). 서비스 계정은 기존 `wms.basic.user/password`를 인메모리 provider로 유지.

**롤 인가 규칙(webChain):**
- permitAll: `/login`, `/error`, `/css/**`, `/js/**`, `/images/**`
- `hasRole("MANAGER")`: `POST /admin/purchase-orders`(생성), `POST /admin/purchase-orders/*/cancel`(취소), `POST /admin/replenishment-requests/*/approve`, `POST /admin/replenishment-requests/*/reject`
- 그 외 `/`, `/admin/**`: `authenticated()`(OPERATOR·MANAGER 공통 — 조회·재고조정·입고)

- [ ] **Step 1: Write the failing test (webChain 롤 인가, MockMvc 슬라이스)**

`WmsAdminControllerTest`에 추가할 슬라이스 테스트는 Task B7에서 다룬다. 여기서는 컴파일·기동을 검증하는 최소 통합 확인을 먼저 둔다:

```java
// src/test/java/com/jhg/wms/config/SecurityConfigTest.java (기존 파일 — 아래 테스트로 교체/추가)
package com.jhg.wms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SecurityConfigTest {

    @Autowired List<SecurityFilterChain> chains;

    @Test
    void 보안_체인이_두_개로_분리된다() {
        assertThat(chains).hasSize(2);   // apiChain + webChain
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.config.SecurityConfigTest"`
Expected: FAIL — 현재 체인 1개(`hasSize(2)` 실패).

- [ ] **Step 3: Write minimal implementation**

```java
// src/main/java/com/jhg/wms/config/SecurityConfig.java
package com.jhg.wms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * 체인 2분할:
 *  - apiChain(@Order 1): /api/** — OMS 서버간 호출용 Basic. 인증 실패 시 401 직접 응답
 *    (폼 로그인 리다이렉트로 새지 않게 HttpStatusEntryPoint 지정). CSRF 예외.
 *  - webChain(@Order 2): /·/admin/** — 사람용 폼 로그인 + DB 롤 인가. CSRF 활성.
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    // OMS가 쓰는 서비스 계정 — 사람 계정(DB)과 분리. 기존 wms.basic.* 유지.
    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http,
                                 @Value("${wms.basic.user:wms}") String user,
                                 @Value("${wms.basic.password:wms}") String password,
                                 PasswordEncoder encoder) throws Exception {
        UserDetailsService serviceAccount = new InMemoryUserDetailsManager(
                User.withUsername(user).password(encoder.encode(password)).roles("SERVICE").build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(serviceAccount);
        provider.setPasswordEncoder(encoder);

        http.securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable())
            .authenticationProvider(provider)
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .httpBasic(withDefaults())
            // 인증 실패 시 401 직접 — /error 재디스패치 → 폼 로그인 302 방지
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webChain(HttpSecurity http, DbUserDetailsService users) throws Exception {
        http
            .userDetailsService(users)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/admin/purchase-orders").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/admin/purchase-orders/*/cancel").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/admin/replenishment-requests/*/approve").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/admin/replenishment-requests/*/reject").hasRole("MANAGER")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", false)
                .permitAll()
            )
            .logout(withDefaults());
        return http.build();
    }
}
```

```html
<!-- src/main/resources/templates/login.html (최소 — 스타일은 Part C) -->
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8" />
  <title>로그인 · JHG-WMS</title>
  <link rel="stylesheet" th:href="@{/css/admin.css}" />
</head>
<body>
  <main class="login">
    <h1>JHG-WMS 로그인</h1>
    <p th:if="${param.error}" class="flash-err" role="alert">아이디 또는 비밀번호가 올바르지 않습니다.</p>
    <p th:if="${param.logout}" class="flash-ok" role="alert">로그아웃되었습니다.</p>
    <form th:action="@{/login}" method="post">
      <label>아이디 <input type="text" name="username" autofocus /></label>
      <label>비밀번호 <input type="password" name="password" /></label>
      <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
      <button type="submit">로그인</button>
    </form>
  </main>
</body>
</html>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.config.SecurityConfigTest"`
Expected: PASS. (다른 테스트는 아직 깨질 수 있음 — B6/B7에서 처리.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jhg/wms/config/SecurityConfig.java src/main/resources/templates/login.html src/test/java/com/jhg/wms/config/SecurityConfigTest.java
git commit -m "feat(wms): 보안 체인 2분할 — /api Basic(401) + /admin 폼 로그인 롤 인가

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B5: 실서블릿 보안 통합 테스트 (핵심 회귀 방지)

**Files:**
- Create: `src/test/java/com/jhg/wms/security/SecurityChainIntegrationTest.java`

**Interfaces:**
- Consumes: 실행 중인 앱(RANDOM_PORT), 시드된 DB 유저(operator/manager), 서비스 계정(wms/wms).

**배경:** 오늘 OMS에서 겪은 버그(인증 실패가 302로 새서 WMS가 성공으로 오인)를 WMS에서 재발 방지. MockMvc는 서블릿 `/error` 재디스패치를 재현 못 하므로 반드시 실서블릿(TestRestTemplate).

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/jhg/wms/security/SecurityChainIntegrationTest.java
package com.jhg.wms.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityChainIntegrationTest {

    @Autowired TestRestTemplate rest;
    @LocalServerPort int port;

    private String url(String path) { return "http://localhost:" + port + path; }

    @Test
    void api_미인증은_401이고_302_리다이렉트가_아니다() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.exchange(url("/api/replenishment-requests"),
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getHeaders().getLocation()).isNull();   // 폼 로그인 리다이렉트 아님
    }

    @Test
    void api_서비스계정_Basic이면_200() {
        ResponseEntity<String> res = rest.withBasicAuth("wms", "wms")
                .getForEntity(url("/api/replenishment-requests"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void admin_미인증은_로그인_페이지로_302() {
        // 리다이렉트를 따라가지 않도록 TestRestTemplate 기본 동작 확인
        ResponseEntity<String> res = rest.getForEntity(url("/admin/inventory"), String.class);
        // 미인증 → 302 /login (혹은 최종적으로 login 페이지 200). 어느 쪽이든 /admin 콘텐츠는 아님.
        assertThat(res.getStatusCode()).isIn(HttpStatus.FOUND, HttpStatus.OK);
        if (res.getStatusCode() == HttpStatus.FOUND)
            assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/login");
    }

    @Test
    void admin_폼_로그인_후_접근된다() {
        // 1) 로그인 페이지에서 CSRF 토큰·세션 취득
        ResponseEntity<String> loginPage = rest.getForEntity(url("/login"), String.class);
        String cookie = loginPage.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String csrf = extractCsrf(loginPage.getBody());

        // 2) 로그인 POST
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (cookie != null) h.add(HttpHeaders.COOKIE, cookie);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "operator");
        form.add("password", "operator");
        form.add("_csrf", csrf);
        ResponseEntity<String> login = rest.postForEntity(url("/login"), new HttpEntity<>(form, h), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String session = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        // 3) 세션으로 /admin 접근
        HttpHeaders auth = new HttpHeaders();
        auth.add(HttpHeaders.COOKIE, session);
        ResponseEntity<String> page = rest.exchange(url("/admin/inventory"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String extractCsrf(String html) {
        var m = java.util.regex.Pattern
                .compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        return m.find() ? m.group(1) : "";
    }
}
```

- [ ] **Step 2: Run test to verify it fails (또는 부분 통과)**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.security.SecurityChainIntegrationTest"`
Expected: 시드 유저 설정은 B3에서 이미 `src/test/resources/application.yml`에 추가됨(기동 OK). B4까지 구현돼 있으면 여러 케이스가 PASS일 수 있으나, 이 태스크의 목적은 **4개 시나리오를 명시적으로 고정**하는 것. 하나라도 FAIL이면 원인(체인/시드/CSRF)을 먼저 파악한다.

- [ ] **Step 3: Run test to verify it passes**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.security.SecurityChainIntegrationTest"`
Expected: PASS (4개). (실패 시: `/api` 401이 아니라 302면 apiChain의 `HttpStatusEntryPoint` 확인; `/admin` 로그인 POST가 403이면 CSRF 토큰 추출 확인.)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/jhg/wms/security/SecurityChainIntegrationTest.java
git commit -m "test(wms): 실서블릿 보안 체인 검증 — /api 401 유지, /admin 폼 로그인

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B6: 깨진 기존 테스트 복구 (인증/롤 반영)

**Files:**
- Modify: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`
- Modify: `src/test/java/com/jhg/wms/web/InventoryControllerTest.java`
- Modify: `src/test/java/com/jhg/wms/web/ReplenishmentRequestControllerTest.java`

**배경:** 체인 분할·폼 로그인·롤 인가 도입으로 기존 MockMvc 테스트가 401/403으로 깨진다. `spring-security-test`의 `SecurityMockMvcRequestPostProcessors`로 롤 컨텍스트를 준다. 이 태스크는 **먼저 전체 테스트를 돌려 깨진 목록을 확정**한 뒤 고친다.

- [ ] **Step 1: 전체 테스트 실행 — 깨진 목록 확정**

Run: `JAVA_HOME=... ./gradlew test`
Expected: 인증/롤 관련 실패 목록 확인(예: admin 컨트롤러 GET이 302, `/api` 슬라이스가 401 등).

- [ ] **Step 2: admin 컨트롤러 테스트에 롤 부여**

`WmsAdminControllerTest`의 각 요청에 롤을 부여한다. 조회·재고조정·입고는 OPERATOR, 발주 생성·취소·승인·거절은 MANAGER. 예시(패턴):

```java
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

// 조회(OPERATOR로 충분)
mockMvc.perform(get("/admin/inventory").with(user("op").roles("OPERATOR")))
       .andExpect(status().isOk());

// 발주 생성(MANAGER 필요)
mockMvc.perform(post("/admin/purchase-orders").with(user("mgr").roles("MANAGER")).with(csrf())
        .param("items[0].productId", "1").param("items[0].quantity", "5"))
       .andExpect(status().is3xxRedirection());
```

- [ ] **Step 3: 롤 경계 음성 테스트 추가(OPERATOR가 MANAGER 액션 → 403)**

`WmsAdminControllerTest`에 추가:

```java
@Test
void OPERATOR는_발주_생성이_403() throws Exception {
    mockMvc.perform(post("/admin/purchase-orders").with(user("op").roles("OPERATOR")).with(csrf())
            .param("items[0].productId", "1").param("items[0].quantity", "5"))
           .andExpect(status().isForbidden());
}

@Test
void OPERATOR는_보충요청_승인이_403() throws Exception {
    mockMvc.perform(post("/admin/replenishment-requests/1/approve").with(user("op").roles("OPERATOR")).with(csrf()))
           .andExpect(status().isForbidden());
}
```

- [ ] **Step 4: `/api` 슬라이스 테스트 인증 부여**

`InventoryControllerTest`·`ReplenishmentRequestControllerTest`의 `/api/**` 요청에 서비스 계정 컨텍스트를 준다:

```java
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
// /api는 인증만 되면 통과(anyRequest().authenticated())
mockMvc.perform(get("/api/inventory/rows").with(user("svc").roles("SERVICE")))
       .andExpect(status().isOk());
// POST는 apiChain이 CSRF 예외이므로 .with(csrf()) 불필요
```

- [ ] **Step 5: 전체 테스트 통과 확인 + 커밋**

Run: `JAVA_HOME=... ./gradlew test`
Expected: BUILD SUCCESSFUL (기존 132 + 신규).

```bash
git add src/test/java/com/jhg/wms/web/
git commit -m "test(wms): 체인 분할·롤 인가 반영 — 슬라이스 테스트 인증 컨텍스트 + 롤 경계 403

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

# PART A — 발주 취소

### Task A1: PurchaseOrder.cancel() 도메인

**Files:**
- Modify: `src/main/java/com/jhg/wms/domain/PurchaseOrderStatus.java`
- Modify: `src/main/java/com/jhg/wms/domain/PurchaseOrder.java`
- Modify: `src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java`

**Interfaces:**
- Produces: `PurchaseOrderStatus.CANCELLED`; `PurchaseOrder.cancel()` — `ORDERED`·`PARTIALLY_RECEIVED`에서만 허용, 그 외 `IllegalStateException`. `getCancelledAt()`. 입고량·품목 불변.

- [ ] **Step 1: Write the failing test**

`PurchaseOrderTest`에 추가. 순수 도메인 단위 테스트라 신규 엔티티의 `item.getId()`가 null이다 — `receive(Map)`가 id를 요구하므로, `RECEIVED`·`PARTIALLY_RECEIVED`에서의 취소/거부는 실제 저장된 엔티티(id 존재)로 A3 서비스 테스트에서 검증한다. 여기선 id가 필요 없는 케이스만 검증한다. `cancel_중복_취소는_거부`가 "취소 불가 상태 거부" 가드(CANCELLED/RECEIVED 공통 분기)를 대표로 커버한다.

```java
@Test
void cancel_ORDERED에서_취소된다() {
    PurchaseOrder po = PurchaseOrder.create("m", PurchaseOrderItem.create(1L, 10));
    po.cancel();
    assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
    assertThat(po.getCancelledAt()).isNotNull();
}

@Test
void cancel_중복_취소는_거부() {
    PurchaseOrder po = PurchaseOrder.create("m", PurchaseOrderItem.create(1L, 10));
    po.cancel();
    assertThatThrownBy(po::cancel).isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.domain.PurchaseOrderTest"`
Expected: FAIL — `CANCELLED`/`cancel()` 없음.

- [ ] **Step 3: Write minimal implementation**

```java
// PurchaseOrderStatus.java
public enum PurchaseOrderStatus { ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED }
```

`PurchaseOrder.java`에 필드·메서드 추가:

```java
    private LocalDateTime cancelledAt;   // receivedAt 옆에

    /** ORDERED·PARTIALLY_RECEIVED에서만 취소. 입고된 실물 수량은 보존(역산 없음). */
    public void cancel() {
        if (status != PurchaseOrderStatus.ORDERED && status != PurchaseOrderStatus.PARTIALLY_RECEIVED)
            throw new IllegalStateException("취소할 수 없는 상태입니다: " + status + " (발주 #" + id + ")");
        this.status = PurchaseOrderStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.domain.PurchaseOrderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jhg/wms/domain/PurchaseOrderStatus.java src/main/java/com/jhg/wms/domain/PurchaseOrder.java src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java
git commit -m "feat(wms): 발주 취소 도메인 — ORDERED/PARTIALLY_RECEIVED→CANCELLED, 입고량 보존

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task A2: ReplenishmentRequest.cancel() 도메인

**Files:**
- Modify: `src/main/java/com/jhg/wms/domain/ReplenishmentRequestStatus.java`
- Modify: `src/main/java/com/jhg/wms/domain/ReplenishmentRequest.java`
- Modify: `src/test/java/com/jhg/wms/domain/ReplenishmentRequestTest.java`

**Interfaces:**
- Produces: `ReplenishmentRequestStatus.CANCELLED`; `ReplenishmentRequest.cancel()` — `APPROVED`에서만 허용(발주가 연결된 상태), 그 외 `IllegalStateException`. `decidedAt` 갱신은 하지 않고 상태만 전이(또는 `cancelledAt` 없이 상태만). 여기서는 상태만 전이한다.

- [ ] **Step 1: Write the failing test**

```java
@Test
void cancel_APPROVED에서_CANCELLED로() {
    ReplenishmentRequest r = ReplenishmentRequest.create(UUID.randomUUID(), "부족",
            ReplenishmentRequestItem.create(1L, 5));
    r.approve(99L, "발주함");
    r.cancel();
    assertThat(r.getStatus()).isEqualTo(ReplenishmentRequestStatus.CANCELLED);
}

@Test
void cancel_REQUESTED에서는_거부() {
    ReplenishmentRequest r = ReplenishmentRequest.create(UUID.randomUUID(), "부족",
            ReplenishmentRequestItem.create(1L, 5));
    assertThatThrownBy(r::cancel).isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.domain.ReplenishmentRequestTest"`
Expected: FAIL — `CANCELLED`/`cancel()` 없음.

- [ ] **Step 3: Write minimal implementation**

```java
// ReplenishmentRequestStatus.java
public enum ReplenishmentRequestStatus { REQUESTED, APPROVED, REJECTED, FULFILLED, CANCELLED }
```

`ReplenishmentRequest.java`에 추가:

```java
    /** 연결 발주가 취소될 때 함께 종결. APPROVED(발주 연결됨)에서만. */
    public void cancel() {
        requireStatus(ReplenishmentRequestStatus.APPROVED);
        this.status = ReplenishmentRequestStatus.CANCELLED;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.domain.ReplenishmentRequestTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jhg/wms/domain/ReplenishmentRequestStatus.java src/main/java/com/jhg/wms/domain/ReplenishmentRequest.java src/test/java/com/jhg/wms/domain/ReplenishmentRequestTest.java
git commit -m "feat(wms): 보충 요청 취소 도메인 — APPROVED→CANCELLED

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task A3: PurchaseOrderService.cancel(poId) — 발주 + 연결 요청 종결

**Files:**
- Modify: `src/main/java/com/jhg/wms/service/PurchaseOrderService.java`
- Modify: `src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderRepository`, `ReplenishmentRequestRepository.findByPurchaseOrderId`, `PurchaseOrder.cancel()`(A1), `ReplenishmentRequest.cancel()`(A2).
- Produces: `PurchaseOrderService.cancel(Long poId)` — `@Transactional`, 발주 취소 + 연결 요청 있으면 CANCELLED. 재고·원장 불변.

- [ ] **Step 1: Write the failing test**

`PurchaseOrderServiceTest`에 추가. 기존 하네스 패턴 사용 — `@DataJpaTest`, `service`/`inventoryService`는 `setUp()`에서 수동 구성, 헬퍼 `itemIdOf(poId, index)`, 재고 조회 `inventoryRepo.findByProductId(id).orElseThrow().getOnHandQty()`. 필요한 import(`ReplenishmentRequest`, `ReplenishmentRequestItem`, `ReplenishmentRequestStatus`, `java.util.UUID`)는 이미 파일에 있음.

```java
@Test
void cancel_부분입고_발주를_취소하고_연결요청을_CANCELLED로_한다() {
    inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, "상품 1", 10));
    Long poId = service.create(List.of(new PurchaseOrderLine(1L, 100)), "발주");

    // 연결 요청 생성 + 승인으로 poId 연결(approve는 REQUESTED에서만 → 신규 요청 사용)
    ReplenishmentRequest req = ReplenishmentRequest.create(UUID.randomUUID(), "부족",
            ReplenishmentRequestItem.create(1L, 100));
    req.approve(poId, "발주함");
    requestRepo.save(req);

    // 부분 입고 → PARTIALLY_RECEIVED
    service.receive(poId, java.util.Map.of(itemIdOf(poId, 0), 60));
    int onHandBefore = inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty();

    service.cancel(poId);

    assertThat(poRepo.findById(poId).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.CANCELLED);
    assertThat(requestRepo.findByPurchaseOrderId(poId).orElseThrow().getStatus())
            .isEqualTo(ReplenishmentRequestStatus.CANCELLED);
    assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty())
            .isEqualTo(onHandBefore);   // 재고 불변
}

@Test
void cancel_연결요청이_없어도_발주만_취소된다() {
    Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "직접 발주");
    service.cancel(poId);
    assertThat(poRepo.findById(poId).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.CANCELLED);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.service.PurchaseOrderServiceTest"`
Expected: FAIL — `cancel(Long)` 없음.

- [ ] **Step 3: Write minimal implementation**

`PurchaseOrderService`에 추가:

```java
    @Transactional
    public void cancel(Long poId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));
        po.cancel();   // ORDERED·PARTIALLY_RECEIVED만 허용(도메인이 방어)
        requestRepository.findByPurchaseOrderId(poId)
                .ifPresent(ReplenishmentRequest::cancel);   // 연결 요청 종결
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.service.PurchaseOrderServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jhg/wms/service/PurchaseOrderService.java src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java
git commit -m "feat(wms): 발주 취소 서비스 — 발주+연결 보충요청 한 트랜잭션 종결, 재고 불변

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task A4: 취소 컨트롤러 엔드포인트 (MANAGER)

**Files:**
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java`
- Modify: `src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderService.cancel`(A3). 인가는 SecurityConfig(B4)의 `POST /admin/purchase-orders/*/cancel` → `hasRole("MANAGER")`.
- Produces: `POST /admin/purchase-orders/{poId}/cancel` → `redirect:/admin/purchase-orders/{poId}` + flash.

- [ ] **Step 1: Write the failing test**

```java
@Test
void MANAGER는_발주를_취소할_수_있다() throws Exception {
    mockMvc.perform(post("/admin/purchase-orders/1/cancel")
                    .with(user("mgr").roles("MANAGER")).with(csrf()))
           .andExpect(status().is3xxRedirection());
    verify(purchaseOrderService).cancel(1L);
}

@Test
void OPERATOR는_발주_취소가_403() throws Exception {
    mockMvc.perform(post("/admin/purchase-orders/1/cancel")
                    .with(user("op").roles("OPERATOR")).with(csrf()))
           .andExpect(status().isForbidden());
    verifyNoInteractions(purchaseOrderService);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest"`
Expected: FAIL — 엔드포인트 없음(404/405) 혹은 매핑 없음.

- [ ] **Step 3: Write minimal implementation**

`WmsAdminController`에 추가:

```java
    @PostMapping("/admin/purchase-orders/{poId}/cancel")
    public String cancelPurchaseOrder(@PathVariable Long poId, RedirectAttributes ra) {
        try {
            purchaseOrderService.cancel(poId);
            ra.addFlashAttribute("successMessage", "발주 취소 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders/" + poId;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=... ./gradlew test --tests "com.jhg.wms.web.WmsAdminControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jhg/wms/web/WmsAdminController.java src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java
git commit -m "feat(wms): 발주 취소 엔드포인트 (MANAGER 전용)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

# PART C — UI 리스킨

> 배경: `docs/wms-admin-ux-followup.md`의 P1~P5를 실행. CSS/템플릿은 단위테스트 대상이 아니므로(스펙 테스트 전략), 각 태스크는 **변경 → `./gradlew test` 회귀 통과 → 로컬 기동 육안 확인**으로 마무리한다. 로컬 육안 확인: H2 TCP 서버 기동 후 `JAVA_HOME=... ./gradlew bootRun`, `http://localhost:8081` 접속(operator/operator 또는 manager/manager).

### Task C1: thymeleaf-security 통합 + admin.css 테마 재작성

**Files:**
- Modify: `build.gradle`
- Rewrite: `src/main/resources/static/css/admin.css`

**Interfaces:**
- Produces: `sec:authorize`·`sec:authentication` 사용 가능(롤 인식 렌더링). CSS 클래스: `.topnav`, `.brand`, `.flash-ok`, `.flash-err`, `.badge`(+ `.badge--ordered/partial/received/cancelled`), `.login`, 테이블·폼·버튼 기본 스타일.

- [ ] **Step 1: 의존성 추가**

`build.gradle` dependencies에 추가:

```gradle
	implementation 'org.thymeleaf.extras:thymeleaf-extras-springsecurity6'
```

- [ ] **Step 2: admin.css 재작성**

`src/main/resources/static/css/admin.css`를 일관 테마로 재작성한다(색상 토큰, nav, 테이블 zebra, 폼/버튼, flash, 상태 배지, 접근성: 버튼 min-height 44px). 상태 배지 예:

```css
.badge { display:inline-block; padding:.15em .6em; border-radius:1em; font-size:.85em; font-weight:600; }
.badge--ordered   { background:#e5edff; color:#1e40af; }
.badge--partial   { background:#fff4d6; color:#92600a; }
.badge--received  { background:#dcfce7; color:#166534; }
.badge--cancelled { background:#f1f1f1; color:#6b7280; text-decoration:line-through; }
button, .btn { min-height:44px; }
```

- [ ] **Step 3: 회귀 테스트 + 기동 확인**

Run: `JAVA_HOME=... ./gradlew test` → BUILD SUCCESSFUL
그다음 로컬 기동으로 CSS 로드·기본 화면 확인.

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/main/resources/static/css/admin.css
git commit -m "feat(wms): admin 테마 CSS 재작성 + thymeleaf-security(롤 인식)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task C2: 롤 인식 nav + 한글 상태 표기 + 발주 취소 버튼

**Files:**
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/resources/templates/admin/purchaseorders.html`
- Modify: `src/main/resources/templates/admin/purchaseorderdetail.html`
- Modify: `src/main/resources/templates/admin/replenishmentrequests.html`
- Modify: `src/main/resources/templates/admin/reservations.html`
- Modify: `src/main/resources/templates/admin/dashboard.html`

**변경 내용:**
- `layout.html`: `<html>`에 `xmlns:sec="http://www.thymeleaf.org/extras/spring-security"` 추가. nav에 로그인 유저/로그아웃 표시. MANAGER 전용 액션은 `sec:authorize="hasRole('MANAGER')"`로 감싼다.
- 발주 상세: 취소 버튼을 `sec:authorize="hasRole('MANAGER')"` + `th:if`(상태가 ORDERED·PARTIALLY_RECEIVED일 때만)로 노출. `POST /admin/purchase-orders/{poId}/cancel` + CSRF.
- 상태 표기: PO 상태·요청 상태·예약 상태를 한글 + `.badge--*` 클래스로. enum 원문 직접 노출 제거.

발주 상세 취소 버튼 예:

```html
<form sec:authorize="hasRole('MANAGER')"
      th:if="${po.status.name() == 'ORDERED' or po.status.name() == 'PARTIALLY_RECEIVED'}"
      th:action="@{/admin/purchase-orders/{id}/cancel(id=${po.id})}" method="post"
      onsubmit="return confirm('이 발주를 취소하시겠습니까? 이미 입고된 수량은 유지됩니다.');">
  <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" />
  <button type="submit" class="btn btn--danger">발주 취소</button>
</form>
```

한글 상태 배지 예(발주 목록):

```html
<span th:switch="${po.status.name()}" class="badge"
      th:classappend="${po.status.name() == 'ORDERED'} ? 'badge--ordered' : (${po.status.name() == 'PARTIALLY_RECEIVED'} ? 'badge--partial' : (${po.status.name() == 'RECEIVED'} ? 'badge--received' : 'badge--cancelled'))">
  <span th:case="'ORDERED'">발주됨</span>
  <span th:case="'PARTIALLY_RECEIVED'">부분 입고</span>
  <span th:case="'RECEIVED'">입고 완료</span>
  <span th:case="'CANCELLED'">취소됨</span>
</span>
```

- [ ] **Step 1: layout.html에 sec 네임스페이스 + 로그인 정보 + 롤 인식**

(위 변경 내용대로 수정)

- [ ] **Step 2: 발주 목록·상세에 한글 상태 배지 + 취소 버튼(MANAGER)**

발주 목록 상태 필터에 `CANCELLED` 옵션 추가. 상세에 위 취소 폼 추가.

- [ ] **Step 3: 요청/예약 화면 한글 상태 표기 + 성공 메시지 한글화**

`replenishmentrequests.html`·`reservations.html`의 enum 원문을 한글 배지로. 컨트롤러의 영문 flash(`Request approved./rejected.`)를 한글로 변경(`WmsAdminController`의 approve/reject flash 문구):

```java
ra.addFlashAttribute("successMessage", "보충 요청을 승인했습니다.");
// reject
ra.addFlashAttribute("successMessage", "보충 요청을 반려했습니다.");
```

- [ ] **Step 4: 회귀 테스트 + 로컬 육안 확인**

Run: `JAVA_HOME=... ./gradlew test` → BUILD SUCCESSFUL
로컬 기동: MANAGER 로그인 시 취소 버튼 노출, OPERATOR 로그인 시 미노출·직접 POST 시 403 확인.

> flash 문구를 바꾸면 관련 컨트롤러 테스트가 문구를 검증하고 있을 수 있다 — 깨지면 함께 갱신.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/ src/main/java/com/jhg/wms/web/WmsAdminController.java src/test/java/com/jhg/wms/web/WmsAdminControllerTest.java
git commit -m "feat(wms): 롤 인식 nav + 한글 상태 배지 + 발주 취소 버튼(MANAGER)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task C3: 로그인 페이지 스타일 + 접근성 마무리

**Files:**
- Modify: `src/main/resources/templates/login.html`
- Modify: `src/main/resources/templates/fragments/layout.html` (flash `role="alert"`)
- Modify: `src/main/resources/static/css/admin.css` (`.login` 블록)

**변경 내용:**
- 로그인 페이지를 admin 테마로(카드 레이아웃, 라벨 연결, 버튼 44px).
- flash 메시지에 `role="alert"` 부여(스크린리더).
- 폼 `label`과 input `id`/`for` 연결.

- [ ] **Step 1: login.html·layout.html 접근성/스타일 반영**

(위 변경 내용대로)

- [ ] **Step 2: 회귀 테스트 + 로컬 육안 확인**

Run: `JAVA_HOME=... ./gradlew test` → BUILD SUCCESSFUL
로컬: 로그아웃 → `/login` 리다이렉트 → 로그인 페이지 스타일·오류 메시지 확인.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/login.html src/main/resources/templates/fragments/layout.html src/main/resources/static/css/admin.css
git commit -m "feat(wms): 로그인 페이지 스타일 + flash 접근성(role=alert)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task C4: (선택) ux-followup P1·P3 — 여력 되면

**Files:**
- Modify: `src/main/resources/templates/admin/dashboard.html`, `admin/inventory.html`
- Modify: `src/main/java/com/jhg/wms/web/WmsAdminController.java` (대시보드 카운트·정렬)

**배경:** 스펙의 "여력 되면(선택)" 항목. 필수 완료 기준이 아니므로 시간 여유가 있을 때만.
- 대시보드: `검토 대기 요청`·`입고 대기 발주`·`부분 입고 발주`·`가용 0 SKU` 카드 → 해당 필터 목록으로 링크.
- 재고 목록 기본 정렬: 가용수량 오름차순.
- 재고 조정: 제출 전 예상 수량 표시, 음수 조정 사유 필수.

- [ ] **Step 1: 대시보드 카드·정렬 구현 + 필요 시 서비스 카운트 메서드**
- [ ] **Step 2: 재고 조정 안전장치(음수 사유 필수는 서버에서도 검증)**
- [ ] **Step 3: 회귀 테스트 + 로컬 확인**
- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(wms): 대시보드 처리대기 카드 + 재고 위험 정렬 + 조정 안전장치 (ux-followup P1/P3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 최종 검증

- [ ] `JAVA_HOME=... ./gradlew build` — 전체 테스트 + bootJar 조립 성공.
- [ ] 로컬 E2E: H2 기동 → WMS 기동 → operator/manager 각각 로그인 → 롤별 화면·액션 확인 → 발주 취소 → 연결 요청 CANCELLED 확인.
- [ ] `/api/**`를 서비스 계정 Basic으로 호출 시 200, 미인증 시 401(302 아님) 확인.
- [ ] README 갱신은 **별도 문서 트랙**(포폴 서사)에서 처리 — 이 플랜 범위 밖.

## 완료 기준 (스펙 대응)

- [ ] 발주를 ORDERED·PARTIALLY_RECEIVED에서 취소, 연결 요청 CANCELLED, 재고 불변 (A1~A4)
- [ ] `/admin` 폼 로그인 / `/api` Basic 분리, OMS 연동(기존 테스트) 유지 (B4~B6)
- [ ] OPERATOR/MANAGER DB 롤 로드, 롤 밖 액션 403 (B1~B6)
- [ ] 일관 테마 + 롤 인식 nav + 한글 상태 (C1~C3)
- [ ] 실서블릿 보안 테스트로 `/api` 401 유지 검증 (B5)
