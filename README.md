# Sentinel Systems — 실시간 교통사고 예측 · 관제 시스템

공공 교통·기상·사고 이력 데이터(실시간 도로 소통·돌발 상황 ↔ 기상청 초단기 강수·결빙·시정 ↔ TAAS 사고다발지역·교차로 기하 구조)를 결합해 **구간별 실시간 교통사고 발생 위험지수(Accident Risk Score)와 연쇄 추돌 전파 위험을 예측·경보하고, 안전 우회 경로(Risk-Aware Routing)와 관제 의사결정을 제공하는 시스템**이다.

---

## 핵심 문제 정의

1. **비동기 다중 소스 정합성:** 주기가 서로 다른 외부 공공 API(소통 1~5분, 돌발 실시간, 기상 10분)의 타임아웃(`Unknown`), 중복 패킷, 수신 시차(Out-of-order)를 제어하고 도로 구간(Link)·교차로(Node) 단위로 상태를 동기화한다.
2. **실시간 위험도 산출 및 충격파 전파:** 급감속 충격파($\Delta v / \Delta t$), 기상 악화(강수·결빙·안개), 도로 기하 구조와 과거 사고 이력을 결합해 구간별 사고 위험지수를 산출하고 상류 구간으로의 연쇄 추돌 위험 전파를 계산한다.
3. **우회로 과밀(2차 위험) 제어 및 사후 대사(Calibration):** 다수 차량·관제 채널을 단일 우회로로 동시 유도할 때 발생하는 우회로 과밀을 제어하고, 예측 위험지수와 실제 발생한 사고·돌발 이력을 사후 대사(Reconciliation & Calibration)하여 모델과 임계치를 보정한다.

---

## 기술 스택 및 선정 근거

| 계층 | 기술 스택 | 선정 근거 및 관측 포인트 |
|---|---|---|
| **Runtime & Framework** | **Java 21 LTS (Virtual Threads) · Spring Boot 3.4 · Gradle (Kotlin DSL)** | `spring.threads.virtual.enabled=true` 기반 경량 비동기 외부 API 폴링, Java 21 불변 `record` 및 강타입 도메인 모델링, 인메모리 도로망 그래프 고성능 멀티스레드 탐색 |
| **Spatial & Relational DB** | **PostgreSQL 16 + PostGIS** (`Spring Data JPA` + `Hibernate Spatial`) | 도로망 `Node/Link` 위상 구조, 반경 $R\text{ km}$ 공간 질의(`ST_DWithin`), 예측 위험지수↔실제 사고 이력 대사(Reconciliation) 원장 저장 |
| **In-Memory State & Dedup** | **Redis 7** (`Spring Data Redis` + `ConcurrentHashMap`) | 구간별 최신 상태 스냅샷 캐싱, 외부 패킷 중복 제거(Idempotency Key TTL), 동시 우회 경로 탐색 및 경보 폭주율(Rate-limit) 제어 |
| **Quality & Testbed** | **JUnit 5 · AssertJ · Spring MockMvc** | 패킷 지연·중복·타임아웃(`UNKNOWN_TIMEOUT`) 결함 주입(Fault Injection) 단위 테스트 및 슬라이스 검증 |

---

## 시스템 구성 및 구현 단계

| 단계 | 패키지 경로 | 핵심 구현 범위 |
|---|---|---|
| **Phase 1** | `com.sentinel.ingestion` | 도로 소통·돌발 상황·기상청 API 수집 파이프라인, 외부 API 타임아웃(`UNKNOWN_TIMEOUT`)·중복 패킷·수신 시차(Out-of-order) 보정 및 스키마 정규화 |
| **Phase 2** | `com.sentinel.graph` | `교차로(Node) → 도로 구간(Link)` 속성 그래프 및 반경 $R\text{ km}$ 위험 구간 공간 색인(`PostGIS`/`QuadTree`/`Geohash`), 시계열 소통·기상·위험도 로그 적재 |
| **Phase 3** | `com.sentinel.risk` | 급감속 충격파($\Delta v / \Delta t$) 실시간 감지, 기상·도로 기하 구조·사고 이력 결합 위험지수 산출 및 상류 구간 연쇄 추돌 위험 전파 |
| **Phase 4** | `com.sentinel.routing` | `통행시간 + 사고위험도` 복합 가중치 기반 안전 경로(`Dijkstra`/`A*`) 산출, 우회로 과밀(2차 사고 위험) 및 경보 폭주 제어 |
| **Phase 5** | `com.sentinel.reconciliation` | 예측 위험지수와 실제 사고·돌발 이력 간 사후 대사 파이프라인, 실시간 관제 웹 대시보드/API 배포, 장애 격리 및 SLO 모니터링 |

---

## 로컬 실행 및 검증 명령어

```bash
# 1. 인프라 기동 (PostgreSQL/PostGIS + Redis)
docker compose up -d

# 2. 컴파일 및 단위·결함 주입 테스트 실행
./gradlew test

# 3. 로컬 API 서버 실행 (Java 21 Virtual Threads 활성화)
./gradlew bootRun
```
