# 🐳 Ureca Team 4 - Docker 배포 및 테스트 가이드

이 문서는 로컬 개발 환경에서 Docker Compose를 사용하여 전체 인프라와 애플리케이션을 실행하고, **더미 데이터 생성** 및 **배치(Batch) 테스트**를 진행하는 방법을 안내합니다.

## 1. 사전 준비 (Prerequisites)
* **Docker Desktop** 설치 및 실행 중일 것
* 프로젝트 루트에 `.env.docker` 파일 확인
    * *없을 경우 팀 공유 드라이브나 슬랙에서 다운로드 필수*

## 2. 파일 구조 설명
| 파일명 | 설명 |
| :--- | :--- |
| `docker-compose.infra.yml` | **인프라**: MySQL, Redis, Kafka, Zookeeper, Kafka UI |
| `docker-compose.app.yml` | **앱**: Admin, Notification, Batch (애플리케이션 컨테이너) |
| `.env.docker` | DB 계정, 포트, Kafka 주소 등 환경변수 설정 파일 |

---

## 3. 전체 시스템 실행 및 종료

### ✅ 전체 시스템 실행 (Infrastructure + Apps)
DB, Kafka 등 인프라와 상시 실행되어야 하는 서버들을 백그라운드에서 실행합니다.
```bash
# 실행 (백그라운드)
docker compose -f docker-compose.infra.yml -f docker-compose.app.yml --env-file .env.docker up -d

# (코드 수정 후) 이미지를 새로 빌드하며 실행
docker compose -f docker-compose.infra.yml -f docker-compose.app.yml --env-file .env.docker up -d --build
```

### 🛑 전체 종료 (Shutdown)
컨테이너를 정지하고 네트워크를 제거합니다.
```bash
docker compose -f docker-compose.infra.yml -f docker-compose.app.yml down
```

---

## 4. 🛠 더미 데이터 생성 (Dummy Data Jobs)

초기 개발을 위한 사용자 및 청구 데이터를 생성합니다.
(배치 모듈(`batch`)을 일회성으로 실행하여 데이터를 적재합니다.)

### 1) User 및 기초 데이터 생성 (가장 먼저 실행)
```powershell
docker compose -f docker-compose.infra.yml -f docker-compose.app.yml run --rm --build core-job java -jar /app/app.jar --spring.batch.job.name=userDummyDataJob
```

### 2) 월별 청구 더미 데이터 생성
* **필수 파라미터:** `targetYearMonth=yyyy-MM`
```powershell
docker compose -f docker-compose.infra.yml -f docker-compose.app.yml run --rm --build core-job java -jar /app/app.jar --spring.batch.job.name=monthlyDummyDataJob targetYearMonth=2025-08
```

---

## 5. 🏃‍♂️ 월별 정산 배치 실행 (Billing Job)

실제 정산 로직을 수행하는 배치 작업입니다.

### 1) 월별 정산 Job 실행 (`monthlyBillingJob`)
* **필수 파라미터:** `billingMonth=yyyy-MM`
* **재실행 팁:** 이미 완료된 작업(Completed)은 다시 실행되지 않으므로, 테스트 시에는 `version` 파라미터를 변경하며 실행하세요.

**배치 서버 실행**
```powershell
 docker compose -f docker-compose.infra.yml -f docker-compose.app.yml --env-file .env.docker --profile job up -d --build batch
```

**API 호출**
* **필수 파라미터:** `?billingMonth=yyyy-MM`
```bash
  curl -X POST "http://localhost:8083/api/batch/monthly-billing?billingMonth=2025-08"
```

---

## 6. 🌐 접속 정보 (Port Mapping)

로컬 브라우저나 DB 툴에서 접속할 때는 아래의 **Local Port**를 사용하세요.

| 서비스 | Docker 내부 Port | **Local (내컴퓨터) Port** | 접속 정보 / URL |
| :--- | :---: | :---: | :--- |
| **MySQL** | 3306 | **3307** | `jdbc:mysql://localhost:3307/urecaTeam4_db` |
| **Redis** | 6379 | **6379** | localhost:6379 |
| **Kafka** | 9092 | **29092** | localhost:29092 (외부 접속용) |
| **Kafka UI** | 8080 | **28080** | [http://localhost:28080](http://localhost:28080) |
| **Admin** | 8080 | **8081** | [http://localhost:8081](http://localhost:8081) |
| **Notification** | 8080 | **8082** | [http://localhost:8082](http://localhost:8082) |
| **Batch** | 8080 | **8083** | [http://localhost:8083](http://localhost:8083) |

---

## 7. 🚨 트러블슈팅 (FAQ)

**Q. 로그를 실시간으로 보고 싶어요.**
```bash
# 전체 로그 (정신없음 주의)
docker compose -f docker-compose.infra.yml -f docker-compose.app.yml logs -f

# 특정 서비스 로그만 확인 (예: batch 서비스)
docker logs -f lgubill-batch
```

**Q. DB를 완전히 초기화하고 싶어요. (데이터 삭제)**
테스트 데이터가 꼬여서 초기화가 필요할 때 사용합니다. **주의: 모든 데이터가 삭제됩니다.**

1. 모든 컨테이너 종료
   ```bash
   docker compose -f docker-compose.infra.yml -f docker-compose.app.yml down
   ```
2. **볼륨 이름 확인 및 삭제** (프로젝트 폴더명에 따라 이름이 다를 수 있음)
   ```bash
   # 1. 볼륨 목록 확인 (mysql_data가 포함된 이름 찾기)
   docker volume ls 
   
   # 2. 확인된 이름으로 삭제 (예: ureca4-be_mysql_data)
   docker volume rm ureca4-be_mysql_data
   ```
3. 다시 실행 (초기화됨)
   ```bash
   docker compose -f docker-compose.infra.yml -f docker-compose.app.yml up -d
   ```

**Q. Kafka 메시지가 제대로 들어갔는지 확인하고 싶어요.**
1. [http://localhost:28080](http://localhost:28080) (Kafka UI) 접속
2. 좌측 메뉴 **Topics** 클릭
3. 확인하려는 토픽(예: `billing-topic`) 클릭
4. **Messages** 탭 클릭 -> 우측 `Execute` 버튼 클릭하여 최신 메시지 조회

