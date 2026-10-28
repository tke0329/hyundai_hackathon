# Pleos에서 핵심 주행 신호와 주행 중 점수 표시를 지원하는가

Labels: wayfinder:research
Type: research
Status: resolved
Assignee: research-agent
Parent: ../map.md
Blocked by:

## Question

공식 문서 기준으로 공모전 대상 Pleos Connect 앱에서 속도·가속도·조향·제동 신호를 어떤 방식으로 사용할 수 있는가? 주행 중 점수 표시의 제약은 무엇인가? 공개 Cloud Vehicle Data API와 차량 내 SDK를 구분하고, 문서 존재·실제 권한·샘플링 주기·차종 지원 중 확인한 범위를 명시한다.

## Answer

2026-10-28 공식 문서 조사 완료. 차량 내 속도는 AAOS CarPropertyManager/PERF_VEHICLE_SPEED, 제동 페달 상태·조향각은 Vehicle SDK의 조회·구독 경로가 확인된다. 실차 접근권한과 샘플링 주기, 로컬 가속도 측정은 미확정이다. Cloud Vehicle Data API의 가속도·조향 배열은 별도 경로이며 현 문서상 유럽 지원이므로 국내 실시간 입력의 근거로 쓰지 않는다. 주행 중 점수 표시는 DO와 CarUxRestrictions 준수 및 심사 검증을 전제로 한다.

세부 근거와 다음 검증: [Pleos 운전 습관 코치: 데이터와 주행 중 표시 가능성](../../../docs/research/pleos-feasibility.md).

빈 작업 폴더에 Git 원격이 없어 연구 브랜치·push는 생략하고 로컬 조사 산출물로 남겼다.
