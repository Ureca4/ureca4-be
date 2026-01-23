package com.ureca.billing.notification.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Notification Handler Factory
 * - Strategy 패턴으로 EMAIL/SMS 핸들러 선택
 * - Spring이 자동으로 Map에 모든 NotificationHandler 빈을 주입
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationHandlerFactory {
    
    // Spring이 자동으로 모든 NotificationHandler 구현체를 Map으로 주입
    // Key: 빈 이름 (예: "emailNotificationHandler", "smsNotificationHandler")
    private final Map<String, NotificationHandler> handlers;
    
    /**
     * 알림 타입에 맞는 핸들러 반환
     * 
     * @param type 알림 타입 ("EMAIL" 또는 "SMS")
     * @return 해당 타입의 핸들러
     */
    public NotificationHandler getHandler(String type) {
        // 타입을 소문자로 변환하고 "NotificationHandler" 접미사 추가
        String beanName = type.toLowerCase() + "NotificationHandler";
        
        NotificationHandler handler = handlers.get(beanName);
        
        if (handler == null) {
            log.error("❌ 핸들러를 찾을 수 없습니다: type={}, beanName={}", type, beanName);
            log.error("사용 가능한 핸들러: {}", handlers.keySet());
            throw new IllegalArgumentException("Unknown notification type: " + type);
        }
        
        //log.debug("✅ 핸들러 선택: type={}, handler={}", type, handler.getClass().getSimpleName());
        return handler;
    }
    
    /**
     * 등록된 모든 핸들러 확인 (디버깅용)
     */
    public void printAvailableHandlers() {
        log.info("=== 등록된 알림 핸들러 목록 ===");
        handlers.forEach((name, handler) -> 
            log.info("  - {}: {}", name, handler.getClass().getSimpleName())
        );
    }
}