package com.ureca.billing.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

// 테스트 환경 설정 주입
@SpringBootTest(properties = {
        "crypto.aes.key=c29tZS1yYW5kb20tc2VjcmV0LWtleS0xMjM0NTY3ODk=",
        "spring.batch.job.enabled=false"
})
public class ManualKafkaTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // 🟢 테스트 1 & 2: 정상 발송 및 중복 방지 테스트
    @Test
    void sendSingleMessage() {
        int billId = 5001; // 테스트할 때마다 이 숫자를 바꿔보세요 (예: 5002, 5003...)

        String jsonMessage = String.format("""
            {
                "billId": %d,
                "userId": 88,
                "billYearMonth": "202501",
                "recipientEmail": "user%d@ureca.com",
                "recipientPhone": "010-1234-5678",
                "totalAmount": 55000,
                "billDate": "2025-01-15",
                "dueDate": "2025-01-25",
                "planName": "5G Basic"
            }
        """, billId, billId);

        kafkaTemplate.send("billing-topic", jsonMessage);
        System.out.println(">>> 🚀 [단건] 메시지 전송 완료! billId=" + billId);
    }

    // 🔴 테스트 3: 재시도 & DLQ 테스트 (100개 보내서 1% 에러 터뜨리기)
    @Test
    void sendManyMessagesForError() throws InterruptedException {
        System.out.println(">>> 💣 [대량] 100개 메시지 전송 시작 (1% 에러 유도)...");

        for (int i = 0; i < 100; i++) {
            int billId = 6000 + i; // 6000 ~ 6099

            String jsonMessage = String.format("""
                {
                    "billId": %d,
                    "userId": %d,
                    "billYearMonth": "202501",
                    "recipientEmail": "error_test_%d@ureca.com",
                    "recipientPhone": "010-0000-0000",
                    "totalAmount": 10000,
                    "billDate": "2025-01-15",
                    "dueDate": "2025-01-25",
                    "planName": "Test Plan"
                }
            """, billId, i, i);

            kafkaTemplate.send("billing-topic", jsonMessage);
            Thread.sleep(50); // 너무 빨리 보내면 로그 보기가 힘들어서 약간 텀을 둠
        }

        System.out.println(">>> ✅ 전송 끝! 서버 로그에서 '장애 주입'과 'DLQ'를 찾아보세요.");
    }
}