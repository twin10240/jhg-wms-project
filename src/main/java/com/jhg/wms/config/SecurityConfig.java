package com.jhg.wms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * WMS 공개 URL(Railway Private Networking 밖으로 노출될 가능성) 대비 HTTP Basic 인증.
 * OMS→WMS 호출은 세션이 없는 서버간 통신이라 Basic이 적합. 관리자 화면(admin/**)은 폼 기반이라
 * CSRF를 유지하고, API(api/**)는 서버간 호출이라 CSRF 예외(토큰을 주고받을 방법이 없음).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain web(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/error", "/css/**", "/js/**", "/images/**").permitAll()
                    .requestMatchers("/", "/admin/**", "/api/**").authenticated()
                    .anyRequest().authenticated()
            )
            .httpBasic(withDefaults());

        return http.build();
    }

    @Bean
    UserDetailsService users(@Value("${wms.basic.user:wms}") String user,
                              @Value("${wms.basic.password:wms}") String password,
                              PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername(user).password(encoder.encode(password)).roles("WMS").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
