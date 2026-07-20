package com.jhg.wms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * 수평 확장(scale) 프로파일에서만 HttpSession을 Redis로 externalize한다.
 * 인스턴스 간 세션(및 세션 기반 CSRF 토큰)이 공유되어 다중 인스턴스에서 관리자 폼 POST가 안 깨진다.
 *
 * 기본/prod 프로파일에서는 SessionAutoConfiguration이 application.yml에서 제외되어
 * Redis 세션이 전혀 활성화되지 않는다(Railway는 Redis 없이 서블릿 인메모리 세션 사용).
 */
@Configuration
@Profile("scale")
@EnableRedisHttpSession
public class RedisSessionConfig {
}
