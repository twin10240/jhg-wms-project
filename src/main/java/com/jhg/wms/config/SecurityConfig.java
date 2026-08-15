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
                .requestMatchers(HttpMethod.POST, "/admin/returns/*/receive").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/admin/returns/*/complete").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/admin/returns/*/cancel").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/admin/cycle-counts/*/approve").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/admin/cycle-counts/*/reject").hasRole("MANAGER")
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
