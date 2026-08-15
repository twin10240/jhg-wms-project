package com.jhg.wms.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityContextActorProviderTest {

    private final SecurityContextActorProvider provider = new SecurityContextActorProvider();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_사용자는_사용자명을_돌려준다() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_MANAGER")));

        assertThat(provider.current()).isEqualTo("manager");
    }

    // 기동 시드·백필은 인증 컨텍스트가 없다 — 원장에 빈 값이 아니라 "system"이 남아야 구분된다.
    @Test
    void 인증이_없으면_system() {
        assertThat(provider.current()).isEqualTo("system");
    }

    @Test
    void 익명_인증도_system() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(provider.current()).isEqualTo("system");
    }
}
