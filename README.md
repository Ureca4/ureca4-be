
# 🚀 Billing System – Team Onboarding Guide

## ✅ 1. 프로젝트 개요

이 프로젝트는 **Spring Boot 기반 멀티 모듈 구조**로 구성된 Billing System 입니다.
각 모듈 간 역할을 분리하고 재사용성을 높이기 위해 아래와 같은 구조를 사용합니다.

---

## 📁 2. 프로젝트 구조

```
project-root
 ┣ settings.gradle
 ┣ build.gradle
 ┣ .env.local
 ┣ .env.prod
 ┣ core-module
 ┃ ┗ build.gradle
 ┣ admin-module
 ┃ ┗ build.gradle
 ┣ batch-module
 ┃ ┗ build.gradle
 ┣ notification-module
   ┗ build.gradle
```

### 🔎 모듈 역할

| 모듈           | 역할            |
| ------------ | ------------- |
| core-module  | 공통 도메인, 공통 설정 |
| admin-module | 관리자 서버        |
| batch-module | 요금 정산 서버 |
| notification-module | 명세서 발송 서버 |
---

## 🧩 3. 멀티 모듈 설정

### 📌 settings.gradle

루트 경로에 있어야 하며 모듈을 등록합니다.

```gradle
rootProject.name = "billing-system"

include("core-module")
include("admin-module")
include("batch-module")
include("notification-module")
```

---

### 📌 Root build.gradle (공통 설정)

```gradle
subprojects {
    apply plugin: 'java'

    group = 'com.ureca'
    version = '1.0.0'

    repositories {
        mavenCentral()
    }

    test {
        useJUnitPlatform()
    }
}
```

---

## 🛠 4. 개발 환경

| 항목    | 요구사항               |
| ----- | ------------------ |
| JDK   | 17 (혹은 프로젝트 설정 기준) |
| DB    | MySQL              |
| Cache | Redis              |
| Build | Gradle             |

---

# 🔐 5. .env 사용 가이드

### ✅ 1) `.env.example` 복사

프로젝트 루트에 존재하는 파일:

```
.env.example
```

이를 복사하여 `.env.local`, `.env.prod` 파일 생성

---

### ✅ 3) Spring Boot 에서 환경변수 적용 방식

`application.yml` 또는 `properties` 내부에서 이렇게 사용:

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
```

📌 **중요**

* `Driver com.mysql.cj.jdbc.Driver claims to not accept jdbcUrl, ${DB_URL}` 같은 에러는
  `.env.[]` 가 적용 안 됐거나 변수 값이 비어있을 때 발생함
* `.env.[]` 반드시 존재해야 함

---

# ▶️ 6. 프로젝트 실행 방법

### 1️⃣ Clone

```
git clone <repo-url>
```

### 2️⃣ 반드시 “루트 폴더 기준으로” 프로젝트 열기

IntelliJ 기준:

```
project-root 선택 → Open as Project
```

### 3️⃣ `.env` 파일 생성 & 값 채우기

### 4️⃣ 빌드

```
./gradlew clean build
```

### 5️⃣ 실행

```
./gradlew bootRun
```

또는 IDE Run

---

# 📘 7. Swagger API 문서

### 기본 접속 경로

```
http://localhost:8080/api/swagger-ui.html
```

> `server.servlet.context-path=/api` 설정이 적용된 경우 위 경로가 기본입니다.

---

### 3. 모듈별 실행
```bash
# Admin API (8080)
./gradlew :admin-module:bootRun

# Batch Service (8081)
./gradlew :batch-module:bootRun

# Notification Service (8082)
./gradlew :notification-module:bootRun
```

## API 테스트
```bash
# Hello World
curl http://localhost:8080/api/hello

# 헬스체크
curl http://localhost:8080/api/health/all

# Swagger UI
http://localhost:8080/api/swagger-ui.html
```


---

# 🧪 8. 헬스체크

서버 정상 여부 확인

```
http://localhost:8080/api/actuator/health
```

Expected:

```
status: UP
```

---

# 💬 9. Trouble Shooting

| 문제                       | 원인        | 해결                        |
| ------------------------ | --------- | ------------------------- |
| MySQL 연결 실패              | .env.[] 미작성  | .env.[] 채우기                  |
| Redis DOWN               | Redis 미실행 | Redis 실행                  |
| Swagger 404              | 경로 오류     | `/api/swagger-ui.html` 확인 |
| Driver claims jdbcUrl 오류 | 환경변수 미적용  | .env 존재 여부 확인             |

---

## 팀원

- 조장: 윤재영
- 조원: 권태환, 신우철, 박성준, 이윤경

현직자 멘토링때의 질문 (1/15)

1. [협업] 기능 구현할 때, 한 기능을 여러 사람이 구현하게 될 수도 있는데 실무에서는 어떻게 역할 분담 하는지?
2. [인프라 & 전체적인 과정] 생각하고 있는 전체 구조가 맞는지? 더 효율적인 구조는 없는지
    1. 실무에서는 각 모듈이나 서비스를 어떻게 분리/운영하는지?
    2. 이러한 구조로 설계함으로써 가지고 가는 리스크가 있는지?
3. [인프라] 규칙적이고 일시적인 작업 수행 시 실무에서도 서버리스 방식이 사용되는지
4. (카프카 컨슈머) 실무에서는 컨슈머 랙을 어떻게 관리하고 지금과 같은 제한된 환경(500만건)의 프로젝트 상황에서는 어떻게 관리하는게 좋을까요?
5. [알림 재시도] 알림 발송 실패시 FAILED → RETRY → SENT 같은 상태 관리 전략이 괜찮은지?
6. [알림 재시도] Retry 로직을 지금 처럼 처리하는 게 좋은지? 아니면 DLQ나 Retry Topic을 사용하는지? 
7. [알림 발송 실패] FAILED 됐을 때 실무에서 처리하는 방식? 선호하는 방식?
8. [보안] 고객의 이름과 이메일을 AES-256-GCM 암호화 방식을 사용하고 있는데 이게 현업에서도 사용하는 방식인지? 다른 권장하는 방식은 없는지? 
9. [보안] 현재는 .env를 사용해서 환경변수 기반으로 관리하고 있는데 실무에서는 어떤 식으로 적용을 하는지?
10. [보안] 유저의 정보를 암호화한 값과 해시값(중복성 체크)으로 분리해두어서 사용했는데, 현업에서도 이 방식을 사용하는지?
11. [보안] 기능 테스트 외에 보안 관점에서 암호화나 개인정보 처리가 정상적으로 지켜지는지 어떤 방식으로 검증하는지?
12. [DB] 현재 프로젝트는 결제 관련해서 월 500만건 정도의 데이터가 적재되는걸 가정해서 진행되고 있는데 실제 통신사에선 이런 데이터가 장기간 누적될 때 어떤 기준으로 관리 전력을 가지는지
13. Kafka 파티션 개수와 Consumer 수를 보통 어떤 기준으로 결정하시나요?
    
<img width="917" height="854" alt="스크린샷 2026-01-14 174456" src="https://github.com/user-attachments/assets/689b609a-4cbe-455a-978c-1522c554ea15" />
