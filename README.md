# 🚀 BillForU+ - 대용량 통신 요금 명세서 및 알림 발송 시스템

> 수백만 명의 사용자를 대상으로 하는 **대용량 청구 데이터 정산** 및 **이벤트 기반 실시간 알림 발송** 시스템
> #### 🗓️ 프로젝트 기간: 2026년 1월 7일 - 2026년 1월 27일 (3주)

<img width="1792" alt="image" src="https://github.com/user-attachments/assets/5deea4cf-af9e-42c1-bc20-3444078d36c0" />

---

## ⛳️ 프로젝트 배경 및 기획의도

### 기획 배경

1. **대용량 데이터 처리의 필요성**
   - 통신사의 월간 청구 데이터는 수백만 건에 달하며, 이를 정확하고 빠르게 처리하는 시스템이 필수적입니다.
   - 기존 단일 서버 방식으로는 대량 데이터 처리 시 병목현상과 장애 위험이 존재합니다.

2. **실시간 알림 발송의 중요성**
   - 고객에게 정확한 시점에 청구서를 전달하는 것은 고객 만족도와 직결됩니다.
   - 중복 발송, 누락 발송은 고객 불만과 비용 낭비로 이어집니다.

3. **안정적인 장애 대응 체계 필요**
   - 발송 실패 시 자동 재시도, 최종 실패 시 대체 채널(SMS) 발송 등 폴백 전략이 필요합니다.
   - 심야 시간대 발송 제한 등 고객 배려 기능이 요구됩니다.

### 프로젝트 목표 및 기대 효과

- 가상 유저 **100만 명**, 청구 이력 **500만 건** 안정적 처리 &rarr; **대용량 처리 역량 확보**
- Kafka 기반 비동기 메시지 처리 &rarr; **시스템 확장성 및 안정성 확보**
- Redis 기반 중복 방지 및 발송 제어 &rarr; **발송 정합성 보장**
- 멀티 채널 알림 (Email, SMS, Push) &rarr; **고객 도달률 극대화**
- **궁극적으로 실무에서 활용 가능한 대용량 분산 시스템 설계 및 구현 경험 확보**

### 협업 도구

- [Notion](https://www.notion.so) - 프로젝트 문서 관리
- [Jira](https://www.atlassian.com/software/jira) - 이슈 트래킹
- [Slack](https://slack.com) - 팀 커뮤니케이션

---

## 🏗️ 시스템 아키텍처

<img width="1007" alt="image" src="https://github.com/user-attachments/assets/5704cf1c-cfeb-479b-a845-56640ad5f47a" />

---

## 📁 프로젝트 구조

```
billing-system/
├── settings.gradle
├── build.gradle
├── docker-compose.yml
├── .env.example
│
├── core-module/          # 공통 도메인, 설정, 암호화
│   └── build.gradle
│
├── admin-module/         # 관리자 API 서버 (:8080)
│   └── build.gradle
│
├── batch-module/         # 요금 정산 배치 서버 (:8081)
│   └── build.gradle
│
└── notification-module/  # 알림 발송 서버 (:8082)
    └── build.gradle
```

### 모듈별 역할

| 모듈 | 포트 | 역할 |
|------|------|------|
| **core-module** | - | 공통 엔티티, 암호화(AES-256-GCM), 유틸리티 |
| **admin-module** | 8080 | 관리자 대시보드 API, 모니터링 |
| **batch-module** | 8081 | 월별 요금 정산, Kafka 메시지 발행 |
| **notification-module** | 8082 | 알림 수신/발송, 재시도 처리, DLQ 관리 |

---

## 🪐 주요 기능 및 서비스 구조도

<img width="1190" alt="image" src="https://github.com/user-attachments/assets/c467c6d1-7039-4715-a483-7400f1e4d918" />
<img width="1481" alt="image" src="https://github.com/user-attachments/assets/9a011bdf-1dd2-47d5-82f4-08d0bb32094b" />

---

## 💫 서비스 화면 및 기능소개

### 1. 관리자 대시보드
<!-- 대시보드 스크린샷 추가 예정 -->
- 실시간 발송 현황 모니터링
- 발송 성공/실패 통계 차트
- Redis 키 현황 조회

### 2. 배치 정산 실행
<!-- 배치 실행 화면 스크린샷 추가 예정 -->
- 월별 요금 정산 배치 실행
- 중복 실행 방지 (Redis Lock)
- 정산 이력 조회

### 3. 알림 발송 모니터링
<!-- 알림 모니터링 스크린샷 추가 예정 -->
- 실시간 발송 로그 조회
- 실패 메시지 재시도 관리
- DLQ(Dead Letter Queue) 처리 현황

### 4. 발송 제어 설정
<!-- 발송 제어 화면 스크린샷 추가 예정 -->
- 금지 시간대 설정 (22:00 ~ 08:00)
- 사용자별 선호 채널 관리
- 예약 발송 설정

---

## 🔄 전체 데이터 플로우

<img width="833" alt="스크린샷 2026-01-26 210820" src="https://github.com/user-attachments/assets/309e118a-efec-4b4a-8e94-a717db9e0a74" />

### 배치 처리 플로우

<img width="1680" alt="스크린샷 2026-01-26 224524" src="https://github.com/user-attachments/assets/b4ed5f5a-2191-4428-a0eb-acba77d3d501" />

---

## 📨 Notification 모듈 상세 플로우

<img width="359" alt="image" src="https://github.com/user-attachments/assets/9e36401b-fe25-4ee5-8d8a-233df0c54833" />

---

## 📬 Kafka 토픽 구조

### 토픽 설계

| 토픽명 | 파티션 | 용도 |
|--------|--------|------|
| `billing-event-topic` | 3 | 메인 청구 알림 메시지 |
| `billing-event-failover` | 3 | 재시도 메시지 |
| `billing-event-dlt` | 1 | Dead Letter (최종 실패) |

### 컨슈머 구조

<img width="1223" alt="image" src="https://github.com/user-attachments/assets/fd2429cc-ba8e-4b44-b6d7-784ebc72660d" />

---

## 🔑 Redis 키 구조

### 중복 방지 및 발송 제어

| 키 패턴 | 용도 | TTL |
|---------|------|-----|
| `sent:msg:{billId}:{type}` | 발송 완료 중복 방지 | 7일 |
| `retry:msg:{billId}:{type}` | 재시도 상태 관리 | 1시간 |
| `userPref:{userId}:{channel}` | 사용자 선호 채널/시간 | - |
| `scheduled:billing:{channel}` | 금지시간대 대기 메시지 | - |
| `batch:lock:{yearMonth}` | 배치 중복 실행 방지 | 1시간 |

### 예시

```bash
# 발송 완료 기록
SET sent:msg:12345:EMAIL "1" EX 604800

# 재시도 정보
HSET retry:msg:12345:EMAIL notificationId "100" type "EMAIL"

# 사용자 선호 설정
HSET userPref:1001:EMAIL quietStart "22:00" quietEnd "08:00"

# 금지시간대 대기열 (Sorted Set)
ZADD scheduled:billing:EMAIL 1706745600 "{message_json}"
```

---

## 🛠️ 기술 스택

### Backend

| 기술 | 버전 | 용도 |
|------|------|------|
| Java | 17 | 메인 언어 |
| Spring Boot | 3.x | 프레임워크 |
| Spring Batch | 5.x | 대용량 배치 처리 |
| Spring Kafka | - | 메시지 큐 연동 |
| Spring Data JDBC | - | DB 접근 |
| MySQL | 8.0 | 메인 데이터베이스 |
| Redis | - | 캐시, 중복방지, 락 |
| Apache Kafka | 7.4.0 | 메시지 브로커 |

### 보안

| 항목 | 방식 |
|------|------|
| 개인정보 암호화 | AES-256-GCM |
| 로그/응답 마스킹 | test****@test.com, 010-****-1234 |
| 환경변수 관리 | .env 파일 (Git 제외) |

### Infrastructure

| 서비스 | 환경 |
|--------|------|
| Admin/Notification/Kafka/Redis | Oracle Cloud |
| Batch/MySQL | AWS EC2 (t4g.small) |

---

## ▶️ 실행 방법

### 1. 저장소 클론

```bash
git clone <repo-url>
cd billing-system
```

### 2. 환경 변수 설정

```bash
cp .env.example .env.local
# .env.local 파일 편집
```

### 3. Docker 인프라 실행

```bash
docker-compose up -d
```

### 4. 모듈별 실행

```bash
# Admin API (8080)
./gradlew :admin-module:bootRun

# Batch Service (8081)
./gradlew :batch-module:bootRun

# Notification Service (8082)
./gradlew :notification-module:bootRun
```

---

## 🧪 API 테스트

### 기본 헬스체크

```bash
# Hello World
curl http://localhost:8080/api/hello

# 헬스체크
curl http://localhost:8080/api/actuator/health
```

### Swagger UI

| 모듈 | URL |
|------|-----|
| Admin | http://localhost:8080/api/swagger-ui.html |
| Batch | http://localhost:8081/api/swagger-ui.html |
| Notification | http://localhost:8082/api/swagger-ui.html |

---

## 📊 주요 API 엔드포인트

### Notification Module (:8082)

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/data/stats` | 대시보드 통계 |
| GET | `/api/retry/failed-list` | 실패 메시지 목록 |
| GET | `/api/retry/status-summary` | 상태별 요약 |
| GET | `/api/queue/detail` | 대기열 상세 |
| GET | `/api/redis/stats` | Redis 키 현황 |
| POST | `/api/retry/process/{id}` | 수동 재시도 |

---

## 🔐 보안 및 개인정보 보호

### 암호화 처리

- **저장 시**: AES-256-GCM으로 이메일, 휴대폰 번호 암호화
- **조회 시**: 복호화 후 마스킹 처리하여 응답

### 마스킹 예시

```
이메일: test****@test.com
휴대폰: 010-****-1234
계좌번호: ***-**-****
```

---

## 💡 Trouble Shooting

| 문제 | 원인 | 해결 |
|------|------|------|
| MySQL 연결 실패 | .env 미설정 | .env.local 파일 확인 |
| Redis DOWN | Redis 미실행 | `docker-compose up redis` |
| Kafka 연결 실패 | Broker 미실행 | `docker-compose up kafka` |
| Swagger 404 | 경로 오류 | `/api/swagger-ui.html` 확인 |
| 환경변수 오류 | .env 미적용 | IDE 재시작 |

---

## 🧑‍💻 팀원

| <img src="https://avatars.githubusercontent.com/coding-quokka101" width="80"><br><a href="https://github.com/coding-quokka101">👑 윤재영</a> | <img src="https://avatars.githubusercontent.com/hwantae" width="80"><br><a href="https://github.com/hwantae">권태환</a> | <img src="https://avatars.githubusercontent.com/shin-0328" width="80"><br><a href="https://github.com/shin-0328">신우철</a> | <img src="https://avatars.githubusercontent.com/jae-0" width="80"><br><a href="https://github.com/jae-0">박성준</a> | <img src="https://avatars.githubusercontent.com/SJP03" width="80"><br><a href="https://github.com/SJP03">이윤경</a> |
|:---:|:---:|:---:|:---:|:---:|
| <ul><li>보안</li><li>운영</li></ul> | <ul><li>이벤트 기반<br>메시지 플랫폼</li><li>보안</li></ul> | <ul><li>요금 정산 배치</li><li>운영</li></ul> | <ul><li>메일 발송 제어</li><li>운영</li></ul> | <ul><li>이벤트 기반<br>메시지 플랫폼</li><li>메일 발송 제어</li></ul> |

---

## 📅 개발 일정

<img width="999" height="895" alt="스크린샷 2026-01-26 231636" src="https://github.com/user-attachments/assets/2e21c113-1caa-41a8-8ad1-1e007acecddc" />



---

## 📚 참고 문서

- [Flyway 마이그레이션 가이드](./FLYWAY_README.md)
- [프론트엔드 README](https://github.com/Ureca4/ureca4-fe/blob/main/README.md)
