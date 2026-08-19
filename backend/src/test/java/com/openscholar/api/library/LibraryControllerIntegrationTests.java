package com.openscholar.api.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import javax.sql.DataSource;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.ProviderRecordCandidate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LibraryControllerIntegrationTests {

	private static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-19T06:30:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private LibraryUseCase library;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	private final List<UUID> createdPaperIds = new ArrayList<>();

	@BeforeEach
	void removeAbandonedLibraryFixtures() {
		jdbcTemplate.update("delete from library_collection");
		jdbcTemplate.update("delete from app_user where id <> ?", LOCAL_USER_ID);
	}

	@AfterEach
	void removeFixtures() {
		jdbcTemplate.update("delete from library_collection");
		jdbcTemplate.update("delete from app_user where id <> ?", LOCAL_USER_ID);
		createdPaperIds.forEach(paperId -> jdbcTemplate.update("delete from paper where id = ?", paperId));
		createdPaperIds.clear();
	}

	@Test
	void collectionAndSavedPaperLifecycleNormalizesUnicodeTags() throws Exception {
		UUID paperId = createPaper("Lifecycle paper", "A reusable library fixture", "Ada Lovelace");
		UUID collectionId = createCollection("  Core Reading  ", " Initial notes ");

		mockMvc
			.perform(put("/api/v1/collections/{collectionId}/papers/{paperId}", collectionId, paperId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("readingStatus", "READING", "tags",
						List.of("  Machine\u00a0\u00a0Learning  ", "REVIEW")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readingStatus").value("READING"))
			.andExpect(jsonPath("$.tags.length()").value(2))
			.andExpect(jsonPath("$.tags[0]").value("machine learning"))
			.andExpect(jsonPath("$.tags[1]").value("review"));

		mockMvc
			.perform(put("/api/v1/collections/{collectionId}/papers/{paperId}", collectionId, paperId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("readingStatus", "COMPLETED", "tags", List.of("Done")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readingStatus").value("COMPLETED"));

		mockMvc.perform(get("/api/v1/collections/{collectionId}", collectionId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Core Reading"))
			.andExpect(jsonPath("$.description").value("Initial notes"))
			.andExpect(jsonPath("$.paperCount").value(1))
			.andExpect(jsonPath("$.papers.totalElements").value(1))
			.andExpect(jsonPath("$.papers.items[0].paperId").value(paperId.toString()));

		mockMvc
			.perform(patch("/api/v1/collections/{collectionId}/papers/{paperId}", collectionId, paperId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("readingStatus", "READING", "tags", List.of("To\u2003Read\uFEFF")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readingStatus").value("READING"))
			.andExpect(jsonPath("$.tags[0]").value("to read"));

		mockMvc
			.perform(patch("/api/v1/collections/{collectionId}", collectionId).contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("name", "Updated Reading", "description", "Revised"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Updated Reading"))
			.andExpect(jsonPath("$.paperCount").value(1));

		mockMvc.perform(delete("/api/v1/collections/{collectionId}/papers/{paperId}", collectionId, paperId))
			.andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/v1/collections/{collectionId}/papers/{paperId}", collectionId, paperId))
			.andExpect(status().isNoContent());
		mockMvc
			.perform(patch("/api/v1/collections/{collectionId}/papers/{paperId}", collectionId, paperId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("readingStatus", "UNREAD", "tags", List.of()))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SAVED_PAPER_NOT_FOUND"));

		mockMvc.perform(delete("/api/v1/collections/{collectionId}", collectionId)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/collections/{collectionId}", collectionId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COLLECTION_NOT_FOUND"));
	}

	@Test
	void collectionAccessIsAlwaysScopedToTheBootstrapOwner() throws Exception {
		UUID paperId = createPaper("Foreign saved paper", null, "Foreign Author");
		UUID foreignUserId = UUID.randomUUID();
		UUID foreignCollectionId = UUID.randomUUID();
		UUID foreignSavedPaperId = UUID.randomUUID();
		jdbcTemplate.update("insert into app_user (id, display_name, created_at) values (?, ?, now())", foreignUserId,
				"Foreign local fixture");
		jdbcTemplate.update("""
				insert into library_collection
				    (id, owner_id, name, description, version, created_at, updated_at)
				values (?, ?, ?, null, 0, now(), now())
				""", foreignCollectionId, foreignUserId, "Not locally visible");
		jdbcTemplate.update("""
				insert into collection_paper
				    (id, collection_id, paper_id, reading_status, version, saved_at, updated_at)
				values (?, ?, ?, 'UNREAD', 0, now(), now())
				""", foreignSavedPaperId, foreignCollectionId, paperId);

		mockMvc.perform(get("/api/v1/collections"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(get("/api/v1/collections/{collectionId}", foreignCollectionId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COLLECTION_NOT_FOUND"));
		mockMvc
			.perform(patch("/api/v1/collections/{collectionId}", foreignCollectionId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("name", "Hijack", "description", "No"))))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/v1/collections/{collectionId}", foreignCollectionId))
			.andExpect(status().isNotFound());
		mockMvc
			.perform(put("/api/v1/collections/{collectionId}/papers/{paperId}", foreignCollectionId, paperId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("readingStatus", "READING", "tags", List.of()))))
			.andExpect(status().isNotFound());
		mockMvc
			.perform(patch("/api/v1/collections/{collectionId}/papers/{paperId}", foreignCollectionId, paperId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("readingStatus", "READING", "tags", List.of()))))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/v1/collections/{collectionId}/papers/{paperId}", foreignCollectionId, paperId))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/library/papers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));

		assertThat(jdbcTemplate.queryForObject("select name from library_collection where id = ?", String.class,
				foreignCollectionId))
			.isEqualTo("Not locally visible");
		assertThat(jdbcTemplate.queryForObject("select count(*) from collection_paper where id = ?", Long.class,
				foreignSavedPaperId))
			.isOne();
	}

	@Test
	void savedSearchUsesLiteralTermsAuthorsCollectionsFiltersAndStablePagination() throws Exception {
		UUID alpha = library.createCollection("Alpha Reading Room", null).collectionId();
		UUID beta = library.createCollection("Beta Archive", null).collectionId();
		UUID literal = createPaper("100% Reliable_Systems \\ Handbook", "Special symbols", "Ada_Unique");
		UUID ordinary = createPaper("Ordinary Study", "Routine material", "Grace Hopper");
		UUID third = createPaper("Third Study", "Additional material", "Katherine Johnson");
		library.addPaper(alpha, literal, ReadingStatus.UNREAD, List.of("methods"));
		library.addPaper(alpha, ordinary, ReadingStatus.READING, List.of("review"));
		library.addPaper(beta, third, ReadingStatus.COMPLETED, List.of("methods"));
		jdbcTemplate.update("update collection_paper set saved_at = ? where collection_id = ?",
				java.sql.Timestamp.from(Instant.parse("2026-08-19T07:00:00Z")), alpha);

		assertSingleSearchResult("%", literal);
		assertSingleSearchResult("_", literal);
		assertSingleSearchResult("\\", literal);
		assertSingleSearchResult("grace hopper", ordinary);

		mockMvc.perform(get("/api/v1/library/papers").param("q", "Alpha Reading"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.totalPages").value(1));

		mockMvc
			.perform(get("/api/v1/library/papers").param("collectionId", alpha.toString())
				.param("readingStatus", "READING")
				.param("tag", " REVIEW "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].paperId").value(ordinary.toString()));

		MvcResult firstPage = mockMvc
			.perform(get("/api/v1/library/papers").param("collectionId", alpha.toString())
				.param("page", "0")
				.param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andReturn();
		MvcResult secondPage = mockMvc
			.perform(get("/api/v1/library/papers").param("collectionId", alpha.toString())
				.param("page", "1")
				.param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andReturn();
		MvcResult repeated = mockMvc
			.perform(get("/api/v1/library/papers").param("collectionId", alpha.toString())
				.param("page", "1")
				.param("size", "1"))
			.andExpect(status().isOk())
			.andReturn();
		String firstPaper = objectMapper.readTree(firstPage.getResponse().getContentAsString())
			.required("items")
			.get(0)
			.required("paperId")
			.asString();
		String secondPaper = objectMapper.readTree(secondPage.getResponse().getContentAsString())
			.required("items")
			.get(0)
			.required("paperId")
			.asString();
		assertThat(firstPaper).isNotEqualTo(secondPaper);
		assertThat(List.of(firstPaper, secondPaper)).containsExactlyInAnyOrder(literal.toString(), ordinary.toString());
		assertThat(repeated.getResponse().getContentAsString())
			.isEqualTo(secondPage.getResponse().getContentAsString());
	}

	@Test
	void v8UpgradeCanonicalizesDeduplicatesAndCapsLegacyV7Tags() {
		String schema = "library_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		UUID paperId = UUID.randomUUID();
		UUID collectionId = UUID.randomUUID();
		UUID savedPaperId = UUID.randomUUID();
		try {
			Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.target("7")
				.load()
				.migrate();
			jdbcTemplate.update("""
					insert into %s.paper
					    (id, title, normalized_title, document_type, metadata_quality,
					     metadata_updated_at, version, created_at, updated_at)
					values (?, 'Legacy paper', 'legacy paper', 'ARTICLE', 0, now(), 0, now(), now())
					""".formatted(schema), paperId);
			jdbcTemplate.update("""
					insert into %s.library_collection
					    (id, owner_id, name, version, created_at, updated_at)
					values (?, ?, 'Legacy collection', 0, now(), now())
					""".formatted(schema), collectionId, LOCAL_USER_ID);
			jdbcTemplate.update("""
					insert into %s.collection_paper
					    (id, collection_id, paper_id, reading_status, version, saved_at, updated_at)
					values (?, ?, ?, 'UNREAD', 0, now(), now())
					""".formatted(schema), savedPaperId, collectionId, paperId);

			List<String> legacyTags = new ArrayList<>(
					List.of(" ml ", "ml", "machine\u00a0learning", "machine learning"));
			java.util.stream.IntStream.rangeClosed(0, 10)
				.mapToObj(index -> "tag-%02d".formatted(index))
				.forEach(legacyTags::add);
			legacyTags.forEach(tag -> jdbcTemplate.update(
					"insert into %s.collection_paper_tag (collection_paper_id, tag) values (?, ?)".formatted(schema),
					savedPaperId, tag));

			Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.load()
				.migrate();

			List<String> migratedTags = jdbcTemplate
				.queryForList("select tag from %s.collection_paper_tag where collection_paper_id = ? order by tag"
					.formatted(schema), String.class, savedPaperId);
			assertThat(migratedTags).hasSize(10)
				.contains("ml", "machine learning")
				.doesNotHaveDuplicates()
				.allMatch(tag -> tag.equals(tag.strip()) && !tag.contains("  "));
			assertThat(jdbcTemplate.queryForObject(
					"select count(*) from %s.collection_paper_tag where collection_paper_id = ? and tag = 'ml'"
						.formatted(schema),
					Long.class, savedPaperId))
				.isOne();
		}
		finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
		}
	}

	@Test
	void serviceAndDatabaseEnforceCanonicalDistinctTagLimits() throws Exception {
		UUID collectionId = library.createCollection("Invariant checks", null).collectionId();
		UUID paperId = createPaper("Invariant paper", null, "Test Author");

		assertThat(library
			.addPaper(collectionId, paperId, ReadingStatus.UNREAD, List.of(" Research\u00a0\u2003Methods\uFEFF "))
			.tags()).containsExactly("research methods");
		assertThatThrownBy(() -> library.updatePaper(collectionId, paperId, ReadingStatus.READING,
				java.util.stream.IntStream.rangeClosed(0, 10).mapToObj(index -> "tag-" + index).toList()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("A saved paper can have at most 10 tags");
		mockMvc
			.perform(patch("/api/v1/collections/{collectionId}/papers/{paperId}", collectionId, paperId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("readingStatus", "READING", "tags",
						java.util.stream.IntStream.rangeClosed(0, 10).mapToObj(index -> "raw-" + index).toList()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc
			.perform(patch("/api/v1/collections/{collectionId}/papers/{paperId}", collectionId, paperId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("readingStatus", "READING", "tags",
						List.of("Machine Learning", " machine\u00a0learning ")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		UUID savedPaperId = jdbcTemplate.queryForObject(
				"select id from collection_paper where collection_id = ? and paper_id = ?", UUID.class, collectionId,
				paperId);
		jdbcTemplate.update("delete from collection_paper_tag where collection_paper_id = ?", savedPaperId);
		assertThatThrownBy(
				() -> jdbcTemplate.update("insert into collection_paper_tag (collection_paper_id, tag) values (?, ?)",
						savedPaperId, "not\u00a0 canonical"))
			.isInstanceOf(DataIntegrityViolationException.class);

		for (int index = 0; index < 10; index++) {
			jdbcTemplate.update("insert into collection_paper_tag (collection_paper_id, tag) values (?, ?)",
					savedPaperId, "tag-" + index);
		}
		assertThatThrownBy(
				() -> jdbcTemplate.update("insert into collection_paper_tag (collection_paper_id, tag) values (?, ?)",
						savedPaperId, "tag-10"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void assertSingleSearchResult(String query, UUID paperId) throws Exception {
		mockMvc.perform(get("/api/v1/library/papers").param("q", query))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].paperId").value(paperId.toString()));
	}

	private UUID createCollection(String name, String description) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("name", name);
		request.put("description", description);
		MvcResult result = mockMvc
			.perform(post("/api/v1/collections").contentType(MediaType.APPLICATION_JSON).content(json(request)))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/v1/collections/")))
			.andExpect(jsonPath("$.paperCount").value(0))
			.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return UUID.fromString(body.required("collectionId").asString());
	}

	private UUID createPaper(String title, String abstractText, String authorName) {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(title, abstractText, LocalDate.of(2026, 8, 19),
				2026, DocumentType.ARTICLE, "en", "Integration Journal", 5, RETRIEVED_AT,
				List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-LIBRARY-" + suffix)),
				List.of(new PaperAuthorCandidate("A-LIBRARY-" + suffix, authorName, null, 0, true)));
		ProviderRecordCandidate providerRecord = new ProviderRecordCandidate("OpenAlex", "W-LIBRARY-" + suffix,
				RETRIEVED_AT, RETRIEVED_AT, URI.create("https://api.openalex.org/works/W-LIBRARY-" + suffix), false,
				URI.create("https://openalex.org/W-LIBRARY-" + suffix), null, Map.of("libraryFixture", true));
		UUID paperId = paperCatalog.upsert(candidate, providerRecord, RETRIEVED_AT).id();
		createdPaperIds.add(paperId);
		return paperId;
	}

	private String json(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

}
