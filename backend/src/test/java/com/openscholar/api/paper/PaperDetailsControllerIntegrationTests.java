package com.openscholar.api.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessHostType;
import com.openscholar.access.AccessLocationView;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessVerificationStatus;
import com.openscholar.access.AccessVersionType;
import com.openscholar.access.ContentHandlingMode;
import com.openscholar.access.PaperAccessUseCase;
import com.openscholar.access.PaperAccessView;
import com.openscholar.api.ApiExceptionHandler;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.ProviderRecordCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@Import({
	TestcontainersConfiguration.class,
	PaperDetailsControllerIntegrationTests.FakeAccessConfiguration.class
})
@SpringBootTest(classes = PaperDetailsControllerIntegrationTests.PaperDetailsTestApplication.class)
class PaperDetailsControllerIntegrationTests {

	private static final Instant OLDER_RETRIEVED_AT = Instant.parse("2026-08-15T09:00:00Z");
	private static final Instant LATEST_RETRIEVED_AT = Instant.parse("2026-08-17T09:00:00Z");
	private static final Instant LATEST_PROVIDER_UPDATED_AT = Instant.parse("2026-08-17T08:30:00Z");
	private static final Instant CITATION_COUNT_AS_OF = Instant.parse("2026-08-17T07:00:00Z");
	private static final Instant ACCESS_CHECKED_AT = Instant.parse("2026-08-17T10:00:00Z");
	private static final Instant ACCESS_FRESH_UNTIL = Instant.parse("2026-08-18T10:00:00Z");
	private static final String PRIVATE_METADATA_SENTINEL = "UPSTREAM_PRIVATE_METADATA_SENTINEL";
	private static final String SOURCE_QUERY_SECRET = "source-query-secret";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FakePaperAccessUseCase accessUseCase;

	private final LinkedHashSet<UUID> createdPaperIds = new LinkedHashSet<>();

	@BeforeEach
	void resetAccessUseCase() {
		accessUseCase.reset();
	}

	@AfterEach
	void deleteCreatedPapersAndAuthors() {
		for (UUID paperId : createdPaperIds) {
			List<UUID> authorIds = jdbcTemplate.queryForList(
					"select author_id from paper_author where paper_id = ?",
					UUID.class,
					paperId);
			jdbcTemplate.update("delete from paper where id = ?", paperId);
			authorIds.forEach(authorId -> jdbcTemplate.update("delete from author where id = ?", authorId));
		}
		createdPaperIds.clear();
	}

	@Test
	void returnsRichCanonicalMetadataDeterministicProvenanceAndUnresolvedAccess() throws Exception {
		RichPaperFixture paper = createRichPaper();
		accessUseCase.returnFor(paper.id(), unresolvedAccess(paper.id(), AccessStatus.ABSTRACT_ONLY));

		MvcResult result = mockMvc.perform(get("/api/v1/papers/{paperId}", paper.id()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", startsWith(MediaType.APPLICATION_JSON_VALUE)))
				.andExpect(jsonPath("$.paperId").value(paper.id().toString()))
				.andExpect(jsonPath("$.title").value(paper.title()))
				.andExpect(jsonPath("$.abstractText").value("A complete, stored abstract with Unicode metadata."))
				.andExpect(jsonPath("$.publicationDate").value("2026-08-17"))
				.andExpect(jsonPath("$.publicationYear").value(2026))
				.andExpect(jsonPath("$.documentType").value("ARTICLE"))
				.andExpect(jsonPath("$.language").value("en"))
				.andExpect(jsonPath("$.venueName").value("Journal of Reproducible Systems"))
				.andExpect(jsonPath("$.citationCount").value(37))
				.andExpect(jsonPath("$.citationCountAsOf").value(CITATION_COUNT_AS_OF.toString()))
				.andExpect(jsonPath("$.metadataCompleteness").value(1.0))
				.andExpect(jsonPath("$.metadataUpdatedAt").value(LATEST_PROVIDER_UPDATED_AT.toString()))
				.andExpect(jsonPath("$.authors.length()").value(2))
				.andExpect(jsonPath("$.authors[0].name").value("Ada Lovelace"))
				.andExpect(jsonPath("$.authors[0].orcid").value(paper.adaOrcid()))
				.andExpect(jsonPath("$.authors[0].openAlexId").value(paper.adaOpenAlexId()))
				.andExpect(jsonPath("$.authors[0].position").value(0))
				.andExpect(jsonPath("$.authors[0].corresponding").value(true))
				.andExpect(jsonPath("$.authors[1].name").value("Grace Hopper"))
				.andExpect(jsonPath("$.authors[1].position").value(1))
				.andExpect(jsonPath("$.authors[1].corresponding").value(false))
				.andExpect(jsonPath("$.identifiers.length()").value(4))
				.andExpect(jsonPath("$.identifiers[0].type").value("ARXIV"))
				.andExpect(jsonPath("$.identifiers[0].namespace").value(""))
				.andExpect(jsonPath("$.identifiers[0].value").value(paper.arxivId()))
				.andExpect(jsonPath("$.identifiers[1].type").value("DOI"))
				.andExpect(jsonPath("$.identifiers[1].value").value(paper.doi()))
				.andExpect(jsonPath("$.identifiers[2].type").value("OPENALEX"))
				.andExpect(jsonPath("$.identifiers[2].value").value(paper.openAlexId()))
				.andExpect(jsonPath("$.identifiers[3].type").value("REPOSITORY"))
				.andExpect(jsonPath("$.identifiers[3].namespace").value("university-z"))
				.andExpect(jsonPath("$.identifiers[3].value").value(paper.repositoryId()))
				.andExpect(jsonPath("$.provenance.length()").value(3))
				.andExpect(jsonPath("$.provenance[0].provider").value("ALPHA SOURCE"))
				.andExpect(jsonPath("$.provenance[0].providerRecordId").value(paper.alphaRecordId()))
				.andExpect(jsonPath("$.provenance[0].sourceUrl").value(paper.publicAlphaSourceUrl()))
				.andExpect(jsonPath("$.provenance[0].providerUpdatedAt")
						.value(LATEST_PROVIDER_UPDATED_AT.toString()))
				.andExpect(jsonPath("$.provenance[0].retrievedAt").value(LATEST_RETRIEVED_AT.toString()))
				.andExpect(jsonPath("$.provenance[0].reportedOpenAccess").value(true))
				.andExpect(jsonPath("$.provenance[0].authorshipSource").value(true))
				.andExpect(jsonPath("$.provenance[1].provider").value("ZETA SOURCE"))
				.andExpect(jsonPath("$.provenance[1].retrievedAt").value(LATEST_RETRIEVED_AT.toString()))
				.andExpect(jsonPath("$.provenance[1].authorshipSource").value(false))
				.andExpect(jsonPath("$.provenance[2].provider").value("BETA SOURCE"))
				.andExpect(jsonPath("$.provenance[2].retrievedAt").value(OLDER_RETRIEVED_AT.toString()))
				.andExpect(jsonPath("$.provenance[2].authorshipSource").value(false))
				.andExpect(jsonPath("$.access.status").value("ABSTRACT_ONLY"))
				.andExpect(jsonPath("$.access.cacheDisposition").value("NOT_YET_RESOLVED"))
				.andExpect(jsonPath("$.access.checkedAt").value(nullValue()))
				.andExpect(jsonPath("$.access.freshUntil").value(nullValue()))
				.andExpect(jsonPath("$.access.bestLocationId").value(nullValue()))
				.andExpect(jsonPath("$.access.locationCount").value(0))
				.andExpect(jsonPath("$.access.warnings").isEmpty())
				.andReturn();

		assertThat(result.getResponse().getContentAsString())
				.doesNotContain(
						PRIVATE_METADATA_SENTINEL,
						paper.privatePdfUrl(),
						SOURCE_QUERY_SECRET,
						"metadataFragment",
						"reportedPdfUrl",
						"landingPageUrl");
		assertGetOnlyCalls(1);
	}

	@Test
	void returnsSparseMetadataWithAStoredLookingAccessSummary() throws Exception {
		SparsePaperFixture paper = createSparsePaper();
		UUID bestLocationId = UUID.randomUUID();
		UUID secondLocationId = UUID.randomUUID();
		accessUseCase.returnFor(paper.id(), new PaperAccessView(
				paper.id(),
				AccessStatus.OPEN_PDF,
				AccessDisposition.CACHE_HIT,
				ACCESS_CHECKED_AT,
				ACCESS_FRESH_UNTIL,
				List.of(),
				List.of("STORED_ACCESS_WARNING"),
				List.of(
						storedLocation(bestLocationId, "UNPAYWALL", true, AccessStatus.OPEN_PDF),
						storedLocation(secondLocationId, "ARXIV", false, AccessStatus.PREPRINT))));

		mockMvc.perform(get("/api/v1/papers/{paperId}", paper.id()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paperId").value(paper.id().toString()))
				.andExpect(jsonPath("$.title").value(paper.title()))
				.andExpect(jsonPath("$.abstractText").value(nullValue()))
				.andExpect(jsonPath("$.authors").isEmpty())
				.andExpect(jsonPath("$.publicationDate").value(nullValue()))
				.andExpect(jsonPath("$.publicationYear").value(nullValue()))
				.andExpect(jsonPath("$.documentType").value("OTHER"))
				.andExpect(jsonPath("$.language").value(nullValue()))
				.andExpect(jsonPath("$.venueName").value(nullValue()))
				.andExpect(jsonPath("$.citationCount").value(nullValue()))
				.andExpect(jsonPath("$.citationCountAsOf").value(nullValue()))
				.andExpect(jsonPath("$.identifiers").isEmpty())
				.andExpect(jsonPath("$.metadataCompleteness").value(0.25))
				.andExpect(jsonPath("$.metadataUpdatedAt").value(paper.retrievedAt().toString()))
				.andExpect(jsonPath("$.provenance.length()").value(1))
				.andExpect(jsonPath("$.provenance[0].provider").value("REPOSITORY"))
				.andExpect(jsonPath("$.provenance[0].providerRecordId").value(paper.providerRecordId()))
				.andExpect(jsonPath("$.provenance[0].sourceUrl").value(nullValue()))
				.andExpect(jsonPath("$.provenance[0].providerUpdatedAt").value(nullValue()))
				.andExpect(jsonPath("$.provenance[0].retrievedAt").value(paper.retrievedAt().toString()))
				.andExpect(jsonPath("$.provenance[0].reportedOpenAccess").value(false))
				.andExpect(jsonPath("$.provenance[0].authorshipSource").value(false))
				.andExpect(jsonPath("$.access.status").value("OPEN_PDF"))
				.andExpect(jsonPath("$.access.cacheDisposition").value("CACHE_HIT"))
				.andExpect(jsonPath("$.access.checkedAt").value(ACCESS_CHECKED_AT.toString()))
				.andExpect(jsonPath("$.access.freshUntil").value(ACCESS_FRESH_UNTIL.toString()))
				.andExpect(jsonPath("$.access.bestLocationId").value(bestLocationId.toString()))
				.andExpect(jsonPath("$.access.locationCount").value(2))
				.andExpect(jsonPath("$.access.warnings[0]").value("STORED_ACCESS_WARNING"));

		assertGetOnlyCalls(1);
	}

	@Test
	void validUnknownPaperReturnsStableNotFoundWithoutReadingAccess() throws Exception {
		UUID missingPaperId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/papers/{paperId}", missingPaperId))
				.andExpect(status().isNotFound())
				.andExpect(header().string(
						"Content-Type", startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
				.andExpect(jsonPath("$.type").value("urn:openscholar:problem:paper-not-found"))
				.andExpect(jsonPath("$.title").value("Paper not found"))
				.andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("Paper not found: " + missingPaperId));

		assertGetOnlyCalls(0);
	}

	@Test
	void malformedPaperIdReturnsStableInvalidRequestWithoutReadingAccess() throws Exception {
		mockMvc.perform(get("/api/v1/papers/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(
						"Content-Type", startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
				.andExpect(jsonPath("$.type").value("urn:openscholar:problem:invalid-request"))
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail")
						.value("The request body or parameter values could not be accepted."));

		assertGetOnlyCalls(0);
	}

	@Test
	void repeatedGetsAreByteForByteDeterministicAndDoNotMutatePostgres() throws Exception {
		RichPaperFixture paper = createRichPaper();
		accessUseCase.returnFor(paper.id(), unresolvedAccess(paper.id(), AccessStatus.ABSTRACT_ONLY));
		Map<String, Long> countsBefore = perPaperCounts(paper.id());

		String first = mockMvc.perform(get("/api/v1/papers/{paperId}", paper.id()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String second = mockMvc.perform(get("/api/v1/papers/{paperId}", paper.id()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(perPaperCounts(paper.id())).isEqualTo(countsBefore);
		assertGetOnlyCalls(2);
	}

	private RichPaperFixture createRichPaper() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		String title = "Interpretable AI and Gröbner Methods " + suffix;
		String doi = "10.5555/details." + suffix;
		String arxivId = "2608." + suffix.substring(0, 5);
		String openAlexId = "W-DETAILS-" + suffix;
		String repositoryId = "THESIS-" + suffix;
		String adaOrcid = "0000-" + suffix.substring(0, 4) + "-"
				+ suffix.substring(4, 8) + "-" + suffix.substring(8, 12);
		String adaOpenAlexId = "a" + suffix;
		String alphaRecordId = "A-DETAILS-" + suffix;
		String zetaRecordId = "Z-DETAILS-" + suffix;
		String betaRecordId = "B-DETAILS-" + suffix;
		String publicAlphaSourceUrl = "https://alpha.example.org/works/" + alphaRecordId;
		String privatePdfUrl = "https://files.example.org/private/" + suffix
				+ ".pdf?token=private-pdf-token";
		List<PaperIdentifier> identifiers = List.of(
				new PaperIdentifier(PaperIdentifierType.REPOSITORY, "University-Z", repositoryId),
				new PaperIdentifier(PaperIdentifierType.OPENALEX, "", openAlexId),
				new PaperIdentifier(PaperIdentifierType.DOI, "", doi),
				new PaperIdentifier(PaperIdentifierType.ARXIV, "", arxivId));

		UUID paperId = upsert(
				richCandidate(
						title,
						identifiers,
						List.of(new PaperAuthorCandidate(
								"A-OLDER-" + suffix, "Older Credited Author", null, 0, false))),
				providerRecord(
						"Beta Source",
						betaRecordId,
						OLDER_RETRIEVED_AT.minusSeconds(60),
						OLDER_RETRIEVED_AT,
						URI.create("https://beta.example.org/works/" + betaRecordId),
						false,
						privatePdfUrl,
						suffix),
				OLDER_RETRIEVED_AT);
		upsert(
				richCandidate(
						title,
						identifiers,
						List.of(new PaperAuthorCandidate(
								"A-ZETA-" + suffix, "Zeta Credited Author", null, 0, false))),
				providerRecord(
						"Zeta Source",
						zetaRecordId,
						LATEST_PROVIDER_UPDATED_AT,
						LATEST_RETRIEVED_AT,
						URI.create("https://zeta.example.org/works/" + zetaRecordId),
						false,
						privatePdfUrl,
						suffix),
				LATEST_RETRIEVED_AT);
		UUID resolvedPaperId = upsert(
				richCandidate(
						title,
						identifiers,
						List.of(
								new PaperAuthorCandidate(
										"A-GRACE-" + suffix, "Grace Hopper", null, 1, false),
								new PaperAuthorCandidate(
										"https://openalex.org/A" + suffix,
										"Ada Lovelace",
										"https://orcid.org/" + adaOrcid,
										0,
										true))),
				providerRecord(
						"Alpha Source",
						alphaRecordId,
						LATEST_PROVIDER_UPDATED_AT,
						LATEST_RETRIEVED_AT,
						URI.create(publicAlphaSourceUrl + "?api_key=" + SOURCE_QUERY_SECRET + "#private-fragment"),
						true,
						privatePdfUrl,
						suffix),
				LATEST_RETRIEVED_AT.plusSeconds(1));

		assertThat(resolvedPaperId).isEqualTo(paperId);
		return new RichPaperFixture(
				paperId,
				title,
				doi,
				arxivId,
				openAlexId,
				repositoryId,
				adaOrcid,
				adaOpenAlexId,
				alphaRecordId,
				publicAlphaSourceUrl,
				privatePdfUrl);
	}

	private SparsePaperFixture createSparsePaper() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		Instant retrievedAt = Instant.parse("2026-08-17T11:00:00Z");
		String title = "Sparse stored paper " + suffix;
		String providerRecordId = "REPOSITORY-" + suffix;
		CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(
				title,
				null,
				null,
				null,
				DocumentType.OTHER,
				null,
				null,
				null,
				null,
				List.of(),
				List.of());
		ProviderRecordCandidate provider = new ProviderRecordCandidate(
				"Repository",
				providerRecordId,
				null,
				retrievedAt,
				null,
				false,
				null,
				null,
				Map.of());
		UUID paperId = upsert(candidate, provider, retrievedAt);
		return new SparsePaperFixture(paperId, title, providerRecordId, retrievedAt);
	}

	private static CanonicalPaperCandidate richCandidate(
			String title,
			List<PaperIdentifier> identifiers,
			List<PaperAuthorCandidate> authors) {
		return new CanonicalPaperCandidate(
				title,
				"A complete, stored abstract with Unicode metadata.",
				LocalDate.of(2026, 8, 17),
				2026,
				DocumentType.ARTICLE,
				"en",
				"Journal of Reproducible Systems",
				37,
				CITATION_COUNT_AS_OF,
				identifiers,
				authors);
	}

	private static ProviderRecordCandidate providerRecord(
			String provider,
			String providerRecordId,
			Instant providerUpdatedAt,
			Instant retrievedAt,
			URI sourceUrl,
			boolean reportedOpenAccess,
			String privatePdfUrl,
			String suffix) {
		return new ProviderRecordCandidate(
				provider,
				providerRecordId,
				providerUpdatedAt,
				retrievedAt,
				sourceUrl,
				reportedOpenAccess,
				URI.create("https://landing.example.org/works/" + suffix),
				URI.create(privatePdfUrl),
				Map.of(
						"private", PRIVATE_METADATA_SENTINEL,
						"unverifiedPdf", privatePdfUrl));
	}

	private UUID upsert(
			CanonicalPaperCandidate candidate,
			ProviderRecordCandidate providerRecord,
			Instant now) {
		UUID paperId = paperCatalog.upsert(candidate, providerRecord, now).id();
		createdPaperIds.add(paperId);
		return paperId;
	}

	private Map<String, Long> perPaperCounts(UUID paperId) {
		Map<String, Long> counts = new HashMap<>();
		for (String table : List.of(
				"paper",
				"paper_external_id",
				"provider_record",
				"paper_author",
				"paper_access_resolution",
				"paper_version")) {
			String paperColumn = table.equals("paper") ? "id" : "paper_id";
			counts.put(table, jdbcTemplate.queryForObject(
					"select count(*) from " + table + " where " + paperColumn + " = ?",
					Long.class,
					paperId));
		}
		return Map.copyOf(counts);
	}

	private void assertGetOnlyCalls(int expectedGetCalls) {
		assertThat(accessUseCase.getCalls()).isEqualTo(expectedGetCalls);
		assertThat(accessUseCase.resolveCalls()).isZero();
	}

	private static PaperAccessView unresolvedAccess(UUID paperId, AccessStatus status) {
		return new PaperAccessView(
				paperId,
				status,
				AccessDisposition.NOT_YET_RESOLVED,
				null,
				null,
				List.of(),
				List.of(),
				List.of());
	}

	private static AccessLocationView storedLocation(
			UUID id, String source, boolean best, AccessStatus status) {
		return new AccessLocationView(
				id,
				source,
				best,
				status,
				status == AccessStatus.PREPRINT
						? AccessVersionType.PREPRINT
						: AccessVersionType.PUBLISHED,
				status == AccessStatus.PREPRINT
						? AccessHostType.PREPRINT_SERVER
						: AccessHostType.PUBLISHER,
				URI.create("https://access.example.org/" + id),
				URI.create("https://access.example.org/" + id + ".pdf"),
				"access.example.org",
				"cc-by-4.0",
				source + "_VERIFIED_ACCESS",
				ContentHandlingMode.LINK_ONLY,
				AccessVerificationStatus.VERIFIED,
				200,
				"application/pdf",
				null,
				ACCESS_CHECKED_AT.minusSeconds(60),
				ACCESS_CHECKED_AT.minusSeconds(30),
				ACCESS_CHECKED_AT,
				ACCESS_CHECKED_AT);
	}

	private record RichPaperFixture(
			UUID id,
			String title,
			String doi,
			String arxivId,
			String openAlexId,
			String repositoryId,
			String adaOrcid,
			String adaOpenAlexId,
			String alphaRecordId,
			String publicAlphaSourceUrl,
			String privatePdfUrl) {
	}

	private record SparsePaperFixture(
			UUID id,
			String title,
			String providerRecordId,
			Instant retrievedAt) {
	}

	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.openscholar")
	@EnableJpaRepositories(basePackages = "com.openscholar")
	@ComponentScan(basePackages = "com.openscholar.paper.internal.persistence")
	@Import({PaperDetailsController.class, ApiExceptionHandler.class})
	static class PaperDetailsTestApplication {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeAccessConfiguration {

		@Bean
		@Primary
		FakePaperAccessUseCase paperDetailsTestAccessUseCase() {
			return new FakePaperAccessUseCase();
		}
	}

	static final class FakePaperAccessUseCase implements PaperAccessUseCase {

		private final Map<UUID, PaperAccessView> responses = new HashMap<>();
		private final AtomicInteger getCalls = new AtomicInteger();
		private final AtomicInteger resolveCalls = new AtomicInteger();

		@Override
		public PaperAccessView get(UUID paperId) {
			getCalls.incrementAndGet();
			PaperAccessView response = responses.get(paperId);
			if (response == null) {
				throw new AssertionError("No fake access response configured for " + paperId);
			}
			return response;
		}

		@Override
		public PaperAccessView resolve(UUID paperId, boolean forceRefresh) {
			resolveCalls.incrementAndGet();
			throw new AssertionError("Paper details must never resolve access");
		}

		void returnFor(UUID paperId, PaperAccessView response) {
			responses.put(paperId, response);
		}

		int getCalls() {
			return getCalls.get();
		}

		int resolveCalls() {
			return resolveCalls.get();
		}

		void reset() {
			responses.clear();
			getCalls.set(0);
			resolveCalls.set(0);
		}
	}
}
