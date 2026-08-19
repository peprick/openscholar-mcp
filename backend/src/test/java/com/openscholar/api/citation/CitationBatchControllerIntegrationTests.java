package com.openscholar.api.citation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import com.openscholar.TestcontainersConfiguration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, CitationBatchControllerIntegrationTests.CitationTestConfiguration.class })
@SpringBootTest(classes = CitationBatchControllerIntegrationTests.CitationBatchTestApplication.class)
class CitationBatchControllerIntegrationTests {

	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-19T10:15:30Z");

	private static final AtomicInteger IDENTIFIER_SEQUENCE = new AtomicInteger(51000);

	private static final Pattern BIBTEX_ENTRY_START = Pattern.compile("(?m)^@[^\\{]+\\{([^,]+),$");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

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
	void bibtexReturnsUtf8AttachmentSafetyHeadersAndExactCallerOrder() throws Exception {
		PaperFixture first = createPaper("First Ω paper");
		PaperFixture second = createPaper("Second β paper");
		PaperFixture third = createPaper("Third é paper");
		List<PaperFixture> callerOrder = List.of(third, first, second);

		MvcResult result = export(callerOrder.stream().map(PaperFixture::id).toList(), "bibtex")
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "application/x-bibtex;charset=UTF-8"))
			.andExpect(header().string("Content-Disposition",
					allOf(startsWith("attachment;"), containsString("openscholar-citations-3.bib"))))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(BIBTEX_ENTRY_START.matcher(body).results().map(match -> match.group(1)).toList())
			.containsExactlyElementsOf(callerOrder.stream().map(PaperFixture::citationKey).toList());
		assertThat(body).contains("Third é paper", "First Ω paper", "Second β paper").endsWith("}\n");
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void cslJsonReturnsUtf8AttachmentSafetyHeadersAndExactCallerOrder() throws Exception {
		PaperFixture first = createPaper("First λ paper");
		PaperFixture second = createPaper("Second ñ paper");
		PaperFixture third = createPaper("Third 漢 paper");
		List<PaperFixture> callerOrder = List.of(second, third, first);

		MvcResult result = export(callerOrder.stream().map(PaperFixture::id).toList(), "csl-json")
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "application/vnd.citationstyles.csl+json;charset=UTF-8"))
			.andExpect(header().string("Content-Disposition",
					allOf(startsWith("attachment;"), containsString("openscholar-citations-3.csl.json"))))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andReturn();

		JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(root.isArray()).isTrue();
		assertThat(
				IntStream.range(0, root.size()).mapToObj(index -> root.get(index).required("id").asString()).toList())
			.containsExactlyElementsOf(callerOrder.stream().map(paper -> paper.id().toString()).toList());
		assertThat(IntStream.range(0, root.size())
			.mapToObj(index -> root.get(index).required("title").asString())
			.toList()).containsExactly("Second ñ paper", "Third 漢 paper", "First λ paper");
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void acceptsInclusiveBatchBoundariesOfOneAndOneHundredPapers() throws Exception {
		List<PaperFixture> papers = IntStream.rangeClosed(1, 100)
			.mapToObj(index -> createPaper("Boundary paper %03d".formatted(index)))
			.toList();

		export(List.of(papers.getFirst().id()), "csl-json").andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("openscholar-citations-1.csl.json")))
			.andExpect(jsonPath("$.length()").value(1));

		MvcResult maximum = export(papers.stream().map(PaperFixture::id).toList(), "csl-json")
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("openscholar-citations-100.csl.json")))
			.andExpect(jsonPath("$.length()").value(100))
			.andReturn();
		JsonNode root = objectMapper.readTree(maximum.getResponse().getContentAsString());
		assertThat(
				IntStream.range(0, root.size()).mapToObj(index -> root.get(index).required("id").asString()).toList())
			.containsExactlyElementsOf(papers.stream().map(paper -> paper.id().toString()).toList());
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void rejectsDuplicatePaperIds() throws Exception {
		PaperFixture paper = createPaper("Duplicate fixture");

		export(List.of(paper.id(), paper.id()), "bibtex").andExpect(status().isBadRequest())
			.andExpect(header().string("Content-Type", startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.title").value("Invalid request"));

		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void rejectsOneHundredAndOneRawIdsEvenWhenEveryIdIsRepeated() throws Exception {
		PaperFixture paper = createPaper("Raw size fixture");
		List<UUID> repeatedIds = java.util.Collections.nCopies(101, paper.id());

		export(repeatedIds, "bibtex").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.violations[0].field").value("paperIds"));

		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void rejectsEmptyNullAndMalformedRequests() throws Exception {
		assertProblem(Map.of("paperIds", List.of(), "format", "bibtex"), "VALIDATION_FAILED");

		Map<String, Object> nullPaperIds = new LinkedHashMap<>();
		nullPaperIds.put("paperIds", null);
		nullPaperIds.put("format", "bibtex");
		assertProblem(nullPaperIds, "VALIDATION_FAILED");

		Map<String, Object> nullFormat = new LinkedHashMap<>();
		nullFormat.put("paperIds", List.of(UUID.randomUUID()));
		nullFormat.put("format", null);
		assertProblem(nullFormat, "VALIDATION_FAILED");

		assertProblem(Map.of("paperIds", java.util.Collections.singletonList(null), "format", "bibtex"),
				"VALIDATION_FAILED");
		assertRawProblem("{\"paperIds\":[\"not-a-uuid\"],\"format\":\"bibtex\"}", "INVALID_REQUEST");
		assertRawProblem("{\"paperIds\":[", "INVALID_REQUEST");
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void rejectsUnsupportedFormatWithStableProblemDetails() throws Exception {
		PaperFixture paper = createPaper("Unsupported format fixture");

		export(List.of(paper.id()), "ris").andExpect(status().isBadRequest())
			.andExpect(header().string("Content-Type", startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_CITATION_FORMAT"))
			.andExpect(jsonPath("$.title").value("Unsupported citation format"))
			.andExpect(jsonPath("$.detail").value("Citation format must be one of: bibtex, csl-json."));

		assertThat(researchProvider.calls()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "bibtex", "csl-json" })
	void missingPaperFailsTheWholeBatchWithoutReturningPartialCitations(String format) throws Exception {
		PaperFixture first = createPaper("First existing paper");
		PaperFixture second = createPaper("Second existing paper");
		UUID missingPaperId = UUID.randomUUID();

		MvcResult result = export(List.of(first.id(), missingPaperId, second.id()), format)
			.andExpect(status().isNotFound())
			.andExpect(header().string("Content-Type", startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
			.andExpect(header().doesNotExist("Content-Disposition"))
			.andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"))
			.andExpect(jsonPath("$.detail").value("Paper not found: " + missingPaperId))
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain(first.citationKey(), second.citationKey(),
				first.title(), second.title());
		assertThat(researchProvider.calls()).isZero();
	}

	private org.springframework.test.web.servlet.ResultActions export(List<UUID> paperIds, String format)
			throws Exception {
		return mockMvc.perform(post("/api/v1/citations/export").contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("paperIds", paperIds, "format", format))));
	}

	private void assertProblem(Map<String, Object> request, String code) throws Exception {
		assertRawProblem(json(request), code);
	}

	private void assertRawProblem(String request, String code) throws Exception {
		mockMvc.perform(post("/api/v1/citations/export").contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isBadRequest())
			.andExpect(header().string("Content-Type", startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
			.andExpect(jsonPath("$.code").value(code));
	}

	private PaperFixture createPaper(String title) {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		int sequence = IDENTIFIER_SEQUENCE.incrementAndGet();
		CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(title,
				"A synthetic batch-citation integration fixture.", LocalDate.of(2026, 8, 19), 2026,
				DocumentType.ARTICLE, "en", "Journal of Batch Exports", sequence, RETRIEVED_AT,
				List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-BATCH-" + suffix)),
				List.of(new PaperAuthorCandidate("A-BATCH-" + suffix, "Ada Lovelace", null, 0, true)));
		ProviderRecordCandidate providerRecord = new ProviderRecordCandidate("OpenAlex", "W-BATCH-" + suffix,
				RETRIEVED_AT, RETRIEVED_AT, URI.create("https://api.openalex.org/works/W-BATCH-" + suffix), false,
				URI.create("https://openalex.org/W-BATCH-" + suffix), null, Map.of("batchCitationFixture", true));
		UUID paperId = paperCatalog.upsert(candidate, providerRecord, RETRIEVED_AT).id();
		createdPaperIds.add(paperId);
		return new PaperFixture(paperId, title, citationKey(paperId));
	}

	private String json(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	private static String citationKey(UUID paperId) {
		return "openscholar_" + paperId.toString().replace("-", "");
	}

	private record PaperFixture(UUID id, String title, String citationKey) {
	}

	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.openscholar")
	@EnableJpaRepositories(basePackages = "com.openscholar")
	@ComponentScan(basePackages = { "com.openscholar.paper.internal.persistence", "com.openscholar.citation.internal" })
	@Import({ CitationBatchController.class, ApiExceptionHandler.class })
	static class CitationBatchTestApplication {

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
