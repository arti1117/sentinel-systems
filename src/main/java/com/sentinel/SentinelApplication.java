package com.sentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 실시간 도로 소통·돌발·이상징후 관제 및 사고범위 예측 서버 애플리케이션.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SentinelApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelApplication.class, args);
    }
}
