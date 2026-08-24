package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.InvalidPaperIdentifierException;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierLookupUseCase;
import com.openscholar.paper.PaperIdentifierNotFoundException;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.paper.ResolvablePaperIdentifierType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PaperIdentifierLookupServiceTests {

	private static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private PaperIdentifierLookupUseCase identifierLookup;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void resolvesDoiArxivAndOpenAlexReferencesSeenInAnOwnedSearch() {
		PaperView paper = createPaper("owned-search");
		addSearchVisibility(LOCAL_USER_ID, paper.id());
		int providerRecordsBefore = count("provider_record");

		assertThat(identifierLookup.resolve("https://doi.org/10.5555/OWNED-SEARCH"))
				.extracting(
						resolution -> resolution.paperId(),
						resolution -> resolution.identifierType(),
						resolution -> resolution.normalizedValue())
				.containsExactly(paper.id(), ResolvablePaperIdentifierType.DOI, "10.5555/owned-search");
		assertThat(identifierLookup.resolve("https://arxiv.org/pdf/2408.01234v9.pdf"))
				.extracting(
						resolution -> resolution.paperId(),
						resolution -> resolution.identifierType(),
						resolution -> resolution.normalizedValue())
				.containsExactly(paper.id(), ResolvablePaperIdentifierType.ARXIV, "2408.01234");
		assertThat(identifierLookup.resolve("https://openalex.org/works/W42424242"))
				.extracting(
						resolution -> resolution.paperId(),
						resolution -> resolution.identifierType(),
						resolution -> resolution.normalizedValue())
				.containsExactly(paper.id(), ResolvablePaperIdentifierType.OPENALEX, "w42424242");

		assertThat(count("provider_record")).isEqualTo(providerRecordsBefore);
	}

	@Test
	void resolvesAPaperSavedInAnOwnedCollection() {
		PaperView paper = createPaper("owned-library");
		addCollectionVisibility(LOCAL_USER_ID, paper.id());

		assertThat(identifierLookup.resolve("doi:10.5555/OWNED-LIBRARY").paperId())
				.isEqualTo(paper.id());
	}

	@Test
	void resolvesDecodedAndLegacyPersistenceFormsOfAnEncodedDoiUrl() {
		PaperView paper = createPaper(
				"encoded-legacy-doi",
				"https://doi.org/10.978.86123/Legacy%23Section+One");
		addSearchVisibility(LOCAL_USER_ID, paper.id());

		assertThat(identifierLookup.resolve(
				"https://doi.org/10.978.86123/Legacy%23Section+One"))
			.extracting(
					resolution -> resolution.paperId(),
					resolution -> resolution.identifierType(),
					resolution -> resolution.normalizedValue())
			.containsExactly(
					paper.id(),
					ResolvablePaperIdentifierType.DOI,
					"10.978.86123/legacy#section+one");
	}

	@Test
	void refusesAnAmbiguousDecodedAndLegacyDoiCollision() {
		PaperView decoded = createDoiOnlyPaper(
				"decoded-doi", "10.978.86123/legacy#section+one");
		PaperView legacy = createDoiOnlyPaper(
				"legacy-doi", "https://doi.org/10.978.86123/Legacy%23Section+One");
		assertThat(decoded.id()).isNotEqualTo(legacy.id());
		addSearchVisibility(LOCAL_USER_ID, decoded.id());
		addSearchVisibility(LOCAL_USER_ID, legacy.id());

		assertThatThrownBy(() -> identifierLookup.resolve(
				"https://doi.org/10.978.86123/Legacy%23Section+One"))
			.isInstanceOf(PaperIdentifierNotFoundException.class)
			.hasMessage("No visible paper was found for that identifier.");
	}

	@Test
	void makesUnknownAndOtherOwnersIdentifiersIndistinguishable() {
		PaperView paper = createPaper("private-paper");
		UUID otherOwner = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO app_user (id, display_name, created_at) VALUES (?, 'Other owner', ?)",
				otherOwner,
				OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
		addCollectionVisibility(otherOwner, paper.id());

		assertThatThrownBy(() -> identifierLookup.resolve("10.5555/private-paper"))
				.isInstanceOf(PaperIdentifierNotFoundException.class)
				.hasMessage("No visible paper was found for that identifier.");
		assertThatThrownBy(() -> identifierLookup.resolve("10.5555/missing-paper"))
				.isInstanceOf(PaperIdentifierNotFoundException.class)
				.hasMessage("No visible paper was found for that identifier.");
	}

	@Test
	void rejectsInvalidInputBeforeLookingUpTheCurrentOwner() {
		assertThatThrownBy(() -> identifierLookup.resolve("PMID:123456"))
				.isInstanceOf(InvalidPaperIdentifierException.class);
	}

	private PaperView createPaper(String suffix) {
		return createPaper(suffix, "10.5555/" + suffix);
	}

	private PaperView createPaper(String suffix, String doi) {
		return paperCatalog.upsert(
				new CanonicalPaperCandidate(
						"Exact identifier " + suffix,
						"Stored abstract",
						LocalDate.of(2026, 8, 24),
						2026,
						DocumentType.ARTICLE,
						"en",
						"Research Journal",
						0,
						NOW,
						List.of(
								new PaperIdentifier(PaperIdentifierType.DOI, "", doi),
								new PaperIdentifier(PaperIdentifierType.ARXIV, "", "2408.01234v2"),
								new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W42424242")),
						List.of()),
				new ProviderRecordCandidate(
						"test-provider",
						"record-" + suffix,
						NOW,
						NOW,
						URI.create("https://example.org/records/" + suffix),
						false,
						URI.create("https://example.org/papers/" + suffix),
						null,
						Map.of()),
				NOW);
	}

	private PaperView createDoiOnlyPaper(String suffix, String doi) {
		return paperCatalog.upsert(
				new CanonicalPaperCandidate(
						"Exact DOI " + suffix,
						"Stored abstract",
						LocalDate.of(2026, 8, 24),
						2026,
						DocumentType.ARTICLE,
						"en",
						"Research Journal",
						0,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.DOI, "", doi)),
						List.of()),
				new ProviderRecordCandidate(
						"test-provider",
						"record-" + suffix,
						NOW,
						NOW,
						URI.create("https://example.org/records/" + suffix),
						false,
						URI.create("https://example.org/papers/" + suffix),
						null,
						Map.of()),
				NOW);
	}

	private void addSearchVisibility(UUID ownerId, UUID paperId) {
		UUID searchId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO search_snapshot (
				    id, owner_id, original_query, normalized_query, fingerprint, fingerprint_version,
				    pipeline_version, filters, status, requested_mode, result_origin,
				    searched_at, fresh_until, provider_coverage, warnings,
				    total_provider_matches, result_count, created_at
				)
				VALUES (?, ?, 'owned exact paper', 'owned exact paper', ?, 1,
				        'test-v1', '{}'::jsonb, 'COMPLETED', 'ONLINE', 'PROVIDER',
				        ?, ?, '[]'::jsonb, '[]'::jsonb, 1, 1, ?)
				""", searchId, ownerId, UUID.randomUUID().toString().replace("-", "").repeat(2),
				now, now.plusHours(1), now);
		jdbcTemplate.update("""
				INSERT INTO search_result (
				    id, search_id, paper_id, paper_snapshot, result_rank, total_score,
				    reported_open_access, ranking_reasons, provider_contributions,
				    provider, provider_record_id, retrieved_at
				)
				VALUES (?, ?, ?, '{}'::jsonb, 1, 1.0, false, '[]'::jsonb, '[]'::jsonb,
				        'test-provider', ?, ?)
				""", UUID.randomUUID(), searchId, paperId, "record-" + paperId, now);
	}

	private void addCollectionVisibility(UUID ownerId, UUID paperId) {
		UUID collectionId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO library_collection (
				    id, owner_id, name, version, created_at, updated_at
				)
				VALUES (?, ?, 'Exact identifier papers', 0, ?, ?)
				""", collectionId, ownerId, now, now);
		jdbcTemplate.update("""
				INSERT INTO collection_paper (
				    id, collection_id, paper_id, reading_status, version, saved_at, updated_at
				)
				VALUES (?, ?, ?, 'UNREAD', 0, ?, ?)
				""", UUID.randomUUID(), collectionId, paperId, now, now);
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}
}
