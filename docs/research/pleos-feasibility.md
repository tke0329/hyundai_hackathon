# Pleos 운전 습관 코치: 데이터와 주행 중 표시 가능성

조사일: 2026-10-28. 공식 Pleos 문서만 조사했다. 계정 신청, SDK 실행, 에뮬레이터 및 실차 검증은 수행하지 않았다. 아래의 ‘확인’은 문서 확인이며 실제 앱의 접근권한 확보를 뜻하지 않는다.

## 결론

차량 내 Pleos Connect 앱에서 속도·브레이크 페달 상태·조향각을 읽는 경로는 문서에 있다. 그러나 실제 대상 차량의 권한, 제공 주기, 신호 품질을 확인하기 전에는 급가감속·jerk·승차감의 실시간 감지 정확도를 약속할 수 없다. 주행 중 점수 화면은 DO(Distraction Optimization)와 플랫폼의 UX 제한을 준수하는 조건으로 검토해야 하며, 승인된 기능으로 확정되지 않았다.

## 차량 내 SDK와 클라우드 API의 구분

| 구분 | 차량 내 Pleos Connect 앱 | Cloud Vehicle Data API |
|---|---|---|
| 접근 경로 | AAOS Car API 및 Pleos Vehicle SDK | HTTPS REST API, 액세스 토큰 |
| 데이터 경로 | 차량 내 Vehicle Service와 통신 | 커넥티드카 텔레매틱스 데이터 조회 |
| 이번 기획의 역할 | 주행 중 감지·점수 표시의 우선 검토 경로 | 별도 연계 후보. 국내 실시간 입력으로 가정하지 않음 |
| 주요 제약 | 차종·프로퍼티별 지원과 접근권한, UX 제한 | 이용 지역, 동의·인증, 차종별 필드, 수집·전달 지연 |

Vehicle SDK는 차량 내 AAOS Vehicle Service에 연결한다. 표준 기능은 AAOS API를 먼저 쓰는 정책이며 속도는 표준 데이터 예시다. [Vehicle SDK 개요](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [SDK API 정책](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)

Cloud Vehicle Data API는 공유 동의 범위의 텔레매틱스 데이터와 OAuth 기반 인증을 사용한다. 공식 개요는 2026년 8월 기준 유럽만 제공한다고 명시한다. 사업자용 기초 데이터는 소유자 동의 후 24시간부터 사용 가능하다는 안내가 있다. 이것은 각 샘플의 전달 지연이나 실시간성을 보장하는 수치가 아니다. [Cloud API 개요](https://document.pleos.ai/en/api-reference/vehicle-data-api/intro)

## 핵심 신호: 확인된 범위

| 신호 | 차량 내 경로와 확인 사실 | 미확정 사항 |
|---|---|---|
| 속도 | 공식 FAQ가 `CarPropertyManager`로 `PERF_VEHICLE_SPEED` 조회 또는 변경 이벤트 구독을 안내한다. [FAQ](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq) | 대상 차량 권한 승인, 실제 주기·지연·결측·정밀도 |
| 브레이크 | `getCurrentBrakePedalStatus`, `registerBrakePedalStatus`, capability 확인 API가 문서 목록에 있다. [Brake API](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/entry) | 페달 상태와 제동 강도는 다르다. 압력·패드 사용량·회생제동 비율 접근은 확인하지 못함 |
| 조향 | `getCurrentSteeringWheelAngle`, `registerSteeringWheelAngle`, capability 확인 API가 문서 목록에 있다. [SteeringWheel API](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/entry) | 실차 권한·주기·단위와 해상도. 정차 중 조향 판단에는 속도와 시각 정렬도 필요 |
| 종·횡·수직 가속도 | 이번 공식 문서 조사에서 현재 차량의 측정 가속도를 일반 앱에 제공하는 로컬 경로를 확정하지 못했다 | 센서 탑재·권한·좌표계·차체 기준 보정. 속도 차분은 가능성 검토용이며 직접 측정값과 구분 |

브레이크 도메인의 대표 읽기 권한은 `pleos.car.permission.CAR_DRIVE`다. 각 API의 `@RequiresPermission` 확인이 필요하고, 누락 시 권한 오류가 발생할 수 있다. [권한 선언](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)

공식 프로젝트 안내에서는 Vehicle State와 NaviHelper를 별도 신청 없이 사용 가능한 상태로 설명하지만, 계정의 현재 상태와 개별 신호 접근은 이번 조사에서 검증하지 않았다. [프로젝트·SDK 접근 신청](https://document.pleos.ai/en/docs/pleos-only/create-my-project)

차종별 Vehicle SDK capability와 AAOS 프로퍼티 접근 가능성을 런타임에 확인해야 한다. 제공되지 않는 특수 프로퍼티는 기술지원 검토를 요청하도록 안내한다. 따라서 문서에 API 이름이 있다는 사실만으로 모든 차종의 제공을 확정하지 않는다. [호환성·가용성](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability)

### 혼동하기 쉬운 클라우드 필드

Cloud `/driving/data`에는 종가속도(`g`), yaw 각속도, 조향각·회전속도의 트립 배열이 정의돼 있다. 날짜 범위로 조회하는 구조이며 이 문서만으로 로컬 실시간 스트림이나 고정 샘플링 주기를 보장할 수 없다. 예제 배열의 길이를 근거로 1Hz라고 추정하지 않는다. [Get Vehicle Operation Data](https://document.pleos.ai/api-reference/vehicle-data-api/vehicle-data-api/common/get-vehicle-operation-data)

Cloud `/speed`에는 계기판 속도 및 각 휠 속도 배열이 있다. 이것을 차량 내 `PERF_VEHICLE_SPEED` 구독과 같은 기능으로 취급하지 않는다. [Get Speed Data](https://document.pleos.ai/api-reference/vehicle-data-api/vehicle-data-api/common/get-speed-data)

Cloud 필드는 차종·파워트레인·연식에 따라 달라지며, 수집되지 않는 값은 null일 수 있다. [Cloud FAQ](https://document.pleos.ai/api-reference/vehicle-data-api/faq)

## 주행 중 점수 표시 제약

- DO를 선언하지 않은 앱은 기본적으로 주차 중 사용 가능, 주행 중 사용 불가다. 정책은 지역·플랫폼 버전·양산차에 따라 달라질 수 있다. [지역별 주행 제한](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)
- DO 선언만으로 제한이 사라지지 않는다. `CarUxRestrictionsManager`를 구독하고 `ActiveRestrictions`에 따라 기능을 축소·차단해야 한다. [주행 규제 개발 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- 간결한 정보, 최소 조작, 높은 대비를 지향하며 반복 애니메이션·화면 전환을 줄여야 한다. 운전자의 주의를 끄는 게임 활동도 유도하지 말라고 안내한다. [운전 안전 설계](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)

따라서 ‘점수의 재미’는 주행 중 게임 조작·득점 경쟁이 아니라, 자동 기록과 주차 후 성장 확인에 두는 설계를 제안한다. 주행 화면은 안정적인 점수 한 개와 최소 상태만 표시하고 점수 증감 애니메이션·반복 효과음·상세 사유 확인은 줄인다. 이는 조사 결과에서 도출한 설계 제안이며 해당 점수 화면의 심사 통과를 의미하지 않는다.

## 기획서에 사용할 표현

> 차량 내 제공 가능한 주행 신호를 바탕으로 운전 습관을 관찰하고 점수 변화를 기록한다. 실제 지원 차종, 접근권한 및 데이터 주기는 SDK·실차 검증 후 확정한다. 주행 중에는 플랫폼의 운전 집중 제한을 준수하는 간결한 정보를 제공하고, 상세 원인과 개선 안내는 주차 후 확인한다.

## 다음 검증의 통과 조건

1. 대상 Pleos 환경에서 속도·조향·브레이크 권한과 capability 확인.
2. 타임스탬프, 갱신 간격, 결측, 지연을 기록해 급가감속과 부드러운 정차 감지에 충분한지 판단.
3. 가속도 직접 접근 또는 속도 차분 방식의 오차 확인. 미지원이면 관련 기능을 시뮬레이션으로 명시.
4. DO 적용, 제한 상태 전환, 백그라운드 기록 및 주차 후 리포트 흐름 검증.
5. 긴급제동 원인·마찰제동 비율·실제 편안함·부품 마모량은 미확인 입력만으로 단정하지 않도록 안내 문구 검토.

자동주행 시스템의 운전 주체·제어 상태 및 FSD 직접 연동 권한은 이번 조사에서 확인하지 않았다. 미래 확장은 협력사 접근권한과 탑승자 피드백 수집을 별도로 검증해야 한다.
