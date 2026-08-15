package com.jhg.wms.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** SecurityContext를 얇게 감싼다 — 서비스가 Spring Security를 직접 알지 않게 하고,
 *  테스트에서 가짜 구현으로 갈아끼울 수 있게 하려는 목적. */
@Component
public class SecurityContextActorProvider implements ActorProvider {

    public static final String SYSTEM = "system";

    @Override
    public String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
            return SYSTEM;
        return auth.getName();
    }
}
