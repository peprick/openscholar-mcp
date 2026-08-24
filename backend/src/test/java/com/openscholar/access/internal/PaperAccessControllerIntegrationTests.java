package com.openscholar.access.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import com.openscholar.TestcontainersConfiguration;
import com.openscholar.TestCurrentUserConfiguration;
import com.openscholar.access.AccessHostType;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessVerificationStatus;
import com.openscholar.access.AccessVersionType;
import com.openscholar.access.internal.provider.AccessCandidate;
import com.openscholar.access.internal.provider.AccessEvidenceLookup;
import com.openscholar.access.internal.provider.AccessEvidenceProvider;
import com.openscholar.access.internal.provider.AccessEvidenceResult;
import com.openscholar.access.internal.provider.AccessProviderException;
import com.openscholar.access.internal.provider.AccessResolutionStatus;
import com.openscholar.access.internal.provider.AccessSource;
import com.openscholar.access.internal.persistence.PaperAccessStore;
import com.openscholar.api.ApiExceptionHandler;
import com.openscholar.api.access.PaperAccessController;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc(addFilters = false)
@Import({
	TestcontainersConfiguration.class,
	TestCurrentUserConfiguration.class,
	PaperAccessControllerIntegrationTests.FakeAccessConfiguration.class
})
@SpringBootTest(
		classes = PaperAccessControllerIntegrationTests.AccessTestApplication.class,
		properties = "openscholar.access.cache-ttl=1h")
class PaperAccessControllerIntegrationTests {

	private static final Instant INITIAL_TIME = Instant.parse("2026-08-17T08:00:00Z");
	private static final String SOURCE_KEY = "upstream-private-location-key";
	private static final URI LANDING_PAGE = URI.create("https://repository.example.org/records/open-paper");
	private static final URI PDF = URI.create("https://repository.example.org/records/open-paper.pdf");
	private static final AtomicInteger ARXIV_SEQUENCE = new AtomicInteger(30000);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FakeAccessFixture providers;

	@Autowired
	private FakeAccessCandidateVerifier verifier;

	@Autowired
	private MutableClock clock;

	private final List<UUID> createdPaperIds = new ArrayList<>();

	@BeforeEach
	void resetFakes() {
		clock.set(INITIAL_TIME);
		providers.reset();
		verifier.reset();
	}

	@AfterEach
	void deleteCreatedPapers() {
		createdPaperIds.forEach(paperId -> jdbcTemplate.update("delete from paper where id = ?", paperId));
		createdPaperIds.clear();
	}

	@Test
	void missingPaperReturnsSafeNotFoundWithoutCallingProviders() throws Exception {
		UUID missingPaperId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/papers/{paperId}/versions", missingPaperId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("Paper not found: " + missingPaperId));

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", missingPaperId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));

		assertThat(providers.totalCalls()).isZero();
		assertThat(verifier.calls()).isZero();
	}

	@Test
	void getForUnresolvedPaperDoesNotTriggerExternalResolution() throws Exception {
		UUID paperId = createPaper(true, false, true);

		mockMvc.perform(get("/api/v1/papers/{paperId}/versions", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paperId").value(paperId.toString()))
				.andExpect(jsonPath("$.status").value("ABSTRACT_ONLY"))
				.andExpect(jsonPath("$.cacheDisposition").value("NOT_YET_RESOLVED"))
				.andExpect(jsonPath("$.checkedAt").value(nullValue()))
				.andExpect(jsonPath("$.freshUntil").value(nullValue()))
				.andExpect(jsonPath("$.bestLocationId").value(nullValue()))
				.andExpect(jsonPath("$.providerCoverage").isEmpty())
				.andExpect(jsonPath("$.warnings").isEmpty())
				.andExpect(jsonPath("$.locations").isEmpty());

		assertThat(providers.totalCalls()).isZero();
		assertThat(verifier.calls()).isZero();
		assertThat(resolutionCount(paperId)).isZero();
		assertThat(versionCount(paperId)).isZero();
	}

	@Test
	void firstDoiResolutionPersistsVerifiedOpenPdfAndGetReusesIt() throws Exception {
		UUID paperId = createPaper(true, false, true);
		providers.unpaywall().resolveWith(openPdfCandidate(AccessSource.UNPAYWALL, SOURCE_KEY));

		MvcResult resolved = mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paperId").value(paperId.toString()))
				.andExpect(jsonPath("$.status").value("OPEN_PDF"))
				.andExpect(jsonPath("$.cacheDisposition").value("RESOLVED"))
				.andExpect(jsonPath("$.checkedAt").value(INITIAL_TIME.toString()))
				.andExpect(jsonPath("$.providerCoverage[?(@.provider == 'UNPAYWALL')].status")
						.value(hasItem("RESOLVED")))
				.andExpect(jsonPath("$.locations.length()").value(1))
				.andExpect(jsonPath("$.locations[0].source").value("UNPAYWALL"))
				.andExpect(jsonPath("$.locations[0].accessStatus").value("OPEN_PDF"))
				.andExpect(jsonPath("$.locations[0].versionType").value("PUBLISHED"))
				.andExpect(jsonPath("$.locations[0].hostType").value("PUBLISHER"))
				.andExpect(jsonPath("$.locations[0].landingPageUrl").value(LANDING_PAGE.toString()))
				.andExpect(jsonPath("$.locations[0].pdfUrl").value(PDF.toString()))
				.andExpect(jsonPath("$.locations[0].hostDomain").value("repository.example.org"))
				.andExpect(jsonPath("$.locations[0].license").value("cc-by-4.0"))
				.andExpect(jsonPath("$.locations[0].contentHandling").value("LINK_ONLY"))
				.andExpect(jsonPath("$.locations[0].verificationStatus").value("VERIFIED"))
				.andExpect(jsonPath("$.locations[0].verificationHttpStatus").value(200))
				.andExpect(jsonPath("$.locations[0].verificationContentType").value("application/pdf"))
				.andReturn();
		String responseBody = resolved.getResponse().getContentAsString();
		String locationId = JsonPath.read(responseBody, "$.bestLocationId");

		assertThat(responseBody)
				.doesNotContain(SOURCE_KEY)
				.doesNotContain("sourceLocationKey")
				.doesNotContain("retentionAllowed");
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(versionCount(paperId)).isEqualTo(1);
		assertThat(activeVersionCount(paperId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"select char_length(source_location_key) from paper_version where paper_id = ?",
				Integer.class,
				paperId)).isEqualTo(64);
		assertThat(jdbcTemplate.queryForObject(
				"select retention_allowed from paper_version where paper_id = ?",
				Boolean.class,
				paperId)).isFalse();
		assertThat(jdbcTemplate.queryForObject(
				"select jsonb_array_length(provider_coverage) from paper_access_resolution where paper_id = ?",
				Integer.class,
				paperId)).isEqualTo(2);

		int providerCalls = providers.totalCalls();
		int verifierCalls = verifier.calls();
		mockMvc.perform(get("/api/v1/papers/{paperId}/versions", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("CACHE_HIT"))
				.andExpect(jsonPath("$.bestLocationId").value(locationId))
				.andExpect(jsonPath("$.locations[0].id").value(locationId));
		assertThat(providers.totalCalls()).isEqualTo(providerCalls);
		assertThat(verifier.calls()).isEqualTo(verifierCalls);
	}

	@Test
	void repositoryEvidencePersistsARepositoryCopyClassification() throws Exception {
		UUID paperId = createPaper(true, false, true);
		providers.unpaywall().resolveWith(repositoryPdfCandidate(SOURCE_KEY));

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REPOSITORY_COPY"))
				.andExpect(jsonPath("$.cacheDisposition").value("RESOLVED"))
				.andExpect(jsonPath("$.bestLocationId").isNotEmpty())
				.andExpect(jsonPath("$.locations.length()").value(1))
				.andExpect(jsonPath("$.locations[0].accessStatus").value("REPOSITORY_COPY"))
				.andExpect(jsonPath("$.locations[0].hostType").value("REPOSITORY"))
				.andExpect(jsonPath("$.locations[0].versionType").value("ACCEPTED_MANUSCRIPT"))
				.andExpect(jsonPath("$.locations[0].pdfUrl").value(PDF.toString()));

		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(activeVersionCount(paperId)).isEqualTo(1);
	}

	@Test
	void freshPostUsesTheNegativeOrPositiveCacheWithoutProviderCalls() throws Exception {
		UUID paperId = createPaper(true, false, false);
		providers.unpaywall().resolveWith(openPdfCandidate(AccessSource.UNPAYWALL, SOURCE_KEY));
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("RESOLVED"));
		int providerCalls = providers.totalCalls();
		int verifierCalls = verifier.calls();

		clock.advance(Duration.ofMinutes(30));
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("CACHE_HIT"))
				.andExpect(jsonPath("$.checkedAt").value(INITIAL_TIME.toString()));

		assertThat(providers.totalCalls()).isEqualTo(providerCalls);
		assertThat(verifier.calls()).isEqualTo(verifierCalls);
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(versionCount(paperId)).isEqualTo(1);
	}

	@Test
	void forceRefreshUpdatesTheSameLocationIdempotently() throws Exception {
		UUID paperId = createPaper(true, false, false);
		providers.unpaywall().resolveWith(openPdfCandidate(AccessSource.UNPAYWALL, SOURCE_KEY));
		String firstBody = mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String firstLocationId = JsonPath.read(firstBody, "$.locations[0].id");

		clock.advance(Duration.ofMinutes(5));
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId)
						.param("forceRefresh", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("FORCED_REFRESH"))
				.andExpect(jsonPath("$.locations.length()").value(1))
				.andExpect(jsonPath("$.locations[0].id").value(firstLocationId))
				.andExpect(jsonPath("$.locations[0].lastSeenAt")
						.value(clock.instant().toString()));

		assertThat(providers.unpaywall().calls()).isEqualTo(2);
		assertThat(providers.arxiv().calls()).isEqualTo(2);
		assertThat(verifier.calls()).isEqualTo(2);
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(versionCount(paperId)).isEqualTo(1);
		assertThat(activeVersionCount(paperId)).isEqualTo(1);
	}

	@Test
	void repeatedForceRefreshWithinCooldownReturnsRetryableRateLimit() throws Exception {
		UUID paperId = createPaper(true, false, false);
		providers.unpaywall().resolveWith(openPdfCandidate(AccessSource.UNPAYWALL, SOURCE_KEY));
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId)
						.param("forceRefresh", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("RESOLVED"));
		int providerCalls = providers.totalCalls();
		int verifierCalls = verifier.calls();

		clock.advance(Duration.ofMinutes(1));
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId)
						.param("forceRefresh", "true"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "240"))
				.andExpect(jsonPath("$.code").value("ACCESS_REFRESH_RATE_LIMITED"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.retryAfterSeconds").value(240));

		assertThat(providers.totalCalls()).isEqualTo(providerCalls);
		assertThat(verifier.calls()).isEqualTo(verifierCalls);
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from paper_access_refresh_guard where paper_id = ?",
				Long.class,
				paperId)).isEqualTo(1);
	}

	@Test
	void explicitClosedResultCreatesRestrictedNegativeCache() throws Exception {
		UUID paperId = createPaper(true, false, true);
		providers.unpaywall().returnStatus(AccessResolutionStatus.CLOSED);

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESTRICTED"))
				.andExpect(jsonPath("$.cacheDisposition").value("RESOLVED"))
				.andExpect(jsonPath("$.locations").isEmpty())
				.andExpect(jsonPath("$.providerCoverage[?(@.provider == 'UNPAYWALL')].status")
						.value(hasItem("CLOSED")));
		int providerCalls = providers.totalCalls();

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESTRICTED"))
				.andExpect(jsonPath("$.cacheDisposition").value("CACHE_HIT"));

		assertThat(providers.totalCalls()).isEqualTo(providerCalls);
		assertThat(verifier.calls()).isZero();
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(versionCount(paperId)).isZero();
	}

	@Test
	void partialProviderFailureReturnsVerifiedArxivPreprintWithWarning() throws Exception {
		UUID paperId = createPaper(true, true, false);
		providers.unpaywall().failWith("UNPAYWALL_DOWN");
		providers.arxiv().resolveWith(openPdfCandidate(AccessSource.ARXIV, "arxiv:2608.30001"));

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PREPRINT"))
				.andExpect(jsonPath("$.cacheDisposition").value("RESOLVED"))
				.andExpect(jsonPath("$.warnings", hasItem("UNPAYWALL_DOWN")))
				.andExpect(jsonPath("$.providerCoverage[?(@.provider == 'UNPAYWALL')].status")
						.value(hasItem("FAILED")))
				.andExpect(jsonPath("$.providerCoverage[?(@.provider == 'ARXIV')].status")
						.value(hasItem("RESOLVED")))
				.andExpect(jsonPath("$.locations[0].source").value("ARXIV"))
				.andExpect(jsonPath("$.locations[0].accessStatus").value("PREPRINT"))
				.andExpect(jsonPath("$.locations[0].hostType").value("PREPRINT_SERVER"))
				.andExpect(jsonPath("$.locations[0].versionType").value("PREPRINT"));

		assertThat(providers.unpaywall().calls()).isEqualTo(1);
		assertThat(providers.arxiv().calls()).isEqualTo(1);
		assertThat(verifier.calls()).isEqualTo(1);
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(activeVersionCount(paperId)).isEqualTo(1);
	}

	@Test
	void allApplicableProviderFailuresWithoutCacheReturnServiceUnavailable() throws Exception {
		UUID paperId = createPaper(true, true, false);
		providers.unpaywall().failWith("UNPAYWALL_DOWN");
		providers.arxiv().failWith("ARXIV_DOWN");

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "30"))
				.andExpect(jsonPath("$.code").value("ACCESS_PROVIDERS_UNAVAILABLE"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.retryAfterSeconds").value(30))
				.andExpect(jsonPath("$.detail")
						.value("No access provider could complete the verification request."));

		assertThat(providers.unpaywall().calls()).isEqualTo(1);
		assertThat(providers.arxiv().calls()).isEqualTo(1);
		assertThat(verifier.calls()).isZero();
		assertThat(resolutionCount(paperId)).isZero();
		assertThat(versionCount(paperId)).isZero();
	}

	@Test
	void permanentProviderFailuresAreNotReportedAsRetryable() throws Exception {
		UUID paperId = createPaper(true, false, false);
		providers.unpaywall().failPermanentlyWith("UNPAYWALL_REJECTED");

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().doesNotExist("Retry-After"))
				.andExpect(jsonPath("$.code").value("ACCESS_PROVIDERS_UNAVAILABLE"))
				.andExpect(jsonPath("$.retryable").value(false))
				.andExpect(jsonPath("$.retryAfterSeconds").doesNotExist());

		assertThat(providers.unpaywall().calls()).isEqualTo(1);
		assertThat(verifier.calls()).isZero();
		assertThat(resolutionCount(paperId)).isZero();
	}

	@Test
	void stalePriorStateFallsBackWhenApplicableProvidersFail() throws Exception {
		UUID paperId = createPaper(true, false, false);
		providers.unpaywall().resolveWith(openPdfCandidate(AccessSource.UNPAYWALL, SOURCE_KEY));
		String initialBody = mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String locationId = JsonPath.read(initialBody, "$.locations[0].id");

		clock.advance(Duration.ofHours(2));
		providers.unpaywall().failWith("UNPAYWALL_DOWN");
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN_PDF"))
				.andExpect(jsonPath("$.cacheDisposition").value("STALE_FALLBACK"))
				.andExpect(jsonPath("$.warnings", hasItem("UNPAYWALL_DOWN")))
				.andExpect(jsonPath("$.checkedAt").value(INITIAL_TIME.toString()))
				.andExpect(jsonPath("$.locations[0].id").value(locationId));

		assertThat(providers.unpaywall().calls()).isEqualTo(2);
		assertThat(verifier.calls()).isEqualTo(1);
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(versionCount(paperId)).isEqualTo(1);
		assertThat(activeVersionCount(paperId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"select checked_at from paper_access_resolution where paper_id = ?",
				Instant.class,
				paperId)).isEqualTo(INITIAL_TIME);
	}

	@Test
	void rejectedVerificationOfAStaleCandidateDoesNotRenewOldAccessAsFresh() throws Exception {
		UUID paperId = createPaper(true, false, false);
		providers.unpaywall().resolveWith(openPdfCandidate(AccessSource.UNPAYWALL, SOURCE_KEY));
		String initialBody = mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String locationId = JsonPath.read(initialBody, "$.locations[0].id");

		clock.advance(Duration.ofHours(2));
		verifier.rejectWith("UNPAYWALL_PDF_NOT_VERIFIED");
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN_PDF"))
				.andExpect(jsonPath("$.cacheDisposition").value("STALE_FALLBACK"))
				.andExpect(jsonPath("$.checkedAt").value(INITIAL_TIME.toString()))
				.andExpect(jsonPath("$.warnings", hasItem("UNPAYWALL_PDF_NOT_VERIFIED")))
				.andExpect(jsonPath("$.locations[0].id").value(locationId));

		assertThat(jdbcTemplate.queryForObject(
				"select checked_at from paper_access_resolution where paper_id = ?",
				Instant.class,
				paperId)).isEqualTo(INITIAL_TIME);
		assertThat(activeVersionCount(paperId)).isEqualTo(1);
	}

	@Test
	void rejectedCandidateWithoutCachePersistsUnavailableResolution() throws Exception {
		UUID paperId = createPaper(true, false, true);
		providers.unpaywall().resolveWith(repositoryPdfCandidate(SOURCE_KEY));
		verifier.rejectWith("UNPAYWALL_PDF_NOT_VERIFIED");

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.cacheDisposition").value("RESOLVED"))
				.andExpect(jsonPath("$.bestLocationId").value(nullValue()))
				.andExpect(jsonPath("$.warnings", hasItem("UNPAYWALL_PDF_NOT_VERIFIED")))
				.andExpect(jsonPath("$.locations").isEmpty());

		int providerCalls = providers.totalCalls();
		int verifierCalls = verifier.calls();
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.cacheDisposition").value("CACHE_HIT"));

		assertThat(providers.totalCalls()).isEqualTo(providerCalls);
		assertThat(verifier.calls()).isEqualTo(verifierCalls);
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(versionCount(paperId)).isZero();
	}

	@Test
	void paperWithoutDoiOrArxivCreatesSuccessfulNoIdentifierCache() throws Exception {
		UUID paperId = createPaper(false, false, true);

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ABSTRACT_ONLY"))
				.andExpect(jsonPath("$.cacheDisposition").value("NO_SUPPORTED_IDENTIFIER"))
				.andExpect(jsonPath("$.warnings", hasItem("NO_SUPPORTED_IDENTIFIER")))
				.andExpect(jsonPath("$.providerCoverage").isEmpty())
				.andExpect(jsonPath("$.locations").isEmpty());

		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("CACHE_HIT"));

		assertThat(providers.totalCalls()).isZero();
		assertThat(verifier.calls()).isZero();
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(versionCount(paperId)).isZero();
	}

	@Test
	void addingDoiToAPreviouslyUnsupportedPaperInvalidatesNegativeCache() throws Exception {
		UUID paperId = createPaper(false, false, true);
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("NO_SUPPORTED_IDENTIFIER"));
		assertThat(providers.totalCalls()).isZero();

		clock.advance(Duration.ofMinutes(1));
		enrichWithDoi(paperId);
		providers.unpaywall().resolveWith(openPdfCandidate(AccessSource.UNPAYWALL, SOURCE_KEY));
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN_PDF"))
				.andExpect(jsonPath("$.cacheDisposition").value("REFRESHED"))
				.andExpect(jsonPath("$.checkedAt").value(clock.instant().toString()))
				.andExpect(jsonPath("$.locations.length()").value(1));

		assertThat(providers.unpaywall().calls()).isEqualTo(1);
		assertThat(verifier.calls()).isEqualTo(1);
		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(activeVersionCount(paperId)).isEqualTo(1);
	}

	@Test
	void acceptedEmptyRefreshDeactivatesAnOldProviderLocation() throws Exception {
		UUID paperId = createPaper(true, false, false);
		providers.unpaywall().resolveWith(openPdfCandidate(AccessSource.UNPAYWALL, SOURCE_KEY));
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.locations.length()").value(1));

		clock.advance(Duration.ofMinutes(5));
		providers.unpaywall().returnStatus(AccessResolutionStatus.NO_RECORD);
		mockMvc.perform(post("/api/v1/papers/{paperId}/access/verify", paperId)
						.param("forceRefresh", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.cacheDisposition").value("FORCED_REFRESH"))
				.andExpect(jsonPath("$.locations").isEmpty())
				.andExpect(jsonPath("$.providerCoverage[?(@.provider == 'UNPAYWALL')].status")
						.value(hasItem("NO_RECORD")));

		assertThat(resolutionCount(paperId)).isEqualTo(1);
		assertThat(versionCount(paperId)).isEqualTo(1);
		assertThat(activeVersionCount(paperId)).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"select active from paper_version where paper_id = ?",
				Boolean.class,
				paperId)).isFalse();
		assertThat(jdbcTemplate.queryForObject(
				"select is_best from paper_version where paper_id = ?",
				Boolean.class,
				paperId)).isFalse();
	}

	private UUID createPaper(boolean withDoi, boolean withArxiv, boolean withAbstract) {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		List<PaperIdentifier> identifiers = new ArrayList<>();
		identifiers.add(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-ACCESS-" + suffix));
		if (withDoi) {
			identifiers.add(new PaperIdentifier(PaperIdentifierType.DOI, "", "10.5555/access." + suffix));
		}
		if (withArxiv) {
			identifiers.add(new PaperIdentifier(
					PaperIdentifierType.ARXIV,
					"",
					"2608.%05d".formatted(ARXIV_SEQUENCE.incrementAndGet())));
		}
		CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(
				"Access integration paper " + suffix,
				withAbstract ? "A locally cached abstract." : null,
				LocalDate.of(2026, 8, 17),
				2026,
				DocumentType.ARTICLE,
				"en",
				"Journal of Deterministic Tests",
				0,
				clock.instant(),
				identifiers,
				List.of());
		ProviderRecordCandidate providerRecord = new ProviderRecordCandidate(
				"OpenAlex",
				"W-ACCESS-" + suffix,
				clock.instant(),
				clock.instant(),
				URI.create("https://api.openalex.org/works/W-ACCESS-" + suffix),
				false,
				URI.create("https://openalex.org/W-ACCESS-" + suffix),
				null,
				Map.of("testFixture", true));
		UUID paperId = paperCatalog.upsert(candidate, providerRecord, clock.instant()).id();
		createdPaperIds.add(paperId);
		return paperId;
	}

	private static AccessCandidate openPdfCandidate(AccessSource source, String sourceKey) {
		return new AccessCandidate(
				source,
				sourceKey,
				true,
				source == AccessSource.ARXIV ? "preprint_server" : "publisher",
				source == AccessSource.ARXIV ? "preprint" : "publishedVersion",
				"cc-by-4.0",
				LANDING_PAGE,
				PDF,
				INITIAL_TIME.minus(Duration.ofDays(1)),
				Map.of("fixture", "deterministic"));
	}

	private static AccessCandidate repositoryPdfCandidate(String sourceKey) {
		return new AccessCandidate(
				AccessSource.UNPAYWALL,
				sourceKey,
				true,
				"repository",
				"acceptedVersion",
				"cc-by-4.0",
				LANDING_PAGE,
				PDF,
				INITIAL_TIME.minus(Duration.ofDays(1)),
				Map.of("fixture", "deterministic-repository"));
	}

	private void enrichWithDoi(UUID paperId) {
		PaperView existing = paperCatalog.findById(paperId).orElseThrow();
		List<PaperIdentifier> identifiers = new ArrayList<>(existing.identifiers());
		identifiers.add(new PaperIdentifier(
				PaperIdentifierType.DOI,
				"",
				"10.5555/enriched." + UUID.randomUUID().toString().replace("-", "")));
		CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(
				existing.title(),
				existing.abstractText(),
				existing.publicationDate(),
				existing.publicationYear(),
				existing.documentType(),
				existing.language(),
				existing.venueName(),
				existing.citationCount(),
				clock.instant(),
				identifiers,
				List.of());
		String providerRecordId = jdbcTemplate.queryForObject(
				"select provider_record_id from provider_record where paper_id = ?",
				String.class,
				paperId);
		ProviderRecordCandidate providerRecord = new ProviderRecordCandidate(
				"OpenAlex",
				providerRecordId,
				clock.instant(),
				clock.instant(),
				URI.create("https://api.openalex.org/works/" + providerRecordId),
				false,
				URI.create("https://openalex.org/" + providerRecordId),
				null,
				Map.of("testFixture", true, "enrichedWithDoi", true));
		assertThat(paperCatalog.upsert(candidate, providerRecord, clock.instant()).id()).isEqualTo(paperId);
	}

	private long resolutionCount(UUID paperId) {
		return count("paper_access_resolution", paperId);
	}

	private long versionCount(UUID paperId) {
		return count("paper_version", paperId);
	}

	private long activeVersionCount(UUID paperId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from paper_version where paper_id = ? and active",
				Long.class,
				paperId);
	}

	private long count(String table, UUID paperId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from " + table + " where paper_id = ?",
				Long.class,
				paperId);
	}

	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.openscholar")
	@EnableJpaRepositories(basePackages = "com.openscholar")
	@EnableConfigurationProperties(AccessProperties.class)
	@ComponentScan(basePackages = "com.openscholar.paper.internal.persistence")
	@Import({
		PaperAccessService.class,
		PaperAccessStore.class,
		PaperAccessRequestCoordinator.class,
		PaperAccessForceRefreshGuard.class,
		PaperAccessController.class,
		ApiExceptionHandler.class
	})
	static class AccessTestApplication {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeAccessConfiguration {

		@Bean
		@Primary
		MutableClock accessTestClock() {
			return new MutableClock(INITIAL_TIME);
		}

		@Bean
		@Primary
		FakeAccessFixture fakeAccessFixture(MutableClock clock) {
			return new FakeAccessFixture(clock);
		}

		@Bean
		@Primary
		AccessEvidenceProvider fakeUnpaywallAccessEvidenceProvider(FakeAccessFixture fixture) {
			return fixture.unpaywall();
		}

		@Bean
		@Primary
		AccessEvidenceProvider fakeArxivAccessEvidenceProvider(FakeAccessFixture fixture) {
			return fixture.arxiv();
		}

		@Bean
		@Primary
		FakeAccessCandidateVerifier fakeAccessCandidateVerifier(MutableClock clock) {
			return new FakeAccessCandidateVerifier(clock);
		}
	}

	static final class FakeAccessFixture {

		private final FakeAccessEvidenceProvider unpaywall;
		private final FakeAccessEvidenceProvider arxiv;

		FakeAccessFixture(MutableClock clock) {
			unpaywall = new FakeAccessEvidenceProvider(AccessSource.UNPAYWALL, clock);
			arxiv = new FakeAccessEvidenceProvider(AccessSource.ARXIV, clock);
		}

		void reset() {
			unpaywall.reset();
			arxiv.reset();
		}

		FakeAccessEvidenceProvider unpaywall() {
			return unpaywall;
		}

		FakeAccessEvidenceProvider arxiv() {
			return arxiv;
		}

		int totalCalls() {
			return unpaywall.calls() + arxiv.calls();
		}
	}

	static final class FakeAccessEvidenceProvider implements AccessEvidenceProvider {

		private final AccessSource source;
		private final MutableClock clock;
		private final AtomicInteger calls = new AtomicInteger();
		private Resolution resolution;

		FakeAccessEvidenceProvider(AccessSource source, MutableClock clock) {
			this.source = source;
			this.clock = clock;
			reset();
		}

		@Override
		public AccessSource source() {
			return source;
		}

		@Override
		public AccessEvidenceResult resolve(AccessEvidenceLookup lookup) {
			calls.incrementAndGet();
			return resolution.resolve(lookup);
		}

		void reset() {
			calls.set(0);
			resolution = lookup -> {
				boolean applicable = source == AccessSource.UNPAYWALL
						? lookup.normalizedDoi() != null
						: lookup.canonicalArxivId() != null;
				return AccessEvidenceResult.unresolved(
						source,
						applicable ? AccessResolutionStatus.NO_RECORD : AccessResolutionStatus.NOT_APPLICABLE,
						clock.instant(),
						applicable ? "fixture_no_record" : "fixture_not_applicable");
			};
		}

		void resolveWith(AccessCandidate candidate) {
			resolution = lookup -> new AccessEvidenceResult(
					source,
					AccessResolutionStatus.RESOLVED,
					List.of(candidate),
					clock.instant(),
					Map.of("fixture", "resolved"));
		}

		void returnStatus(AccessResolutionStatus status) {
			resolution = lookup -> AccessEvidenceResult.unresolved(
					source, status, clock.instant(), "fixture_" + status.name().toLowerCase());
		}

		void failWith(String errorCode) {
			resolution = lookup -> {
				throw new AccessProviderException(
						source,
						errorCode,
						"Deterministic provider failure",
						true,
						Duration.ofSeconds(30),
						null);
			};
		}

		void failPermanentlyWith(String errorCode) {
			resolution = lookup -> {
				throw new AccessProviderException(
						source,
						errorCode,
						"Deterministic permanent provider failure",
						false,
						null,
						null);
			};
		}

		int calls() {
			return calls.get();
		}

		@FunctionalInterface
		private interface Resolution {

			AccessEvidenceResult resolve(AccessEvidenceLookup lookup);
		}
	}

	static final class FakeAccessCandidateVerifier implements AccessCandidateVerifier {

		private final MutableClock clock;
		private final AtomicInteger calls = new AtomicInteger();
		private String rejectionWarning;

		FakeAccessCandidateVerifier(MutableClock clock) {
			this.clock = clock;
		}

		@Override
		public CandidateVerificationOutcome verify(AccessCandidate candidate, Instant retrievedAt) {
			calls.incrementAndGet();
			if (rejectionWarning != null) {
				return CandidateVerificationOutcome.rejected(rejectionWarning);
			}
			boolean arxiv = candidate.source() == AccessSource.ARXIV;
			boolean repository = "repository".equalsIgnoreCase(candidate.hostType());
			AccessVersionType versionType = arxiv
					? AccessVersionType.PREPRINT
					: repository ? AccessVersionType.ACCEPTED_MANUSCRIPT : AccessVersionType.PUBLISHED;
			AccessHostType hostType = arxiv
					? AccessHostType.PREPRINT_SERVER
					: repository ? AccessHostType.REPOSITORY : AccessHostType.PUBLISHER;
			AccessStatus accessStatus = arxiv
					? AccessStatus.PREPRINT
					: repository
							? AccessStatus.REPOSITORY_COPY
							: candidate.pdfUrl() == null ? AccessStatus.OPEN_LANDING_PAGE : AccessStatus.OPEN_PDF;
			ResolvedAccessLocation location = new ResolvedAccessLocation(
					candidate.source().name(),
					candidate.sourceKey(),
					candidate.best(),
					accessStatus,
					versionType,
					hostType,
					candidate.landingPageUrl(),
					candidate.pdfUrl(),
					candidate.license(),
					candidate.source().name() + "_VERIFIED_ACCESS",
					AccessVerificationStatus.VERIFIED,
					200,
					candidate.pdfUrl() == null ? "text/html" : "application/pdf",
					null,
					candidate.providerUpdatedAt(),
					retrievedAt,
					clock.instant());
			return CandidateVerificationOutcome.accepted(location);
		}

		void reset() {
			calls.set(0);
			rejectionWarning = null;
		}

		void rejectWith(String warningCode) {
			rejectionWarning = warningCode;
		}

		int calls() {
			return calls.get();
		}
	}

	static final class MutableClock extends Clock {

		private Instant current;

		MutableClock(Instant current) {
			this.current = current;
		}

		void set(Instant instant) {
			current = instant;
		}

		void advance(Duration duration) {
			current = current.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}
}
