package com.openscholar.api.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@Import(SystemStatusControllerTests.FixedClockConfiguration.class)
@WebMvcTest(SystemStatusController.class)
class SystemStatusControllerTests {

	private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Test
	void reportsServiceStatusAsJson() throws Exception {
		mockMvc.perform(get("/api/v1/system/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.service").value("openscholar-backend"))
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.timestamp").value("2026-08-16T12:00:00Z"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
