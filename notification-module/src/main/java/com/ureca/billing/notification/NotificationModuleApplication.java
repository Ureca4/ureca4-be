package com.ureca.billing.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;


@EnableKafka
@EnableScheduling 
@SpringBootApplication(scanBasePackages = {
    "com.ureca.billing.core",
    "com.ureca.billing.notification"
})
public class NotificationModuleApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationModuleApplication.class, args);
	}

}
