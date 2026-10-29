package vn.brightpath.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchedulingApplicationTests {

	@Test
	void contextLoads() {
	}

}
