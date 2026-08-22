package com.openscholar.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.openscholar.api.ApiExceptionHandler;
import com.openscholar.jobs.ResearchRefreshJobNotFoundException;
import com.openscholar.jobs.ResearchRefreshJobNotRetryableException;
import com.openscholar.jobs.ResearchRefreshJobPage;
import com.openscholar.jobs.ResearchRefreshJobStatus;
import com.openscholar.jobs.ResearchRefreshJobTrigger;
import com.openscholar.jobs.ResearchRefreshJobType;
import com.openscholar.jobs.ResearchRefreshJobUseCase;
import com.openscholar.jobs.ResearchRefreshJobView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@ContextConfiguration(classes = {
	ResearchRefreshJobController.class,
	ApiExceptionHandler.class,
	ResearchRefreshJobControllerTests.FakeJobsConfiguration.class
})
@WebMvcTest(ResearchRefreshJobController.class)
class ResearchRefreshJobControllerTests {

	private static final UUID JOB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FakeJobs jobs;

	@BeforeEach
	void reset() {
		jobs.missing = false;
		jobs.notRetryable = false;
	}

	@Test
	void enqueuesAValidatedDurableJob() throws Exception {
		mockMvc.perform(post("/api/v1/refresh-jobs")
					.contentType("application/json")
					.content("""
							{"jobType":"SEARCH_METADATA","targetId":"22222222-2222-2222-2222-222222222222"}
							"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").value(JOB_ID.toString()))
				.andExpect(jsonPath("$.jobType").value("SEARCH_METADATA"))
				.andExpect(jsonPath("$.targetId").value(TARGET_ID.toString()))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.attemptCount").value(0))
				.andExpect(jsonPath("$.maxAttempts").value(3));

		assertThat(jobs.lastType).isEqualTo(ResearchRefreshJobType.SEARCH_METADATA);
		assertThat(jobs.lastTargetId).isEqualTo(TARGET_ID);
	}

	@Test
	void listsJobsWithBoundedPagination() throws Exception {
		mockMvc.perform(get("/api/v1/refresh-jobs").queryParam("page", "2").queryParam("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(JOB_ID.toString()))
				.andExpect(jsonPath("$.page").value(2))
				.andExpect(jsonPath("$.size").value(10))
				.andExpect(jsonPath("$.totalElements").value(21))
				.andExpect(jsonPath("$.totalPages").value(3));

		assertThat(jobs.lastPage).isEqualTo(2);
		assertThat(jobs.lastSize).isEqualTo(10);
	}

	@Test
	void rejectsInvalidRequestsAndPagination() throws Exception {
		mockMvc.perform(post("/api/v1/refresh-jobs")
					.contentType("application/json")
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc.perform(get("/api/v1/refresh-jobs").queryParam("size", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void returnsStableNotFoundAndRetryConflictProblems() throws Exception {
		jobs.missing = true;
		mockMvc.perform(get("/api/v1/refresh-jobs/{jobId}", JOB_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REFRESH_JOB_NOT_FOUND"));

		jobs.missing = false;
		jobs.notRetryable = true;
		mockMvc.perform(post("/api/v1/refresh-jobs/{jobId}/retry", JOB_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REFRESH_JOB_NOT_RETRYABLE"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeJobsConfiguration {

		@Bean
		FakeJobs jobs() {
			return new FakeJobs();
		}
	}

	static final class FakeJobs implements ResearchRefreshJobUseCase {

		private ResearchRefreshJobType lastType;
		private UUID lastTargetId;
		private int lastPage;
		private int lastSize;
		private boolean missing;
		private boolean notRetryable;

		@Override
		public ResearchRefreshJobView enqueue(ResearchRefreshJobType jobType, UUID targetId) {
			lastType = jobType;
			lastTargetId = targetId;
			return view();
		}

		@Override
		public ResearchRefreshJobView get(UUID jobId) {
			if (missing) {
				throw new ResearchRefreshJobNotFoundException(jobId);
			}
			return view();
		}

		@Override
		public ResearchRefreshJobPage list(int page, int size) {
			lastPage = page;
			lastSize = size;
			return new ResearchRefreshJobPage(List.of(view()), page, size, 21, 3);
		}

		@Override
		public ResearchRefreshJobView retry(UUID jobId) {
			if (notRetryable) {
				throw new ResearchRefreshJobNotRetryableException(jobId);
			}
			return view();
		}

		private ResearchRefreshJobView view() {
			return new ResearchRefreshJobView(
					JOB_ID,
					ResearchRefreshJobType.SEARCH_METADATA,
					TARGET_ID,
					ResearchRefreshJobTrigger.MANUAL,
					ResearchRefreshJobStatus.QUEUED,
					0,
					3,
					NOW,
					null,
					null,
					null,
					NOW,
					null,
					null,
					NOW);
		}
	}
}
