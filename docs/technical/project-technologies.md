# 프로젝트별 핵심 기술 상세 분석서

> 본 문서는 워크스페이스 하위 3개 프로젝트 그룹(AutowareFoundation, CarlaSimulator, F1Tenth)의 소스코드와 기술 문서를 검토하여 작성한 엔지니어 레벨 기술 분석서입니다.

---

## 목차

1. [AutowareFoundation - 자율주행 풀 스택](#1-autowarefoundation)
2. [CarlaSimulator - 시뮬레이션 생태계](#2-carlasimulator)
3. [F1Tenth - 1/10 스케일 자율 레이싱](#3-f1tenth)
4. [기술 스택 비교 요약](#4-기술-스택-비교-요약)

---

## 1. AutowareFoundation

### 1.1 시스템 아키텍처

전체 파이프라인은 **모듈형 센서-비종속 구조**를 따릅니다:

```
Sensing → Localization → Perception → Planning → Control → Vehicle
```

#### 좌표계 (TF Tree)

```
earth (ECEF)
  └── map (MGRS origin, 50Hz+)
       └── base_link (후축 중심의 지면 투영점)
            ├── sensor_<name>
            ├── lidar
            └── imu
```

- **base_link**: 차량 후축 중심을 지면에 투영한 기준점
- **map**: 고정 월드 좌표 원점 (MGRS 그리드)
- Localization 출력 최소 주파수: **50Hz**

---

### 1.2 autoware (메타 레포)

- YAML `.repos` 파일로 40개 이상의 서브 패키지를 `vcs import`로 관리하는 워크스페이스 오케스트레이터
- 버전: autoware_core (v1.7.0), autoware_universe (v0.50.0), autoware_msgs (v1.11.0), autoware_launch (v0.50.0)

---

### 1.3 autoware_core - 안정 코어 패키지 (69개)

**언어**: C++17 / **빌드**: ament_cmake_auto + colcon / **주요 의존성**: PCL, Eigen, Lanelet2, OpenCV, OSQP, Boost

#### 1.3.1 NDT Scan Matcher (Localization)

**알고리즘**: Multi-grid NDT + OpenMP 병렬화 (`pclomp` 네임스페이스)

**핵심 데이터 구조**:
```cpp
struct NdtResult {
  Eigen::Matrix4f pose;                              // 4x4 변환 행렬
  float transform_probability;                        // 수렴 점수 (0-1)
  float nearest_voxel_transformation_likelihood;      // 대안 점수
  int iteration_num;                                   // 수렴 반복 횟수
  Eigen::Matrix<double, 6, 6> hessian;                // 공분산 추정용 헤시안
  std::vector<Eigen::Matrix4f> transformation_array;  // 수렴 이력
};
```

**파라미터 설정**:
```cpp
struct NdtParams {
  double trans_epsilon;         // 이동 수렴 임계값 (보통 0.01m)
  double step_size;             // 라인 서치 스텝 크기
  float resolution;             // 복셀 그리드 해상도 [m] (도심: 1.0m)
  int max_iterations;           // 반복 상한 (10-100)
  NeighborSearchMethod search_method; // KDTREE, DIRECT26, DIRECT7, DIRECT1
  int num_threads;              // OpenMP 스레드 수
  float regularization_scale_factor; // GNSS 정규화 가중치
};
```

**GNSS 정규화**: 피처가 부족한 도로(교량, 고속도로)에서 NDT 비용함수에 GNSS 제약 추가
- 비용함수: `NDT(R,t) + scale_factor * |종방향_오차|^2`
- 종방향(X) 오차만 패널티, Z/횡방향은 미적용

**2D 실시간 공분산 추정** (4가지 모드):
| 모드 | 방법 | 특징 |
|------|------|------|
| `FIXED_VALUE` | 고정값 | 가장 빠름, 환경 무관 |
| `LAPLACE_APPROXIMATION` | 2x2 XY 헤시안 역행렬 | 수학적으로 엄밀 |
| `MULTI_NDT` | 다중 초기자세 수렴 분산 | 정확하지만 느림 |
| `MULTI_NDT_SCORE` | 복셀 변환 우도 기반 | MULTI_NDT의 고속 변형 |

**동적 맵 로딩**: ~20m x 20m PCD 그리드 단위로 `pointcloud_map_loader` 서비스에서 로드 (대형 맵 메모리 제약 해결)

#### 1.3.2 EKF Localizer (Localization)

**상태 벡터** (6차원):
```cpp
enum IDX {
  X = 0,      // X 위치 [m]
  Y = 1,      // Y 위치 [m]
  YAW = 2,    // 요 각도 [rad]
  YAWB = 3,   // 요 바이어스 (센서 오프셋) [rad]
  VX = 4,     // 종방향 속도 [m/s]
  WZ = 5,     // 요 레이트 [rad/s]
};
```

**예측 모델** (등속 운동학):
```
x_{k+1} = x_k + vx * cos(yaw) * dt
y_{k+1} = y_k + vx * sin(yaw) * dt
yaw_{k+1} = yaw_k + wz * dt
yaw_bias_{k+1} = yaw_bias_k   (상수)
vx_{k+1} = vx_k               (등속 가정)
wz_{k+1} = wz_k               (등각속도 가정)
```

**핵심 기법**:

| 기법 | 설명 |
|------|------|
| **시간 지연 보상** | 증강 상태 기반 고정 래그 스무딩 (0-500ms 센서 딜레이 대응) |
| **요 바이어스 추정** | 센서 장착 각도 오프셋 자동 보정 |
| **마할라노비스 거리 게이팅** | 이상치 확률적 거부 (자세 3-DOF: 11.3, 속도 2-DOF: 9.21) |
| **스무스 업데이트** | 저주파 측정을 다수 예측 스텝으로 분할 (3-5 스텝) |
| **Z 좌표 보정** | 경사에서 pitch 기반 수직 위치 보정 |

**프로세스 노이즈**:
- `proc_stddev_vx_c`: 최대 차량 가속도 [m/s^2]
- `proc_stddev_wz_c`: 최대 각가속도 [rad/s^2]
- `proc_stddev_yaw_bias_c`: 요 바이어스 드리프트 (보통 0.001 rad/s)

**출력** (50Hz):
- `ekf_pose_with_covariance`: 6x6 공분산 행렬 포함 자세
- `ekf_twist_with_covariance`: 6x6 공분산 행렬 포함 속도
- TF: `map` -> `base_link` 변환 브로드캐스트

#### 1.3.3 진단 프레임워크

NDT 스캔 매처 진단 (`scan_matching_status`):

| 필드 | WARNING 조건 | ERROR 조건 | 결과 기각 |
|------|-------------|-----------|----------|
| `sensor_points_size` | - | SIZE=0 | YES |
| `sensor_points_delay_time_sec` | >timeout | - | YES |
| `transform_probability` | <converged_param | - | YES |
| `iteration_num` | >max_iterations | - | YES |
| `distance_initial_to_result` | >tolerance_m | - | NO |

---

### 1.4 autoware_universe - 실험/확장 패키지

**언어**: C++17, Python, CUDA / **GPU**: TensorRT, CUDA, cuDNN, ONNX

#### 1.4.1 CenterPoint 3D 객체 검출 (Perception)

**아키텍처**: 2단계 딥러닝 파이프라인

```
PointCloud2 입력
  ↓
[복셀화 + 필라 인코딩] ← Voxel Feature Encoder (ONNX/TensorRT)
  ↓
[백본 + Neck + 검출 헤드] ← DetectionHead (ONNX/TensorRT)
  ↓
[후처리: NMS, 요 정규화]
  ↓
DetectedObjects 출력
```

**모델 파라미터** (ONNX 학습 결과):
```
class_names: ["CAR", "TRUCK", "BUS", "BICYCLE", "PEDESTRIAN"]
point_feature_size: 4 (x, y, z, intensity)
max_voxel_size: 40000
point_cloud_range: [-51.2, -51.2, -3.0, 51.2, 51.2, 5.0] [m]
voxel_size: [0.2, 0.2, 8.0] (XY, XY, Z) [m]
```

**모델 변형**:
| 모델 | 학습 데이터 | 특징 |
|------|------------|------|
| CenterPoint Full | nuScenes ~28k + TIER IV ~11k, 60 epochs | 높은 정확도 |
| CenterPoint Tiny | Argoverse2 ~110k + TIER IV ~11k, 20 epochs | 낮은 지연시간 |

**후처리**:
- IoU 기반 NMS (탐색 반경 10.0m, IoU 임계값 0.1)
- 시간적 밀도화: `num_past_frames` 1-3 (과거 프레임 합산)
- 클래스별 요 정규화 임계값: `[0.3, 0.3, 0.3, 0.3, 0.0]` rad

#### 1.4.2 MPC 횡방향 제어 (Control)

**아키텍처**: 선형 MPC 경로 추종

```
기준 궤적 → [경로 필터링] → [MPC QP 최적화] → [차량 모델] → [Butterworth LPF] → 조향 출력
```

**차량 모델** (선택 가능):

| 모델 | 상태 벡터 | 특징 |
|------|----------|------|
| Kinematics (기본) | `[x, y, θ, δ, v]` | 자전거 모델 + 1차 조향 지연 |
| Kinematics No Delay | `[x, y, θ, δ, v]` | 조향 동역학 미포함 |
| Dynamics (WIP) | - | 슬립각 + 타이어 특성 포함 |

**QP 최적화 문제**:
```
minimize: J = Σ[w_lat·e_lat² + w_head·e_head² + w_steer·δ² + w_jerk·j²]
subject to: 조향 속도/각도 제한

w_lat: 횡방향 추종 오차      (기본 0.1)
w_head: 헤딩 안정화 오차
w_steer: 조향각 편차 패널티
w_jerk: 횡방향 저크 (부드러움)
```

**QP 솔버**:
| 솔버 | 방식 | 제약조건 |
|------|------|---------|
| `unconstraint_fast` | Eigen 최소제곱법 | 없음 (빠름) |
| `OSQP` | ADMM 기반 | 조향 속도 제한 포함 |

**MPC 파라미터**:
```cpp
int prediction_horizon;     // 50-100 스텝
double prediction_dt;       // 0.05-0.1 초
double input_delay;         // 조향 구동 지연 [s]
double steer_tau;           // 조향 1차 시상수
```

**필터링**:
- 입력: 곡률에 2차 Butterworth LPF (컷오프: 0.04 Hz)
- 출력: 조향각에 Butterworth LPF (컷오프: 3.0 Hz)

#### 1.4.3 Behavior Path Planner (Planning)

**플러그인 기반 Scene Module 아키텍처**:

| 모듈 | 기능 | 핵심 알고리즘 |
|------|------|-------------|
| Lane Following | 차선 중심선 기반 경로 | 센터라인 보간 |
| Static Obstacle Avoidance | 정지 장애물 횡방향 회피 | 충돌 없는 경로 최적화 |
| Lane Change | 차선 변경 | 운동학적 경로 + 충돌 검증 |
| Goal Planner | 주차 기동 | 주차 슬롯 검출 + 궤적 |
| Start Planner | 출발 기동 | 전/후진 모션 계획 |
| Avoidance by Lane Change | 회피를 위한 차선 변경 | 다차선 충돌 검사 |

**궤적 보간** (`autoware_trajectory` 패키지):
| 보간법 | 최소 포인트 수 | 용도 |
|--------|--------------|------|
| Linear | 2 | 기본 |
| AkimaSpline | 5 | 부드러운 경로 |
| CubicSpline | 4 | 곡률 계산 필요시 |

- 호 길이 매개변수화: `s` 좌표 (시작점 기준 3D 호 길이)
- 곡률: 2D (X-Y만, 평면 도로 가정)

---

### 1.5 autoware_msgs - 인터페이스 정의

| 패키지 | 메시지 타입 | 용도 |
|--------|-----------|------|
| `autoware_control_msgs` | Lateral, Longitudinal, AckermannControlCommand | 차량 제어 명령 |
| `autoware_perception_msgs` | DetectedObjects, PredictedObjects | 인지 출력 |
| `autoware_planning_msgs` | Trajectory, PathPoint, LaneletRoute | 계획 인터페이스 |
| `autoware_vehicle_msgs` | SteeringReport, VelocityReport, GearCommand | 차량 상태 |
| `autoware_v2x_msgs` | - | V2X 통신 |

### 1.6 QoS 설정 (`autoware_qos_utils`)

```cpp
// 센서 토픽: Best-effort, keep_last=1
rclcpp::SensorDataQoS().keep_last(1)

// 일반 토픽: ROS 배포판별 분기
#ifdef ROS_DISTRO_HUMBLE
  rmw_qos_profile_default
#else
  rclcpp::QoS(rclcpp::KeepLast(10))
#endif
```

### 1.7 성능 특성

| 컴포넌트 | 지연시간 | 주파수 |
|----------|---------|--------|
| LiDAR 취득 | 0-40ms | 10Hz |
| Perception (CenterPoint) | 50-100ms | 10Hz |
| NDT 스캔 매칭 | 20-50ms | 10Hz |
| EKF 업데이트 | 1-5ms | 50Hz |
| Behavior Planner | 50-200ms | 10Hz |
| MPC 궤적 추종 | 10-20ms | 50Hz |
| **End-to-End 총합** | **~200-300ms** | - |

| 리소스 | 사용량 |
|--------|--------|
| NDT 맵 (5km^2 도심) | ~50-100 MB |
| TensorRT 모델 | ~100-500 MB |
| GPU 메모리 (CenterPoint) | 2-4 GB |

---

## 2. CarlaSimulator

### 2.1 carla - 코어 시뮬레이터 (v0.10.0)

**엔진**: Unreal Engine 5.5 / **빌드**: CMake 3.27+ / Ninja

#### 2.1.1 클라이언트-서버 RPC 아키텍처

```
[Python/C++ Client] ←──MsgPack RPC──→ [UE5 Server]
        │                                    │
   LibCarla (C++)                     Chaos Physics
   PythonAPI (SWIG)                   Sensor Actors
                                      Traffic Manager
```

**RPC 계층**:
```cpp
// 동기/비동기 디스패치 메타데이터 포함 호출
template<typename...Args>
auto call(const std::string &function, Args &&...args) {
  return _client.call(function, Metadata::MakeSync(), std::forward<Args>(args)...);
}

// 서버: boost::asio io_context 기반 스레드 안전 바인딩
void BindSync(const std::string &name, FunctorT &&functor) {
  _server.bind(name, Wrapper::WrapSyncCall(_sync_io_context, ...));
}
```

**직렬화**: MsgPack 바이너리 프로토콜 (네트워크 효율 최적화)

#### 2.1.2 클라이언트 API 주요 클래스

| 클래스 | 역할 | 핵심 메서드 |
|--------|------|-----------|
| `Client` | 진입점, 에피소드 관리 | `LoadWorld()`, `ApplyBatch()`, `StartRecorder()` |
| `World` | 에피소드별 시뮬레이션 상태 | `GetMap()`, `SpawnActor()`, `Tick()`, `OnTick()` |
| `Actor` | 액터 인터페이스 | `GetTransform()`, `GetVelocity()`, `Listen()` |
| `Vehicle` | 차량 제어 | `ApplyControl()`, `ApplyAckermannControl()`, `SetAutopilot()` |
| `Sensor` | 센서 추상화 | `Listen(callback)` - 비동기 데이터 스트리밍 |

**에피소드 상태 관리**:
- `EpisodeState`: 프레임 원자적(atomic) 스냅샷 (모든 액터 위치/속도/가속도)
- `EpisodeProxy`: 스레드 안전 에피소드 핸들
- 가비지 컬렉션 정책: Disabled/Enabled/Inherited

#### 2.1.3 센서 레지스트리 (컴파일 타임 타입 맵)

```cpp
using SensorRegistry = CompositeSerializer<
  std::pair<ACollisionSensor *, s11n::CollisionEventSerializer>,
  std::pair<ADepthCamera *, s11n::ImageSerializer>,
  std::pair<ADVSCamera *, s11n::DVSEventArraySerializer>,
  std::pair<AGnssSensor *, s11n::GnssSerializer>,
  std::pair<AInertialMeasurementUnit *, s11n::IMUSerializer>,
  std::pair<ARadar *, s11n::RadarSerializer>,
  std::pair<ARayCastLidar *, s11n::LidarSerializer>,
  std::pair<ASceneCaptureCamera *, s11n::ImageSerializer>,
  std::pair<ASemanticSegmentationCamera *, s11n::ImageSerializer>,
  std::pair<AInstanceSegmentationCamera *, s11n::ImageSerializer>,
  // ... 17종 센서 등록
>;
```

**데이터 흐름**: UE5 센서 트리거 → 타입별 직렬화기 디스패치 → MsgPack 압축 → 네트워크 전송 → 클라이언트 콜백 역직렬화

| 센서 | 직렬화기 | 출력 형식 |
|------|---------|----------|
| RGB 카메라 | ImageSerializer | Uint8 PNG 압축 프레임 |
| Depth 카메라 | ImageSerializer | Float32 깊이 맵 |
| 시맨틱 분할 | ImageSerializer | CityScapes 라벨 |
| DVS (이벤트) | DVSEventArraySerializer | 스파이크 스트림 (x,y,t,polarity) |
| LiDAR | LidarSerializer | 3D 포인트 (x,y,z,intensity) |
| Radar | RadarSerializer | 추적 객체 (pos, vel, RCS) |
| IMU | IMUSerializer | 선형 가속도 + 각속도 |

#### 2.1.4 Traffic Manager - 파이프라인 병렬 처리

4단계 순차 파이프라인으로 다수 차량을 관리합니다:

```
LocalizationStage → CollisionStage → TrafficLightStage → MotionPlanStage
(차선/위치 계산)    (충돌 감지)       (신호등 상태)       (PID 제어 출력)
```

**MotionPlanStage PID 제어**:
| 환경 | Kp | Ki | Kd |
|------|----|----|-----|
| 도심 | 1.0 | 0.05 | 0.1 |
| 고속도로 | 0.8 | 0.04 | 0.08 |

**곡률 기반 감속**: `GetThreePointCircleRadius()` - 3점 원 반경 계산으로 커브 속도 결정

**차량별 파라미터**: 속도 오프셋, 차선 오프셋, 차선변경 공격성, 충돌 무시 확률, 선행 차량 거리

**InMemoryMap**: 공간 가속 구조 (쿼드트리/R-tree) - O(1) 웨이포인트 조회

#### 2.1.5 차량 물리 모델

```cpp
struct VehiclePhysicsControl {
  // 엔진
  std::vector<geom::Vector2D> torque_curve;  // RPM → 토크 룩업 테이블
  float max_torque = 300.0f;
  float max_rpm = 5000.0f;
  float rev_down_rate = 600.0f;

  // 변속기
  bool use_automatic_gears = true;
  float final_ratio = 4.0f;
  std::vector<float> forward_gear_ratios = {2.85, 2.02, 1.35, 1.0, ...};
  float transmission_efficiency = 0.9f;

  // 샤시
  float mass = 1000.0f;
  float drag_coefficient = 0.3f;
  geom::Vector3D inertia_tensor_scale;

  // 휠 (FL, FR, RL, RR)
  std::vector<WheelPhysicsControl> wheels;
};

struct WheelPhysicsControl {
  float tire_friction = 2.0f;
  float max_steer_angle = 70.0f;
  float radius = 0.3f;
  float max_brake_torque = 1500.0f;
};
```

물리 연산: 엔진 토크 → 변속기 → 디퍼렌셜 → 휠 포스 (UE5 Chaos Physics)

#### 2.1.6 도로 네트워크 (OpenDRIVE)

```cpp
class Map {
  MapData _data;    // Roads, lanes, junctions, signals
  Rtree _rtree;     // R-tree 공간 인덱스 (O(log N) 근접 탐색)

  std::optional<Waypoint> GetClosestWaypointOnRoad(
    const geom::Location &location, int32_t lane_type);

  geom::Transform ComputeTransform(Waypoint waypoint); // 3D 자세
};
```

- **Waypoint**: `(RoadId, LaneId, s)` - 호 길이 매개변수화
- R-tree 인덱싱: O(log N) 최근접 이웃 탐색
- 샘플링 해상도: 0.1m

---

### 2.2 scenario_runner - OpenSCENARIO 실행 엔진 (v0.9.16)

#### 2.2.1 3계층 아키텍처

```
OSC2 시나리오 (.osc2)
    ↓
[ANTLR4 파서 + AST 빌더] → 추상 구문 트리
    ↓
[Symbol Manager] → 시맨틱 분석 + 타입 체크
    ↓
[Scenario Executor] → py_trees Behavior Tree
    ↓
[Atomic Behaviors] → CARLA API 호출
```

#### 2.2.2 Behavior Tree 실행

**프레임워크**: py_trees (Python Behavior Tree)

```python
class ScenarioManager:
    def _tick_scenario(self, timestamp):
        GameTime.on_carla_tick(timestamp)

        if self._agent:
            ego_action = self._agent()
            self.ego_vehicles[0].apply_control(ego_action)

        self.scenario_tree.tick_once()  # 깊이 우선 순회

        if self.scenario_tree.status != py_trees.common.Status.RUNNING:
            self._running = False
```

**상태 흐름**: `SUCCESS` (조건 충족) / `RUNNING` (실행 중) / `FAILURE` (조건 위반)

**Atomic Behavior 목록**:

| 동작 | 기능 |
|------|------|
| `ActorTransformSetter` | 액터 텔레포트 |
| `ChangeTargetSpeed` | 목표 속도 램프 |
| `LaneChange` | 차선 변경 기동 |
| `WaypointFollower` | PID 기반 웨이포인트 추종 |

**OpenSCENARIO 2.0 지원 구조**:
- `do ... while(condition)` - 루핑
- `parallel(actions)` - 병렬 실행
- `sequential(actions)` - 순차 실행
- 이벤트 트리거: `on event_name do ...`

---

### 2.3 ros-bridge - ROS 1/2 통합 (16개 패키지)

#### 2.3.1 ROS 버전 호환 추상화 계층

```python
ROS_VERSION = get_ros_version()  # 런타임 버전 감지

if ROS_VERSION == 1:
    import rospy
    def init(name): rospy.init_node(name)
    def ok(): return not rospy.is_shutdown()

elif ROS_VERSION == 2:
    import rclpy
    def init(name, args=None): rclpy.init(args=args)
    def ok(): return rclpy.ok()
```

#### 2.3.2 센서-ROS 토픽 매핑

| CARLA 센서 | ROS 메시지 | 변환 세부사항 |
|-----------|-----------|-------------|
| RGB Camera | `sensor_msgs/Image` | RGB8, PNG 압축, cv_bridge |
| LiDAR | `sensor_msgs/PointCloud2` | x,y,z (float32) + intensity (uint8) |
| Radar | `carla_msgs/RadarDetection[]` | 위치 3D + 속도 3D + RCS |
| GNSS | `sensor_msgs/NavSatFix` | 위도/경도/고도 + 공분산 |
| IMU | `sensor_msgs/Imu` | 선형 가속도 + 각속도 + 9x9 공분산 |

**동기화 모드**: 선택적 배리어 동기화 - 모든 센서 콜백 완료 후 `CARLA.tick()`

#### 2.3.3 주요 패키지

| 패키지 | 기능 |
|--------|------|
| `carla_ros_bridge` | 코어 브릿지: 월드 상태 퍼블리싱, 액터 스포닝 |
| `carla_ackermann_control` | Ackermann 운동학 노드 |
| `carla_ad_demo` | 풀 자율주행 스택 데모 |
| `ros_compatibility` | ROS 1/2 추상화 |
| `rviz_carla_plugin` | RViz 시각화 플러그인 |

---

### 2.4 imitation-learning - 조건부 모방 학습 (CIL)

**프레임워크**: TensorFlow 1.x / **논문**: CoRL 2017

#### 네트워크 아키텍처

```
입력 이미지 (200x88 RGB)
    ↓ [CNN 인코더: 8개 Conv 블록]
    Conv1: 5x5, stride=2, 32ch → BN → ReLU → Dropout
    Conv2: 3x3, stride=1, 32ch → BN → ReLU → Dropout
    Conv3: 3x3, stride=2, 64ch → BN → ReLU → Dropout
    Conv4: 3x3, stride=1, 64ch → BN → ReLU → Dropout
    Conv5: 3x3, stride=2, 128ch → BN → ReLU → Dropout
    Conv6: 3x3, stride=1, 128ch → BN → ReLU → Dropout
    Conv7: 3x3, stride=1, 256ch → BN → ReLU → Dropout
    Conv8: 3x3, stride=1, 256ch → BN → ReLU → Dropout
    ↓ [Flatten → FC1(512) → FC2(512)]
    ↓
    ├── [속도 입력] → FC(128) → FC(128)
    ↓
    [Concatenate] → FC(512)
    ↓
    ├── Branch 0 (좌회전): FC(256) → FC(256) → [Steer, Gas, Brake]
    ├── Branch 1 (우회전): FC(256) → FC(256) → [Steer, Gas, Brake]
    ├── Branch 2 (직진):   FC(256) → FC(256) → [Steer, Gas, Brake]
    ├── Branch 3 (차선유지): FC(256) → FC(256) → [Steer, Gas, Brake]
    └── Branch 4 (속도):   FC(256) → FC(256) → [Speed]
```

**핵심 설계**:
- 고수준 내비게이션 명령에 따른 **조건부 브랜치 분기**
- Xavier 초기화 + BatchNorm + Dropout 정규화
- 멀티모달 입력: RGB 이미지 스트림 + 현재 속도 스칼라
- 손실함수: 브랜치별 L1/L2 회귀 + 가중 합산

---

### 2.5 reinforcement-learning - A3C 비동기 강화 학습

**프레임워크**: Chainer 1.24 / **알고리즘**: A3C (Mnih et al., 2016)

#### 네트워크 구조

```python
class A3CFF(chainer.ChainList, a3c.A3CModel):
    # 이미지 인코더 (DQN-style)
    NatureDQNHead:
        Conv1: 8x8, stride=4, 32ch → ReLU
        Conv2: 4x4, stride=2, 64ch → ReLU
        Conv3: 3x3, stride=1, 64ch → ReLU

    # 정책 헤드 (Actor)
    FCSoftmaxPolicy: FC → ReLU → ... → Softmax(n_actions)

    # 가치 헤드 (Critic)
    FCVFunction: FC → ReLU → ... → Scalar V(s)
```

**입력 전처리**:
- 이미지: 84x84 그레이스케일 리사이즈 → 정규화 (`/255 - 0.5`)
- 프레임 스태킹: `n_images_to_accum`개 연속 프레임 (시간적 정보)
- 측정값: 속도 등 스칼라 → 계수 스케일링

**학습 루프 (멀티프로세스)**:
```
각 워커 프로세스:
  1. 로컬 모델 동기화 ← 공유 모델
  2. n_steps 롤아웃 수집 (환경 상호작용)
  3. 어드밴티지 계산: A(s,a) = R - V(s)
  4. 손실 = -log_prob(a) * A - β * entropy + 0.5 * (R - V(s))^2
  5. 그래디언트 비동기 전파 → 공유 모델 업데이트
```

**하이퍼파라미터**: γ=0.99 (할인 인자), β=1e-2 (엔트로피 정규화), 보상 클리핑 [-1, 1]

---

### 2.6 driving-benchmarks - 평가 프레임워크 (CARLA 0.8.4)

**에이전트 인터페이스**:
```python
class Agent:
    def run_step(self, measurements, sensor_data, directions):
        """
        measurements: 차량 상태 (위치, 속도)
        sensor_data: 센서 출력 dict (카메라, LiDAR)
        directions: 내비게이션 명령 (좌/우/직)
        returns: VehicleControl (throttle, brake, steer)
        """
```

**벤치마크 스위트**:
| 스위트 | 시나리오 | 메트릭 |
|--------|---------|--------|
| CoRL 2017 | 내비게이션, 도심 주행, 날씨 변화 | 성공률, 충돌, 위반 |
| NoCrash | 안전 핵심 시나리오 | 충돌/차선이탈/신호위반 |

---

## 3. F1Tenth

### 3.1 f1tenth_gym - 물리 시뮬레이션 엔진

**패키지**: `f110_gym` v0.2.1 / **의존성**: NumPy, Numba, SciPy, OpenAI Gym

#### 3.1.1 차량 동역학 모델

**A. Kinematic Single-Track (KS) - 5 DOF**

상태: `[x, y, δ, v, ψ]` (위치, 조향각, 속도, 요)

```
dx/dt = v * cos(ψ)
dy/dt = v * sin(ψ)
dδ/dt = u₁           (조향 속도 입력)
dv/dt = u₂           (가속도 입력)
dψ/dt = (v / L) * tan(δ)    (L = lf + lr = 휠베이스)
```

**B. Dynamic Single-Track (ST) - 7 DOF**

상태: `[x, y, δ, v, ψ, ψ̇, β]` (+ 요 레이트, 슬립각)

- 타이어 횡력 모델 포함 (코너링 강성 계수: C_Sf, C_Sr)
- `|v| < 0.5 m/s` 에서 KS 모델로 자동 전환 (특이점 회피)
- 요 모멘트 방정식 포함 비선형 동역학

**차량 파라미터 (1/10 스케일)**:
```
mu = 1.0489          (마찰 계수)
C_Sf ≈ 20.9          (전방 코너링 강성)
C_Sr ≈ 20.9          (후방 코너링 강성)
lf ≈ 0.1157 m        (CG-전축 거리)
lr ≈ 0.1425 m        (CG-후축 거리)
h ≈ 0.0614 m         (CG 높이)
m ≈ 3.74 kg          (차량 질량)
I ≈ 0.04712 kg⋅m²    (요 관성 모멘트)
```

**제어 제약조건**:
```
조향각:      -1.066 rad ≤ δ ≤ 1.066 rad (±61.1°)
조향 속도:   -0.4 rad/s ≤ dδ/dt ≤ 0.4 rad/s
속도:        -13.6 m/s ≤ v ≤ 50.8 m/s
가속도:      -11.5 m/s² ≤ a ≤ 11.5 m/s²
파워 전환속도: v_switch = 7.319 m/s (이상에서 출력 제한 가속)
```

**적분법**: Euler (기본, dt=0.01s) 또는 RK4 / **Numba @njit** JIT 컴파일

#### 3.1.2 LiDAR 시뮬레이션

**알고리즘**: Distance Transform (EDT) 기반 반복 레이 트레이싱

```
1. 초기화: 점유 그리드에서 EDT (유클리드 거리 변환) 사전 계산
2. 각 빔: 레이저 위치에서 시작, 최근접 장애물 거리만큼 레이 전진
3. 종료: 거리 ≤ ε (0.0001m) 또는 max_range (30m) 도달
```

**스캐너 설정**:
- 빔 수: 1080 (기본)
- FOV: 4.7 rad (270°)
- 최대 범위: 30.0m
- 사전 계산: sin/cos 룩업 테이블 (2000 각도 빈)

**멀티에이전트 가림(Occlusion)**: 다른 차량 사각형의 4 꼭짓점 기반 레이-엣지 교차 검사

#### 3.1.3 충돌 검출

**알고리즘**: GJK (Gilbert-Johnson-Keerthi)

- Minkowski 차집합 기반 볼록 다각형 충돌 검출
- 복잡도: O(iterations), 보통 10회 이내
- 입력: 2개 볼록 다각형 (차량 본체 4 꼭짓점)

**환경 충돌 (iTTC)**:
```
ttc = (scan[i] - side_distance[i]) / (v * cos(beam_angle))
충돌 판정: 0 ≤ ttc < 0.005 초
```

#### 3.1.4 Gym 환경 인터페이스

**관측 공간**:
```python
{
  'scans': array (num_agents, num_beams),   # 레이저 거리
  'poses_x', 'poses_y', 'poses_theta',      # 에이전트 위치
  'linear_vels_x', 'linear_vels_y',          # 바디 프레임 속도
  'ang_vels_z',                              # 요 레이트
  'collisions': array (num_agents,),        # 바이너리 충돌 플래그
  'lap_times', 'lap_counts'                  # 랩 정보
}
```

**행동 공간**: `[desired_steering_angle (rad), desired_velocity (m/s)]` → 내부 PID로 `[steering_velocity, acceleration]` 변환

---

### 3.2 f1tenth_gym_ros - ROS 2 브릿지

**노드**: `GymBridge` (단일 ROS 2 노드)

| 항목 | 설정 |
|------|------|
| 물리 타이머 | 100 Hz (dt=0.01s) |
| 퍼블리싱 타이머 | 250 Hz (dt=0.004s) |
| 최대 에이전트 | 2 (ego + opponent) |

**ROS 2 토픽**:

| 방향 | 토픽 | 메시지 타입 |
|------|------|-----------|
| Sub | `/ego/commands/drive` | `AckermannDriveStamped` |
| Sub | `/initialpose` | `PoseWithCovarianceStamped` |
| Pub | `/ego/scan` | `LaserScan` (1080빔) |
| Pub | `/ego/odom` | `Odometry` |
| Pub | TF: `odom` → `base_link` | `TransformStamped` |

---

### 3.3 f1tenth_system - 실차량 온보드 스택

#### 노드 그래프

```
joy → joy_teleop ──┐
                    ├→ ackermann_mux → ackermann_to_vesc → vesc_driver
autonomous_node ───┘                                        ↓ (serial)
                                                         VESC ESC
urg_node (Hokuyo) → /scan
vesc_to_odom → /odom + TF

Static TF: base_link ↔ laser [x=0.27m, z=0.11m]
```

#### Throttle Interpolator - 속도 제한 사다리꼴 보간

```python
# 속도 (RPM) 보간 @ 75 Hz
max_delta_rpm = |speed_to_erpm_gain * max_acceleration / smoother_rate|
             = |4614 * 2.5 / 75| ≈ 153.8 ERPM/tick

smoothed_rpm = last_rpm + clip(desired - last, -max_delta, +max_delta)

# 서보 (조향) 보간 @ 75 Hz
max_delta_servo = |steering_gain * max_servo_speed / smoother_rate|
               = |1.2135 * 3.2 / 75| ≈ 0.0517 servo_units/tick
```

**안전 메커니즘**:
- 포화 클리핑: 최소/최대 제약 강제
- 속도 제한: 순간 토크 스파이크 방지
- 데드맨 스위치: 조이스틱 LB(텔레옵) / RB(자율주행) 버튼

---

### 3.4 vesc - 모터 컨트롤러 드라이버

#### 3.4.1 시리얼 패킷 프로토콜

```
소형 페이로드 (<256 bytes):
[SOF=0x02] [PayloadLen] [Payload...] [CRC16_HI] [CRC16_LO] [EOF=0x03]

대형 페이로드 (≥256 bytes):
[SOF=0x03] [Len_HI] [Len_LO] [Payload...] [CRC16] [EOF=0x03]
```

**CRC**: CRC-16 CCITT (다항식 0x1021, 초기값 0x0000, 반사 없음)

#### 3.4.2 모터 제어 모드

| 모드 | 토픽 | 변환식 |
|------|------|--------|
| 속도 (ERPM) | `commands/motor/speed` | `erpm = 4614 * v + offset` |
| 전류 | `commands/motor/current` | 양방향 가속 토크 |
| 브레이크 | `commands/motor/brake` | 감속 전용 |
| 서보 (조향) | `commands/servo/position` | `servo = -1.2135 * δ + 0.5304` |

서보 범위: [0.15, 0.85] (PWM)

#### 3.4.3 텔레메트리 데이터

```cpp
v_in()           // 입력 전압
temp_mos1-6()    // MOSFET 온도
current_motor()  // 모터 전류
rpm()            // 모터 RPM
duty_now()       // 현재 듀티 사이클
tachometer()     // 펄스 카운트
```

#### 3.4.4 운동학적 오도메트리

```cpp
// ERPM → 선속도
current_speed = (-state.speed - offset) / gain;

// 서보 위치 → 조향각
current_steering = (servo_pos - offset) / gain;

// 자전거 모델 요 레이트
yaw_rate = (current_speed * tan(current_steering)) / wheelbase;
// wheelbase = 0.25 m
```

데드밴드: `|v| < 0.05 m/s` → `v = 0` (노이즈 무시)

출력: `/odom` (nav_msgs::Odometry) + TF (`odom` → `base_link`)

---

### 3.5 f1tenth_racetracks - 트랙 데이터셋

**23개 서킷**: Austin, Brands Hatch, Budapest, Catalunya, Hockenheim, IMS, Melbourne, Mexico City, Montreal, Monza, Nurburgring, Oschersleben, Sakhir, Sao Paulo, Sepang, Shanghai, Silverstone, Sochi, Spa, Spielberg, Yas Marina, Zandvoort 등

#### 데이터 포맷

**레이스라인 CSV** (세미콜론 구분):
```
s_m; x_m; y_m; psi_rad; kappa_radpm; vx_mps; ax_mps2
```

| 필드 | 단위 | 설명 |
|------|------|------|
| `s_m` | m | 센터라인 기준 호 길이 |
| `x_m`, `y_m` | m | 글로벌 좌표 |
| `psi_rad` | rad | 헤딩 |
| `kappa_radpm` | 1/m | 곡률 (음수 가능) |
| `vx_mps` | m/s | 기준 속도 |
| `ax_mps2` | m/s^2 | 기준 가속도 |

**센터라인 CSV**:
```
x_m, y_m, w_tr_right_m, w_tr_left_m
```
(좌/우 트랙 폭 포함 → 주행 가능 회랑 정의)

**맵 YAML**:
```yaml
image: Austin_map.png
resolution: 0.08089          # m/pixel
origin: [-21.26, -70.80, 0.0]
occupied_thresh: 0.45
free_thresh: 0.196
```

---

## 4. 기술 스택 비교 요약

### 4.1 언어 및 빌드 시스템

| 프로젝트 | 주 언어 | 빌드 시스템 | GPU 활용 |
|----------|--------|-----------|---------|
| Autoware | C++17, Python | ament_cmake + colcon | TensorRT, CUDA, cuDNN |
| CARLA | C++, Python | CMake 3.27 + Ninja | UE5 Chaos Physics |
| F1Tenth | Python, C++ | pip/setuptools, ament_python, catkin | 선택적 (range_libc CUDA, Numba JIT) |

### 4.2 핵심 알고리즘

| 도메인 | Autoware | CARLA | F1Tenth |
|--------|----------|-------|---------|
| **Localization** | NDT + EKF (6-DOF) | - | MCL (Particle Filter) + 엔코더 오도메트리 |
| **Perception** | CenterPoint, YOLOX, BEV | 센서 시뮬레이션 | EDT 레이캐스팅 |
| **Planning** | BehaviorTree, Hybrid-A*, Frenet | OpenSCENARIO BT | Lattice, Graph, FGM, Wall Follower |
| **Control** | MPC + OSQP, Pure Pursuit | PID (Traffic Manager) | MPC, Pure Pursuit, Stanley, LQR, PID |
| **학습** | - | CIL (TF), A3C (Chainer) | - |
| **물리** | - | UE5 Chaos + 엔진/변속기 모델 | KS/ST 동역학 + 타이어 모델 |

### 4.3 통신 패턴

| 프로젝트 | 미들웨어 | 프로토콜 | 동기화 |
|----------|---------|---------|--------|
| Autoware | ROS 2 DDS | 토픽/서비스/액션 | QoS 기반 |
| CARLA | 커스텀 RPC | MsgPack 바이너리 | Tick 기반 (동기/비동기) |
| F1Tenth | ROS 2 (시뮬, 실차 foxy-devel) / ROS 1 (실차 레거시) | 토픽 | 타이머 콜백 |

### 4.4 성능 레이턴시

| 컴포넌트 | Autoware | CARLA | F1Tenth |
|----------|---------|-------|---------|
| 인지/센서 | 50-100ms (CenterPoint) | 10-50ms (Tick) | <1ms (EDT 레이캐스트) |
| 위치 추정 | 20-50ms (NDT) | - | <1ms (엔코더) |
| 제어 | 10-20ms (MPC) | <1ms (PID) | <1ms (PID) |
| **E2E** | **200-300ms** | **10-50ms** | **10ms** |
