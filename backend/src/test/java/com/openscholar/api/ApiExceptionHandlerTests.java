package com.openscholar.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openscholar.search.SearchCoordinationInterruptedException;
import com.openscholar.search.SearchCoordinationTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTests {

	private static final String NESTED_SECRET = "private coordination detail";

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new CoordinationFailureController())
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	void mapsCoordinationTimeoutToRetryableServiceUnavailableWithoutRetryAfter() throws Exception {
		mockMvc.perform(get("/test/coordination-timeout"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().doesNotExist("Retry-After"))
				.andExpect(jsonPath("$.code").value("SEARCH_COORDINATION_TIMEOUT"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.detail")
						.value("Search coordination did not become available within the configured wait limit."));
	}

	@Test
	void mapsCoordinationInterruptionToRetryableServiceUnavailableWithoutCauseDetails() throws Exception {
		mockMvc.perform(get("/test/coordination-interrupted"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().doesNotExist("Retry-After"))
				.andExpect(jsonPath("$.code").value("SEARCH_COORDINATION_INTERRUPTED"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.detail")
						.value("Search coordination was interrupted before it became available."))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString(NESTED_SECRET))));
	}

	@RestController
	private static final class CoordinationFailureController {

		@GetMapping("/test/coordination-timeout")
		void timeout() {
			throw new SearchCoordinationTimeoutException();
		}

		@GetMapping("/test/coordination-interrupted")
		void interrupted() {
			throw new SearchCoordinationInterruptedException(new InterruptedException(NESTED_SECRET));
		}
	}
}
