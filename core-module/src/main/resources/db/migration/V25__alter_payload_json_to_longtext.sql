-- payload 컬럼의 속박(JSON 형식 강제)을 풀어버리는 명령어입니다.
ALTER TABLE OUTBOX_EVENTS MODIFY payload LONGTEXT;