package com.openscholar.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import com.openscholar.privacy.PrivacyExportBusyException;
import com.openscholar.privacy.PrivacyExportTooLargeException;
import com.openscholar.search.SearchCoordinationInterruptedException;
import com.openscholar.search.SearchCoordinationTimeoutException;
import com.openscholar.search.SearchDeadlineExceededException;
import com.openscholar.search.SearchExecutionInterruptedException;
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

	@Test
	void mapsExecutionDeadlineToRetryableGatewayTimeoutWithoutRetryAfter() throws Exception {
		mockMvc.perform(get("/test/execution-deadline"))
				.andExpect(status().isGatewayTimeout())
				.andExpect(header().doesNotExist("Retry-After"))
				.andExpect(jsonPath("$.code").value("SEARCH_DEADLINE_EXCEEDED"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.detail").isNotEmpty())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString(NESTED_SECRET))));
	}

	@Test
	void mapsExecutionInterruptionToRetryableServiceUnavailableWithoutCauseDetails() throws Exception {
		mockMvc.perform(get("/test/execution-interrupted"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().doesNotExist("Retry-After"))
				.andExpect(jsonPath("$.code").value("SEARCH_EXECUTION_INTERRUPTED"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.detail").isNotEmpty())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString(NESTED_SECRET))));
	}

	@Test
	void mapsOversizedPrivacyExportToNonRetryableUnprocessableEntityWithoutCaching() throws Exception {
		mockMvc.perform(get("/test/privacy-export-too-large"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.code").value("PRIVACY_EXPORT_TOO_LARGE"))
				.andExpect(jsonPath("$.title").value("Personal-data export too large"))
				.andExpect(jsonPath("$.retryable").doesNotExist())
				.andExpect(jsonPath("$.detail")
						.value("The personal-data export exceeds the supported record or byte limit"));
	}

	@Test
	void mapsBusyPrivacyExportToGenericRetryableRateLimitWithoutCaching() throws Exception {
		mockMvc.perform(get("/test/privacy-export-busy"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("Retry-After", "2"))
				.andExpect(jsonPath("$.code").value("PRIVACY_EXPORT_BUSY"))
				.andExpect(jsonPath("$.title").value("Personal-data export busy"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.retryAfterSeconds").value(2))
				.andExpect(jsonPath("$.detail")
						.value("Personal-data export capacity is temporarily full. Try again later."))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("00000000-0000-0000-0000-000000000001"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("globalPermits"))));
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

		@GetMapping("/test/execution-deadline")
		void deadlineExceeded() {
			SearchDeadlineExceededException exception = new SearchDeadlineExceededException();
			exception.initCause(new IllegalStateException(NESTED_SECRET));
			throw exception;
		}

		@GetMapping("/test/execution-interrupted")
		void executionInterrupted() {
			throw new SearchExecutionInterruptedException(new InterruptedException(NESTED_SECRET));
		}

		@GetMapping("/test/privacy-export-too-large")
		void privacyExportTooLarge() {
			throw new PrivacyExportTooLargeException();
		}

		@GetMapping("/test/privacy-export-busy")
		void privacyExportBusy() {
			throw new PrivacyExportBusyException(Duration.ofSeconds(1).plusNanos(1));
		}
	}
}
