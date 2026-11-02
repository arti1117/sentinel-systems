# Sentinel Systems — 실시간 교통·돌발·이상징후 관제 및 사고범위 예측 시스템

전국 고속도로·국도(**국토교통부 ITS 국가교통정보센터**)와 서울시 주요 도로(**서울 열린데이터광장 TOPIS**)의 실시간 공공데이터에 **기상(강수·결빙·시정)**과 **실시간 유동인구(도시 혼잡도)**를 결합해, 도로 위에서 벌어지는 현황과 돌발상황을 실시간으로 보여주고 이상징후 및 사고 확산 범위를 예측하는 관제 시스템이다.

---

## 시스템 구성 4단계

| 단계 | 모듈 패키지 | 핵심 구현 범위 | 연동 공공데이터 소스 |
|---|---|---|---|
| **Phase 1. 도로현황** | `com.sentinel.traffic` | 전국 고속도로·국도(ITS)와 서울시 도로(TOPIS)의 실시간 구간 소통 속도를 수집·통합하고, 도로 종별(고속도로·도심도로) 기준에 따라 소통 등급(`SMOOTH`/`SLOW`/`CONGESTED`)을 정규화하여 실시간 도로 현황 및 요약 통계를 제공한다. | 국토부 ITS 소통정보 API + 서울시 TOPIS 실시간 도로 소통 API |
| **Phase 2. 돌발상황** | `com.sentinel.incident` | 사고·공사·차량고장·통제 등 실시간 돌발상황을 수집하고 인근 도로 CCTV 스트림을 매핑한다. 이기종 출처 간 중복 제보를 병합하고 `발생(ACTIVE) → 갱신 → 해제(CLEARED)` 상태를 추적한다. | 국토부 ITS 돌발/CCTV API + 서울시 TOPIS 돌발정보 API |
| **Phase 3. 이상징후 (기상 + 인구)** | `com.sentinel.anomaly` | 실시간 도로 현황 위에 **기상 악화(강수·적설·결빙·안개)**와 **실시간 유동인구 혼잡도**를 결합해, 평시 대비 급격한 감속·밀집이 겹치는 복합 위험 구간을 조기 탐지하고 경보를 발생시킨다. | 기상청 초단기실황 API + 서울시 실시간 도시데이터(유동인구·혼잡도) API |
| **Phase 4. 사고범위예측** | `com.sentinel.prediction` | 사고 또는 이상징후가 발생한 도로 구간으로부터 인접 상류 도로 구간(Link)과 주변 권역으로 정체 및 2차 사고 위험이 번지는 시간대별·반경별 파급 범위를 예측한다. | PostGIS 공간 도로망 그래프 + 축적 소통·사고 이력 |

---

## 기술 스택

| 계층 | 기술 스택 | 역할 |
|---|---|---|
| **Runtime & Framework** | **Java 21 LTS (Virtual Threads) · Spring Boot 3.4 · Gradle (Kotlin DSL)** | `spring.threads.virtual.enabled=true` 기반 다중 공공 API(ITS + TOPIS) 병렬 수집, Java 21 `record` 도메인 모델링 |
| **Spatial & Relational DB** | **PostgreSQL 16 + PostGIS** (`Spring Data JPA` + `Hibernate Spatial`) | 도로 구간(`Link`)·돌발 위치 공간 질의 및 소통·이상징후 이력 저장 |
| **In-Memory State & Cache** | **Redis 7** (`Spring Data Redis` + `ConcurrentHashMap`) | 실시간 도로현황 최신 스냅샷 유지 및 조회 응답 가속 |
| **Quality & Testbed** | **JUnit 5 · AssertJ · Spring MockMvc** | 이기종 공공데이터 정규화·결측치 방어·상태 갱신 단위 및 API 슬라이스 테스트 |

---

## 로컬 실행 및 검증 명령어

```bash
# 1. 인프라 기동 (PostgreSQL/PostGIS + Redis)
docker compose up -d

# 2. 컴파일 및 테스트 실행
./gradlew test

# 3. 로컬 API 서버 실행
./gradlew bootRun
```
