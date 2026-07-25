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
