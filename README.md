# apt-price-tracker

네이버 모바일 부동산(`m.land.naver.com`)에서 매물을 수집해서
`data/apt-listings.json`에 저장하고,
신규/가격 하락 매물을 Teams로 알리는 배치 프로젝트입니다.

## 기능

- 대상 동(현재 49개) 순회 수집
- 20~39평 필터링
- 이전 실행 데이터와 비교해서 신규/가격하락 감지
- Teams Webhook 알림 전송
- 수집 결과 JSON 저장

## 기술 스택

- Kotlin
- Spring Boot
- Java 17
- Gradle Wrapper

## 프로젝트 구조

```text
src/main/kotlin/me/aptprice
├── AptpriceApplication.kt
├── model/Models.kt
├── repository/FileDataRepository.kt
├── service/
│   ├── NaverService.kt
│   └── TeamsNotifierService.kt
└── util/BotRunner.kt
```

## 로컬 실행

```bash
./gradlew clean bootRun
```

Teams 알림까지 쓰려면:

```bash
export TEAMS_WEBHOOK_URL="https://outlook.office.com/webhook/..."
./gradlew clean bootRun
```

## 주요 설정

설정 파일: `src/main/resources/application.yml`

- `bot.enabled`: 봇 실행 여부 (기본 `true`)
- `bot.safe.max-regions-per-run`: 한 번에 수집할 최대 동 수 (기본 `49`)
- `bot.safe.region-delay-min-ms`
- `bot.safe.region-delay-max-ms`
- `naver.safe.*`: 요청 재시도, 백오프, 타임아웃, 쿨다운 관련 설정

## GitHub Actions

워크플로우: `.github/workflows/apt-price-bot.yml`

- 평일 KST 09:00 자동 실행
- 수동 실행 지원 (`workflow_dispatch`)
- 기본값은 전체 동 수집 (`max_regions_per_run=49`)
- `commit_data=true`면 `data/apt-listings.json` 변경분 자동 커밋

현재 러너 조건:

```yaml
runs-on: [self-hosted, macOS, ARM64]
```

## Self-hosted runner 관리

```bash
cd ~/actions-runner/apt-price-tracker-runner
./svc.sh status
./svc.sh start
./svc.sh stop
```

## 테스트

```bash
./gradlew test
```
