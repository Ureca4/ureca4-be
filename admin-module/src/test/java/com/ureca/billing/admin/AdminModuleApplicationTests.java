package com.ureca.billing.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.batch.job.enabled=false"
})
class AdminModuleApplicationTests {

	@Test
	void contextLoads() {
	}

}
