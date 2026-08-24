package com.openscholar.api.citation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.TestCurrentUserConfiguration;
import com.openscholar.api.ApiExceptionHandler;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc(addFilters = false)
@Import({
	TestcontainersConfiguration.class,
	TestCurrentUserConfiguration.class,
	CitationControllerIntegrationTests.CitationTestConfiguration.class
})
@SpringBootTest(classes = CitationControllerIntegrationTests.CitationTestApplication.class)
class CitationControllerIntegrationTests {

	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-17T10:15:30Z");
	private static final AtomicInteger IDENTIFIER_SEQUENCE = new AtomicInteger(41000);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CountingResearchProvider researchProvider;

	private final List<UUID> createdPaperIds = new ArrayList<>();

	@BeforeEach
	void resetProvider() {
		researchProvider.reset();
	}

	@AfterEach
	void deleteCreatedPapers() {
		createdPaperIds.forEach(paperId -> jdbcTemplate.update("delete from paper where id = ?", paperId));
		createdPaperIds.clear();
	}

	@Test
	void defaultFormatReturnsRawBibtexWithDownloadAndSafetyHeaders() throws Exception {
		PaperFixture paper = createRichPaper();

		MvcResult result = mockMvc.perform(get("/api/v1/papers/{paperId}/citation", paper.id()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/x-bibtex;charset=UTF-8"))
				.andExpect(header().string("Content-Disposition", allOf(
						startsWith("attachment;"),
						containsString(paper.citationKey() + ".bib"))))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body)
				.startsWith("@article{" + paper.citationKey() + ",\n")
				.contains("  author = {{Ada Lovelace} and {Grace Hopper}},\n")
				.contains("  title = {{Reliable AI \\& Data\\_\\{Systems\\}}},\n")
				.contains("  journal = {Journal of Reproducible Research},\n")
				.contains("  year = {2026},\n")
				.contains("  month = aug,\n")
				.contains("  doi = {" + paper.doi() + "},\n")
				.contains("  eprint = {" + paper.arxivId() + "},\n")
				.contains("  url = {https://doi.org/" + paper.doi() + "},\n")
				.endsWith("}\n")
				.doesNotStartWith("\"");
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void cslJsonReturnsAOneElementArrayWithCanonicalMetadata() throws Exception {
		PaperFixture paper = createRichPaper();

		MvcResult result = mockMvc.perform(get("/api/v1/papers/{paperId}/citation", paper.id())
						.param("format", "csl-json"))
				.andExpect(status().isOk())
				.andExpect(header().string(
						"Content-Type", "application/vnd.citationstyles.csl+json;charset=UTF-8"))
				.andExpect(header().string("Content-Disposition", allOf(
						startsWith("attachment;"),
						containsString(paper.citationKey() + ".csl.json"))))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(paper.id().toString()))
				.andExpect(jsonPath("$[0].type").value("article-journal"))
				.andExpect(jsonPath("$[0].citation-key").value(paper.citationKey()))
				.andExpect(jsonPath("$[0].title").value(paper.title()))
				.andExpect(jsonPath("$[0].author[0].literal").value("Ada Lovelace"))
				.andExpect(jsonPath("$[0].author[1].literal").value("Grace Hopper"))
				.andExpect(jsonPath("$[0].container-title").value("Journal of Reproducible Research"))
				.andExpect(jsonPath("$[0].issued.date-parts[0][0]").value(2026))
				.andExpect(jsonPath("$[0].issued.date-parts[0][1]").value(8))
				.andExpect(jsonPath("$[0].issued.date-parts[0][2]").value(17))
				.andExpect(jsonPath("$[0].DOI").value(paper.doi()))
				.andExpect(jsonPath("$[0].URL").doesNotExist())
				.andExpect(jsonPath("$[0].archive").value("arXiv"))
				.andExpect(jsonPath("$[0].archive_location").value(paper.arxivId()))
				.andReturn();

		assertThat(result.getResponse().getContentAsString())
				.startsWith("[{")
				.endsWith("]\n");
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void unsupportedFormatReturnsStableProblemDetails() throws Exception {
		PaperFixture paper = createRichPaper();

		mockMvc.perform(get("/api/v1/papers/{paperId}/citation", paper.id())
						.param("format", "ris"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Content-Type", startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_CITATION_FORMAT"))
				.andExpect(jsonPath("$.title").value("Unsupported citation format"))
				.andExpect(jsonPath("$.detail").value("Citation format must be one of: bibtex, csl-json."));

		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void unknownPaperReturnsSafeNotFoundWithoutCallingAProvider() throws Exception {
		UUID missingPaperId = UUID.randomUUID();

		mockMvc.perform(get("/api/v1/papers/{paperId}/citation", missingPaperId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("Paper not found: " + missingPaperId));

		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void sparseMetadataProducesBestEffortBibtexAndCslJson() throws Exception {
		PaperFixture paper = createSparsePaper();

		String bibtex = mockMvc.perform(get("/api/v1/papers/{paperId}/citation", paper.id()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		assertThat(bibtex)
				.isEqualTo("@misc{" + paper.citationKey() + ",\n"
						+ "  title = {{" + paper.title() + "}},\n"
						+ "}\n")
				.doesNotContain("author =", "year =", "doi =", "url =", "language =");

		mockMvc.perform(get("/api/v1/papers/{paperId}/citation", paper.id())
						.param("format", "csl-json"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(paper.id().toString()))
				.andExpect(jsonPath("$[0].type").value("document"))
				.andExpect(jsonPath("$[0].citation-key").value(paper.citationKey()))
				.andExpect(jsonPath("$[0].title").value(paper.title()))
				.andExpect(jsonPath("$[0].author").doesNotExist())
				.andExpect(jsonPath("$[0].issued").doesNotExist())
				.andExpect(jsonPath("$[0].DOI").doesNotExist())
				.andExpect(jsonPath("$[0].URL").doesNotExist());

		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void repeatedExportsAreByteForByteDeterministicAndReadOnly() throws Exception {
		PaperFixture paper = createRichPaper();
		long paperCountBefore = tableCount("paper");
		long providerRecordCountBefore = tableCount("provider_record");

		String firstBibtex = exportBody(paper.id(), "bibtex");
		String secondBibtex = exportBody(paper.id(), "bibtex");
		String firstCslJson = exportBody(paper.id(), "csl-json");
		String secondCslJson = exportBody(paper.id(), "csl-json");

		assertThat(secondBibtex).isEqualTo(firstBibtex);
		assertThat(secondCslJson).isEqualTo(firstCslJson);
		assertThat(tableCount("paper")).isEqualTo(paperCountBefore);
		assertThat(tableCount("provider_record")).isEqualTo(providerRecordCountBefore);
		assertThat(researchProvider.calls()).isZero();
	}

	private String exportBody(UUID paperId, String format) throws Exception {
		return mockMvc.perform(get("/api/v1/papers/{paperId}/citation", paperId)
						.param("format", format))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
	}

	private PaperFixture createRichPaper() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		int sequence = IDENTIFIER_SEQUENCE.incrementAndGet();
		String title = "Reliable AI & Data_{Systems}";
		String doi = "10.5555/citation." + suffix;
		String arxivId = "2608.%05d".formatted(sequence);
		CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(
				title,
				"A complete citation integration fixture.",
				LocalDate.of(2026, 8, 17),
				2026,
				DocumentType.ARTICLE,
				"en",
				"Journal of Reproducible Research",
				17,
				RETRIEVED_AT,
				List.of(
						new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-CITE-" + suffix),
						new PaperIdentifier(PaperIdentifierType.DOI, "", doi),
						new PaperIdentifier(PaperIdentifierType.ARXIV, "", arxivId),
						new PaperIdentifier(PaperIdentifierType.PMID, "", Integer.toString(8_000_000 + sequence)),
						new PaperIdentifier(PaperIdentifierType.PMCID, "", "PMC" + (9_000_000 + sequence))),
				List.of(
						new PaperAuthorCandidate("A-CITE-1-" + suffix, "Ada Lovelace", null, 0, true),
						new PaperAuthorCandidate("A-CITE-2-" + suffix, "Grace Hopper", null, 1, false)));
		UUID paperId = persist(candidate, suffix);
		return new PaperFixture(paperId, title, doi, arxivId, citationKey(paperId));
	}

	private PaperFixture createSparsePaper() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		String title = "Sparse citation " + suffix;
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
		UUID paperId = persist(candidate, suffix);
		return new PaperFixture(paperId, title, null, null, citationKey(paperId));
	}

	private UUID persist(CanonicalPaperCandidate candidate, String suffix) {
		ProviderRecordCandidate providerRecord = new ProviderRecordCandidate(
				"OpenAlex",
				"W-CITE-" + suffix,
				RETRIEVED_AT,
				RETRIEVED_AT,
				URI.create("https://api.openalex.org/works/W-CITE-" + suffix),
				false,
				URI.create("https://openalex.org/W-CITE-" + suffix),
				null,
				Map.of("testFixture", true));
		UUID paperId = paperCatalog.upsert(candidate, providerRecord, RETRIEVED_AT).id();
		createdPaperIds.add(paperId);
		return paperId;
	}

	private long tableCount(String table) {
		return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
	}

	private static String citationKey(UUID paperId) {
		return "openscholar_" + paperId.toString().replace("-", "");
	}

	private record PaperFixture(
			UUID id,
			String title,
			String doi,
			String arxivId,
			String citationKey) {
	}

	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.openscholar")
	@EnableJpaRepositories(basePackages = "com.openscholar")
	@ComponentScan(basePackages = {
		"com.openscholar.paper.internal.persistence",
		"com.openscholar.citation.internal"
	})
	@Import({CitationController.class, ApiExceptionHandler.class})
	static class CitationTestApplication {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CitationTestConfiguration {

		@Bean
		CountingResearchProvider citationTestResearchProvider() {
			return new CountingResearchProvider();
		}
	}

	static final class CountingResearchProvider implements ResearchProvider {

		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			calls.incrementAndGet();
			throw new AssertionError("Citation export must not query a research provider");
		}

		int calls() {
			return calls.get();
		}

		void reset() {
			calls.set(0);
		}
	}
}
