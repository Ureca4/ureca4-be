# 🧪 Notification Module API 테스트 가이드

> 빌포유(Bill For You) 알림 시스템의 전체 API 테스트 가이드입니다.

---

## 📌 목차

1. [사전 준비](#-사전-준비)
2. [API 목록 요약](#-api-목록-요약)
3. [테스트 시나리오](#-테스트-시나리오)
4. [API 상세 가이드](#-api-상세-가이드)
5. [트러블슈팅](#-트러블슈팅)

---

## 🔧 사전 준비

### 1. 인프라 실행

```bash
# Docker Compose로 Kafka, Zookeeper, Redis 실행
docker-compose up -d

# 컨테이너 상태 확인
docker ps
```

**필요한 서비스:**
| 서비스 | 포트 | 용도 |
|--------|------|------|
| MySQL | 3306 | 데이터 저장 |
| Redis | 6379 | 중복 방지, 대기열 |
| Kafka | 9092 | 메시지 브로커 |
| Zookeeper | 2181 | Kafka 코디네이터 |

### 2. 애플리케이션 실행

```bash
# notification-module 실행
./gradlew :notification-module:bootRun

# 또는 IDE에서 NotificationModuleApplication.java 실행
```

### 3. Swagger UI 접속

```
http://localhost:8080/swagger-ui.html
```

---

## 📋 API 목록 요약

| 태그 | 경로 | API 수 | 설명 |
|------|------|--------|------|
| 1. 통합 발송 테스트 | `/api/test` | 7개 | 이메일 발송 및 금지시간 테스트 |
| 2. 시스템 정책 | `/api/policy` | 3개 | 금지 시간대 정책 조회 |
| 3. 사용자 알림 설정 | `/api/user-prefs` | 11개 | 사용자별 채널/금지시간 설정 |
| 4. 대기열 모니터링 | `/api/queue` | 7개 | 금지시간 대기열 관리 |
| 5. Redis 모니터링 | `/api/redis` | 6개 | 중복방지 키 관리 |
| 6. 재시도/DLT 관리 | `/api/retry` | 6개 | 실패 메시지 재시도 |

**총 40개 API**

---

## 🚀 테스트 시나리오

### 시나리오 1: 기본 이메일 발송 

**목표**: 이메일이 정상적으로 발송되는지 확인

```
1. GET /api/policy/check
   → 현재 금지시간인지 확인

2. POST /api/test/send-with-user-pref
   Body: {
     "billId": 1001,
     "userId": 1,
     "billYearMonth": "202501",
     "recipientEmail": "test@yopmail.com",
     "totalAmount": 85000
   }
   → 이메일 발송 확인

3. GET /api/redis/check/1001?type=EMAIL
   → 중복방지 키 생성 확인
```

**예상 결과:**
- ✅ 이메일 발송 성공 (4billforu@gmail.com으로 수신)
- ✅ Redis에 `sent:msg:1001:EMAIL` 키 생성
- ✅ DB notifications 테이블에 SENT 상태 저장

---

### 시나리오 2: 중복 발송 방지 

**목표**: 동일한 billId로 재발송 시 차단되는지 확인

```
1. POST /api/test/send-with-user-pref
   Body: { "billId": 1001, ... }  ← 동일한 billId!
   → "중복 발송 차단" 메시지 확인

2. DELETE /api/redis/clear/1001?type=EMAIL
   → 중복방지 키 삭제

3. POST /api/test/send-with-user-pref
   Body: { "billId": 1001, ... }
   → 재발송 성공!
```

**예상 결과:**
- ✅ 첫 번째: 중복 차단
- ✅ 키 삭제 후: 재발송 가능

---

### 시나리오 3: 금지 시간대 테스트

**목표**: 22:00~08:00 금지시간에 대기열 저장 확인

```
1. GET /api/test/check-time?simulatedTime=23:00
   → isBlockTime: true 확인

2. POST /api/test/send-with-user-pref/at?simulatedTime=23:00
   Body: { "billId": 2001, "userId": 1, ... }
   → action: "WOULD_BE_QUEUED" 확인

3. GET /api/test/check-time?simulatedTime=10:00
   → isBlockTime: false 확인
```

**예상 결과:**
- ✅ 23:00 → 금지 시간 (대기열 저장)
- ✅ 10:00 → 정상 시간 (즉시 발송)

---

### 시나리오 4: 사용자별 채널 비활성화 

**목표**: 사용자가 EMAIL 채널을 비활성화하면 발송 차단

```
1. PUT /api/user-prefs/9999/EMAIL/toggle?enabled=false
   → 채널 비활성화

2. POST /api/test/send-with-user-pref
   Body: { "billId": 3001, "userId": 9999, ... }
   → reason: "CHANNEL_DISABLED" 확인

3. PUT /api/user-prefs/9999/EMAIL/toggle?enabled=true
   → 채널 다시 활성화
```

**예상 결과:**
- ✅ 비활성화 시: 발송 차단
- ✅ 활성화 시: 정상 발송

---

### 시나리오 5: 사용자별 금지 시간대

**목표**: 사용자별 금지시간 설정 및 적용 확인

```
1. PUT /api/user-prefs/9999/EMAIL/quiet-time?quietStart=18:00&quietEnd=09:00
   → 개인 금지시간 설정 (저녁 6시 ~ 아침 9시)

2. GET /api/user-prefs/9999/check-quiet?channel=EMAIL
   → 현재 금지시간인지 확인

3. DELETE /api/user-prefs/9999/EMAIL/quiet-time
   → 금지시간 제거 (시스템 정책만 적용)
```

**예상 결과:**
- ✅ 사용자 설정이 시스템 정책보다 우선 적용

---

### 시나리오 6: 재시도 및 DLT

**목표**: 1% 실패 → 재시도 → DLT 플로우 확인

```
1. Kafka로 대량 발송 (터미널):
   docker exec -it local-kafka bash
   kafka-console-producer --topic billing-event --bootstrap-server localhost:9092
   
   입력 (여러 번):
   {"billId":90001,"userId":1,"billYearMonth":"202501","recipientEmail":"test@yopmail.com","totalAmount":85000}
   {"billId":90002,"userId":1,"billYearMonth":"202501","recipientEmail":"test@yopmail.com","totalAmount":85000}
   ... (100건 이상 입력)

2. GET /api/retry/status-summary
   → SENT, FAILED 개수 확인

3. GET /api/retry/dlt-candidates
   → 3회 재시도 후 DLT 이동 메시지 확인
```

**예상 결과:**
- ✅ 약 1% 실패 발생
- ✅ 자동 재시도 (최대 3회)
- ✅ 3회 실패 시 DLT 이동

---

### 시나리오 7: 대기열 수동 처리 

**목표**: 금지시간 대기열의 메시지 수동 처리

```
1. POST /api/queue/add
   Body: { "billId": 4001, "userId": 1, ... }
   → 대기열에 수동 추가

2. GET /api/queue/status
   → totalCount 확인

3. POST /api/queue/process?maxCount=100
   → 대기열 수동 처리

4. GET /api/queue/status
   → totalCount: 0 확인
```

**예상 결과:**
- ✅ 대기열 추가/조회/처리 정상 동작

---

## 📚 API 상세 가이드

### 1. 통합 발송 테스트 (`/api/test`)

| API | Method | 경로 | 설명 |
|-----|--------|------|------|
| 1-1 | POST | `/send` | 이메일 발송 (시스템 정책) |
| 1-2 | POST | `/send-with-time` | 시뮬레이션 시간 발송 |
| 1-3 | GET | `/check-time` | 시스템 정책 체크 |
| 1-4 | GET | `/user-quiet/{userId}` | 사용자별 금지시간 체크 |
| 1-5 | GET | `/user-quiet/{userId}/at` | 사용자별 금지시간 체크 (시뮬레이션) |
| 1-6 | POST | `/send-with-user-pref` | 사용자 설정 기반 발송 ⭐ |
| 1-7 | POST | `/send-with-user-pref/at` | 사용자 설정 기반 발송 (시뮬레이션) |

#### 📌 주요 API: 1-6. 사용자 설정 기반 발송

```bash
POST /api/test/send-with-user-pref
Content-Type: application/json

{
  "billId": 1001,
  "userId": 1,
  "billYearMonth": "202501",
  "recipientEmail": "test@yopmail.com",
  "recipientPhone": "01012345678",
  "totalAmount": 85000,
  "planFee": 55000,
  "addonFee": 15000,
  "microPaymentFee": 15000,
  "billDate": "2025-01-31",
  "dueDate": "2025-02-15",
  "planName": "5G 프리미어 에센셜"
}
```

**응답 예시 (성공):**
```json
{
  "userId": 1,
  "billId": 1001,
  "currentTime": "14:30:00",
  "action": "SENT",
  "message": "✅ 이메일이 즉시 발송되었습니다."
}
```

**응답 예시 (중복 차단):**
```json
{
  "action": "DUPLICATE_BLOCKED",
  "message": "⚠️ 이미 발송된 청구서입니다. 중복 발송이 차단되었습니다."
}
```

**응답 예시 (금지 시간):**
```json
{
  "action": "QUEUED",
  "message": "⏰ 금지 시간입니다 (SYSTEM_POLICY). 대기열에 저장되었습니다."
}
```

---

### 2. 시스템 정책 (`/api/policy`)

| API | Method | 경로 | 설명 |
|-----|--------|------|------|
| 2-1 | GET | `/email` | EMAIL 정책 조회 ⭐|
| 2-2 | GET | `/check` | 현재 금지시간 여부 |
| 2-3 | GET | `/check?time=23:00` | 특정 시간 금지시간 여부 |

---

### 3. 사용자 알림 설정 (`/api/user-prefs`)

| API | Method | 경로 | 설명 |
|-----|--------|------|------|
| 3-1 | GET | `/{userId}/check-quiet` | 금지시간 체크 ⭐|
| 3-2 | GET | `/{userId}/check-quiet-at?time=23:00` | 특정 시간 금지시간 체크 |
| 3-3 | GET | `/{userId}` | 전체 설정 조회 |
| 3-4 | GET | `/{userId}/{channel}` | 특정 채널 설정 조회 |
| 3-5 | POST | `/` | 설정 저장/수정 |
| 3-6 | PUT | `/{userId}/{channel}/quiet-time` | 금지시간대 설정 ⭐ |
| 3-7 | DELETE | `/{userId}/{channel}/quiet-time` | 금지시간대 제거 |
| 3-8 | PUT | `/{userId}/{channel}/toggle` | 채널 활성화/비활성화 ⭐ |
| 3-9 | DELETE | `/{userId}` | 전체 설정 삭제 |
| 3-10 | GET | `/admin/with-quiet-time` | 금지시간 설정된 사용자 목록 |
| 3-11 | GET | `/admin/stats` | 채널별 활성 사용자 수 |

#### 📌 주요 API: 3-6. 금지시간대 설정

```bash
PUT /api/user-prefs/9999/EMAIL/quiet-time?quietStart=22:00&quietEnd=08:00
```

**응답:**
```json
{
  "success": true,
  "message": "✅ 금지 시간대가 설정되었습니다: 22:00 ~ 08:00",
  "userId": 9999,
  "channel": "EMAIL"
}
```

#### 📌 주요 API: 3-8. 채널 활성화/비활성화

```bash
PUT /api/user-prefs/9999/EMAIL/toggle?enabled=false
```

**응답:**
```json
{
  "success": true,
  "message": "🚫 채널이 비활성화되었습니다.",
  "userId": 9999,
  "channel": "EMAIL",
  "enabled": false
}
```

---

### 4. 대기열 모니터링 (`/api/queue`)

| API | Method | 경로 | 설명 |
|-----|--------|------|------|
| 4-1 | GET | `/status` | 대기열 상태 조회 ⭐|
| 4-2 | GET | `/ready` | 발송 가능 메시지 조회 |
| 4-3 | GET | `/detail` | 대기열 상세 정보 |
| 4-4 | POST | `/add` | 메시지 수동 추가 |
| 4-5 | DELETE | `/clear` | 대기열 초기화 |
| 4-6 | GET | `/scheduler-status` | 스케줄러 상태 |
| 4-7 | POST | `/process` | 대기열 수동 처리 ⭐ |

#### 📌 주요 API: 4-7. 대기열 수동 처리

```bash
POST /api/queue/process?maxCount=100
```

**응답:**
```json
{
  "success": true,
  "message": "✅ 대기열 처리 완료. 5건 성공, 0건 실패",
  "processed": 5,
  "beforeSize": 5,
  "afterSize": 0
}
```

---

### 5. Redis 모니터링 (`/api/redis`)

| API | Method | 경로 | 설명 |
|-----|--------|------|------|
| 5-1 | GET | `/keys` | 중복방지 키 목록 |
| 5-2 | GET | `/check/{billId}` | 특정 billId 중복 체크 ⭐ |
| 5-3 | GET | `/stats` | 키 패턴별 통계 |
| 5-4 | DELETE | `/clear` | 키 전체 삭제 |
| 5-5 | DELETE | `/clear/{billId}` | 특정 키 삭제 ⭐ |
| 5-6 | POST | `/mark/{billId}` | 수동으로 키 생성 |

#### 📌 주요 API: 5-2. 특정 billId 중복 체크

```bash
GET /api/redis/check/1001?type=EMAIL
```

**응답:**
```json
{
  "billId": 1001,
  "type": "EMAIL",
  "key": "sent:msg:1001:EMAIL",
  "exists": true,
  "isDuplicate": true,
  "ttl_seconds": 604800,
  "ttl_days": 7,
  "status": "🔴 이미 발송됨 - 중복 발송 차단"
}
```

---

### 6. 재시도/DLT 관리 (`/api/retry`)

| API | Method | 경로 | 설명 |
|-----|--------|------|------|
| 6-1 | GET | `/status-summary` | Notification 상태 요약 ⭐ |
| 6-2 | GET | `/failed-count` | FAILED 개수 조회 |
| 6-3 | GET | `/failed-list` | FAILED 목록 조회 |
| 6-4 | POST | `/run` | 재시도 스케줄러 수동 실행 ⭐|
| 6-5 | POST | `/run/{notificationId}` | 특정 메시지 재시도 |
| 6-6 | GET | `/dlt-candidates` | DLT 후보 조회 ⭐ |

#### 📌 주요 API: 6-1. Notification 상태 요약

```bash
GET /api/retry/status-summary
```

**응답:**
```json
{
  "summary": {
    "SENT": 611,
    "FAILED": 5,
    "RETRY": 3,
    "PENDING": 3
  },
  "total": 622,
  "description": {
    "SENT": "발송 완료",
    "FAILED": "발송 실패 (재시도 대상)",
    "RETRY": "재시도 중",
    "PENDING": "대기 중 (금지시간)"
  }
}
```

#### 📌 주요 API: 6-6. DLT 후보 조회

```bash
GET /api/retry/dlt-candidates?limit=20
```

**응답:**
```json
{
  "count": 5,
  "description": "3회 재시도 후 최종 실패한 메시지 (수동 처리 필요)",
  "messages": [
    {
      "notificationId": 628,
      "userId": 1,
      "retryCount": 3,
      "errorMessage": "Moved to DLT after 3 retries",
      "createdAt": "2026-01-21T14:49:09"
    }
  ]
}
```

---

## 🔥 트러블슈팅

### 1. 이메일이 발송되지 않아요

**원인**: Gmail SMTP 설정 문제

**해결**:
```yaml
# .env.local 확인

   MAIL_USERNAME=[발신자이메일]
   MAIL_PASSWORD=[앱 비밀번호]
   
```

### 2. 중복 발송이 차단되지 않아요

**원인**: Redis 연결 문제

**해결**:
```bash
# Redis 연결 확인
docker exec -it local-redis redis-cli ping
# 응답: PONG
```

### 3. Kafka 메시지가 처리되지 않아요

**원인**: 토픽이 없거나 Consumer 문제

**해결**:
```bash
# 토픽 목록 확인
docker exec -it local-kafka kafka-topics --list --bootstrap-server localhost:9092

# 토픽 생성 (없으면)
docker exec -it local-kafka kafka-topics --create --topic billing-event --partitions 3 --bootstrap-server localhost:9092
```

### 4. FK 에러 발생

**원인**: 테스트용 billId가 bills 테이블에 없음

**해결**: 실제 배치에서 생성된 billId 사용, 또는 테스트 시 존재하는 billId 사용

---

## ✅ 테스트 체크리스트

| 기능 | 확인 |
|------|------|
| ✉️ 이메일 실제 발송 | ☐ |
| 🔄 Kafka 3파티션 / 3컨슈머 | ☐ |
| 🚫 중복 발송 방지 (Redis) | ☐ |
| ⏰ 시스템 금지 시간대 (22:00~08:00) | ☐ |
| 👤 사용자별 금지 시간대 | ☐ |
| 📴 채널 활성화/비활성화 | ☐ |
| 📬 대기열 저장/처리 | ☐ |
| 🔁 1% 실패 → 재시도 | ☐ |
| 💀 3회 실패 → DLT | ☐ |

---

**작성일**: 2026-01-21  

