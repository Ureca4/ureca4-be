# 🗄️ BillForU+ - Flyway 마이그레이션 가이드

> LG U+ 청구 시스템 데이터베이스 스키마 버전 관리
> #### Flyway를 활용한 안정적인 DB 마이그레이션 (V1 ~ V25)

---

## ⛳️ Flyway 도입 배경

### 왜 Flyway인가?

1. **DB 스키마 버전 관리**
   - 팀원 간 DB 스키마 불일치 문제 해결
   - 운영/개발 환경 간 동일한 스키마 유지

2. **자동화된 마이그레이션**
   - 애플리케이션 실행 시 자동으로 마이그레이션 적용
   - 수동 SQL 실행으로 인한 휴먼 에러 방지

3. **롤백 및 히스토리 추적**
   - 어떤 마이그레이션이 언제 적용되었는지 추적 가능
   - 문제 발생 시 원인 파악 용이

---

## 🏗️ ERD (Entity Relationship Diagram)

<img width="1481" alt="image" src="https://github.com/user-attachments/assets/9a011bdf-1dd2-47d5-82f4-08d0bb32094b" />

---

## 📋 마이그레이션 버전 구조 (V1 ~ V25)

```
src/main/resources/db/migration/
├── V1__create_user_and_product_tables.sql
├── V2__create_user_subscription_tables.sql
├── V3__create_billing_tables.sql
├── V4__add_billing_dates_to_bills.sql
├── V5__add_charge_category_to_bill_details.sql
├── V6__create_bill_arrears_table.sql
├── V7__create_device_installments_table.sql
├── V8__create_user_relations_table.sql
├── V9__create_notifications_table.sql
├── V10__create_batch_execution_tables.sql
├── V11__create_message_policy_table.sql
├── V12__insert_message_policy_data.sql
├── V13__alter_plan_and_drop_batch_execution_tables.sql
├── V14__insert_into_plans.sql
├── V15__split_email_phone_cipher_and_hash.sql
├── V16__add_total_amount_to_bills.sql
├── V17__add_billing_month_to_bills.sql
├── V18__add_amount_to_bill_details.sql
├── V19__create_outbox_events.sql
├── V20__alter_notifications_add_bill_id.sql
├── V21__create_user_notification_prefs.sql
├── V22__add_push_to_outbox_notification_type.sql
├── V23__alter_notifications_add_push.sql
├── V24__add_preferred_schedule_to_user_prefs.sql
└── V25__alter_payload_json_to_longtext.sql
```

---

## 🎯 마이그레이션 전략

### 1단계: 기본 도메인 (V1-V2)

| 버전 | 테이블 | 설명 |
|------|--------|------|
| V1 | `USERS` | 사용자 기본 정보 (이메일, 휴대폰) |
| V1 | `PLANS` | 요금제 마스터 (5G/LTE) |
| V1 | `ADDONS` | 부가서비스 마스터 |
| V2 | `USER_PLANS` | 사용자별 요금제 가입 |
| V2 | `USER_ADDONS` | 사용자별 부가서비스 가입 |
| V2 | `MICRO_PAYMENTS` | 소액결제 내역 |

### 2단계: 청구 시스템 (V3-V5)

| 버전 | 작업 | 설명 |
|------|------|------|
| V3 | CREATE | `BILLS`, `BILL_DETAILS` 테이블 생성 |
| V4 | ALTER | 정산일/청구일 컬럼 추가 |
| V5 | ALTER | charge_category, related_user_id 추가 (정산 원장화) |

### 3단계: 부가 기능 (V6-V8)

| 버전 | 테이블 | 설명 |
|------|--------|------|
| V6 | `BILL_ARREARS` | 체납 관리 |
| V7 | `DEVICE_INSTALLMENTS` | 단말 할부 |
| V8 | `USER_RELATIONS` | 가족 관계 (본인/자녀/워치) |

### 4단계: 알림 시스템 (V9-V12)

| 버전 | 작업 | 설명 |
|------|------|------|
| V9 | CREATE | `NOTIFICATIONS` 테이블 생성 |
| V10 | CREATE | `BATCH_EXECUTIONS`, `BATCH_EXECUTION_HISTORY` 생성 |
| V11 | CREATE | `MESSAGE_POLICY` 테이블 (발송 금지 시간대) |
| V12 | INSERT | 메시지 정책 기본 데이터 삽입 |

### 5단계: 스키마 정리 및 데이터 (V13-V14)

| 버전 | 작업 | 설명 |
|------|------|------|
| V13 | ALTER/DROP | plans 컬럼 수정, 배치 테이블 삭제 |
| V14 | INSERT | LG U+ 실제 요금제 13종 데이터 삽입 |

### 6단계: 보안 강화 (V15)

| 버전 | 작업 | 설명 |
|------|------|------|
| V15 | ALTER | 이메일/휴대폰 → cipher + hash 분리 (AES-256-GCM) |

### 7단계: 청구 시스템 확장 (V16-V18)

| 버전 | 작업 | 설명 |
|------|------|------|
| V16 | ALTER | BILLS에 total_amount 컬럼 추가 |
| V17 | ALTER | BILLS에 billing_month 컬럼 추가 (YYYY-MM) |
| V18 | ALTER | BILL_DETAILS에 amount 컬럼 추가 |

### 8단계: Outbox 패턴 도입 (V19-V20)

| 버전 | 작업 | 설명 |
|------|------|------|
| V19 | CREATE | `OUTBOX_EVENTS` 테이블 (Transactional Outbox 패턴) |
| V20 | ALTER | NOTIFICATIONS에 bill_id, UK 추가 (멱등성 보장) |

### 9단계: 사용자 알림 설정 (V21, V24)

| 버전 | 작업 | 설명 |
|------|------|------|
| V21 | CREATE | `USER_NOTIFICATION_PREFS` (채널별 설정, 금지시간) |
| V24 | ALTER | 선호 발송 시간 컬럼 추가 (일/시/분) |

### 10단계: 멀티채널 확장 (V22-V23)

| 버전 | 작업 | 설명 |
|------|------|------|
| V22 | ALTER | OUTBOX_EVENTS에 PUSH 타입 추가 |
| V23 | ALTER | NOTIFICATIONS에 PUSH 타입 추가 |

### 11단계: 성능 개선 (V25)

| 버전 | 작업 | 설명 |
|------|------|------|
| V25 | ALTER | OUTBOX_EVENTS.payload JSON → LONGTEXT 변경 |

---


## 🚀 사용 방법

### 1. Gradle 의존성 추가

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-mysql'
    runtimeOnly 'com.mysql:mysql-connector-j'
}
```

### 2. application.yml 설정

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/lg_uplus_billing
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
    
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
    
  jpa:
    hibernate:
      ddl-auto: validate
```

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

---

## ✅ 마이그레이션 확인

### 성공 로그 예시

```
Flyway Community Edition 9.x.x
Database: jdbc:mysql://localhost:3306/lg_uplus_billing
Successfully validated 25 migrations
Current version of schema `lg_uplus_billing`: 25
Schema `lg_uplus_billing` is up to date. No migration necessary.
```

### 마이그레이션 히스토리 확인

```sql
SELECT 
    installed_rank,
    version,
    description,
    installed_on,
    execution_time,
    success
FROM flyway_schema_history 
ORDER BY installed_rank;
```

---

## 🔄 롤백 전략

> ⚠️ Flyway Community Edition은 자동 롤백을 지원하지 않습니다.

### 롤백 방법

| 방법 | 설명 | 권장도 |
|------|------|:------:|
| **수동 롤백** | 각 버전에 대응하는 UNDO 스크립트 작성 | ⭐⭐⭐ |
| **백업 복구** | 마이그레이션 전 DB 백업 후 복구 | ⭐⭐ |
| **Flyway Teams** | 자동 롤백 기능 사용 (유료) | ⭐ |

---

## 📝 버전 관리 규칙

### 파일 네이밍 규칙

```
V{버전번호}__{설명}.sql

예시:
V1__create_user_tables.sql
V26__add_new_column.sql
```

### 필수 규칙

| 규칙 | 설명 |
|------|------|
| ✅ 순차적 버전 | V1, V2, V3... 순서대로 |
| ✅ 언더스코어 2개 | V1`__`description |
| ❌ 수정 금지 | 한 번 적용된 파일 절대 수정 금지 |
| ✅ 새 버전 추가 | 변경 필요시 새로운 버전으로 |

---

## 🔧 트러블슈팅

### 문제 1: "Table already exists" 오류

**해결**:
```yaml
spring:
  flyway:
    baseline-on-migrate: true
```

### 문제 2: 체크섬 불일치

**해결**: 마이그레이션 파일 수정 금지, 새 버전으로 변경 적용

### 문제 3: FK 제약조건 오류

**해결**: 마이그레이션 순서 확인 (참조 테이블 먼저 생성)

---

## 🔗 관련 문서

- [메인 README](./README.md)
- [Flyway 공식 문서](https://flywaydb.org/documentation/)
