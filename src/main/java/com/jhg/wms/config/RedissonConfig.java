package com.jhg.wms.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 분산 락용 Redisson 클라이언트 — 수평 확장(scale) 프로파일에서만 생성한다.
 * Railway 단일 인스턴스(prod 단독)에는 이 빈이 없으므로 Redis 연결 자체가 없다(기동 실패 방지).
 * 세션 공유는 Spring Session(Lettuce)이 담당하고, 이 클라이언트는 InitDb 시딩 락 전용이다.
 */
@Configuration
@Profile("scale")
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${REDIS_HOST:redis}") String host,
                                         @Value("${REDIS_PORT:6379}") int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
