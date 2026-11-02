package com.sentinel.traffic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 공공 교통 오픈API(ITS, TOPIS) 연동 설정 프로퍼티.
 */
@ConfigurationProperties(prefix = "sentinel.public-api")
public record PublicApiProperties(
        long timeoutMs,
        String itsApiKey,
        String topisApiKey
) {

    public boolean hasTopisKey() {
        return topisApiKey != null && !topisApiKey.isBlank();
    }

    public boolean hasItsKey() {
        return itsApiKey != null && !itsApiKey.isBlank();
    }
}
