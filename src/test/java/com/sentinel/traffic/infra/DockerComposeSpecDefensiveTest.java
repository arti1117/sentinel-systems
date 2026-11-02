package com.sentinel.traffic.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DockerComposeSpecDefensiveTest {

    private static final Path PROJECT_ROOT = Path.of("");

    @Test
    @DisplayName("Docker Compose 방어 검증: 4개 서비스(postgis, redis, backend, frontend)와 헬스체크·네트워크·메모리 상한이 설정되어야 한다")
    void dockerComposeDefinesAllServicesWithHealthchecksAndResourceLimits() throws IOException {
        Path composePath = PROJECT_ROOT.resolve("docker-compose.yml");
        assertThat(composePath).exists();

        String content = Files.readString(composePath);

        // 1. 4개 전체 서비스 정의 확인
        assertThat(content)
                .contains("postgres-postgis:")
                .contains("redis:")
                .contains("backend:")
                .contains("frontend:");

        // 2. 컨테이너 내부 localhost 함정 차단: 컨테이너 서비스명(postgres-postgis, redis) 연결 확인
        assertThat(content)
                .contains("jdbc:postgresql://postgres-postgis:5432/sentinel")
                .contains("SENTINEL_REDIS_HOST: redis");

        // 3. 기동 순서 경쟁 조건(Race Condition) 차단: healthcheck 및 service_healthy 의존성 확인
        assertThat(content)
                .contains("healthcheck:")
                .contains("pg_isready")
                .contains("redis-cli")
                .contains("condition: service_healthy");

        // 4. 호스트 메모리 고갈(IDE 속도 저하) 차단: JVM 메모리 상한(-Xmx) 및 컨테이너 메모리 제한 확인
        assertThat(content)
                .contains("-Xmx")
                .contains("mem_limit:");
    }

    @Test
    @DisplayName("Docker 보안·빌드 방어 검증: .dockerignore가 .env 비밀키, .git, node_modules 유출을 차단해야 한다")
    void dockerignoreBlocksSecretsAndHeavyBuildArtifacts() throws IOException {
        Path rootDockerignore = PROJECT_ROOT.resolve(".dockerignore");
        Path frontendDockerignore = PROJECT_ROOT.resolve("frontend/.dockerignore");

        assertThat(rootDockerignore).exists();
        assertThat(frontendDockerignore).exists();

        String rootIgnore = Files.readString(rootDockerignore);
        assertThat(rootIgnore)
                .contains(".env")
                .contains(".git")
                .contains("frontend/node_modules")
                .contains(".gradle");

        String feIgnore = Files.readString(frontendDockerignore);
        assertThat(feIgnore)
                .contains("node_modules")
                .contains("dist")
                .contains(".env");
    }

    @Test
    @DisplayName("Frontend Nginx 방어 검증: /api 및 /health 경로를 backend:8080 컨테이너로 프록시하고 타임아웃을 설정해야 한다")
    void frontendNginxConfigProxiesApiToBackendContainerWithTimeouts() throws IOException {
        Path backendDockerfile = PROJECT_ROOT.resolve("Dockerfile");
        Path frontendDockerfile = PROJECT_ROOT.resolve("frontend/Dockerfile");
        Path nginxConf = PROJECT_ROOT.resolve("frontend/nginx.conf");

        assertThat(backendDockerfile).exists();
        assertThat(frontendDockerfile).exists();
        assertThat(nginxConf).exists();

        String nginx = Files.readString(nginxConf);
        assertThat(nginx)
                .contains("location /api/")
                .contains("http://backend:8080")
                .contains("proxy_read_timeout")
                .contains("try_files $uri $uri/ /index.html;");
    }
}
