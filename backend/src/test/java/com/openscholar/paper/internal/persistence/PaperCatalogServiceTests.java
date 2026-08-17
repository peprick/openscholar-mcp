package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PaperCatalogServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void idempotentlyUpsertsByNormalizedDoiBeforeOpenAlexAndPreservesNewerMetadata() {
		CanonicalPaperCandidate initial = paper(
				"Initial title",
				null,
				4,
				Instant.parse("2026-08-15T00:00:00Z"),
				List.of(
						new PaperIdentifier(PaperIdentifierType.DOI, "", "https://doi.org/10.1000/Test"),
						new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "https://openalex.org/W123")),
				List.of());
		PaperView created = paperCatalog.upsert(
				initial,
				providerRecord("W123", Instant.parse("2026-08-15T01:00:00Z"), Map.of("source", "initial")),
				NOW);

		CanonicalPaperCandidate enriched = paper(
				"Enriched title",
				"A useful abstract",
				8,
				Instant.parse("2026-08-16T00:00:00Z"),
				List.of(
						new PaperIdentifier(PaperIdentifierType.DOI, "", "doi:10.1000/TEST"),
						new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "w123")),
				List.of());
		PaperView updated = paperCatalog.upsert(
				enriched,
				providerRecord("W123", Instant.parse("2026-08-16T01:00:00Z"), Map.of("source", "enriched")),
				NOW.plusSeconds(60));

		CanonicalPaperCandidate stale = paper(
				"Stale title",
				null,
				2,
				Instant.parse("2026-08-14T00:00:00Z"),
				List.of(
						new PaperIdentifier(PaperIdentifierType.DOI, "", "10.1000/test"),
						new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W123")),
				List.of());
		PaperView afterStale = paperCatalog.upsert(
				stale,
				providerRecord("W123", Instant.parse("2026-08-14T01:00:00Z"), Map.of()),
				NOW.plusSeconds(120));

		assertThat(updated.id()).isEqualTo(created.id());
		assertThat(afterStale.id()).isEqualTo(created.id());
		assertThat(afterStale.title()).isEqualTo("Enriched title");
		assertThat(afterStale.abstractText()).isEqualTo("A useful abstract");
		assertThat(afterStale.citationCount()).isEqualTo(8);
		assertThat(afterStale.citationCountAsOf())
				.isEqualTo(Instant.parse("2026-08-16T00:00:00Z"));
		assertThat(count("paper")).isEqualTo(1);
		assertThat(count("paper_external_id")).isEqualTo(2);
		assertThat(count("provider_record")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"select jsonb_typeof(metadata_fragment) from provider_record",
				String.class)).isEqualTo("object");
		assertThat(jdbcTemplate.queryForObject(
				"select metadata_quality from paper where id = ?",
				Double.class,
				created.id())).isBetween(0.0, 1.0);
	}

	@Test
	void staleProviderReplayCannotAttachANewIdentifier() {
		Instant newer = Instant.parse("2026-08-16T10:00:00Z");
		PaperView original = paperCatalog.upsert(
				paper(
						"Canonical paper",
						null,
						1,
						newer,
						List.of(
								new PaperIdentifier(PaperIdentifierType.DOI, "", "10.1000/canonical"),
								new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-REPLAY")),
						List.of()),
				providerRecord("W-REPLAY", newer, Map.of()),
				NOW);

		PaperView afterReplay = paperCatalog.upsert(
				paper(
						"Poisoned replay",
						null,
						0,
						newer.minusSeconds(60),
						List.of(
								new PaperIdentifier(PaperIdentifierType.DOI, "", "10.1000/unrelated"),
								new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-REPLAY")),
						List.of()),
				providerRecord("W-REPLAY", newer.minusSeconds(60), Map.of()),
				NOW.plusSeconds(60));

		PaperView unrelated = paperCatalog.upsert(
				paper(
						"Actually unrelated",
						null,
						0,
						newer.plusSeconds(60),
						List.of(
								new PaperIdentifier(PaperIdentifierType.DOI, "", "10.1000/unrelated"),
								new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-UNRELATED")),
						List.of()),
				providerRecord("W-UNRELATED", newer.plusSeconds(60), Map.of()),
				NOW.plusSeconds(120));

		assertThat(afterReplay.id()).isEqualTo(original.id());
		assertThat(afterReplay.title()).isEqualTo("Canonical paper");
		assertThat(afterReplay.identifiers())
				.noneMatch(identifier -> identifier.value().equalsIgnoreCase("10.1000/unrelated"));
		assertThat(unrelated.id()).isNotEqualTo(original.id());
		assertThat(count("paper")).isEqualTo(2);
	}

	@Test
	void storesOrderedAuthorshipWithProviderProvenanceWithoutDeduplicatingByName() {
		List<PaperAuthorCandidate> authors = List.of(
				new PaperAuthorCandidate(null, "Same Name", null, 1, false),
				new PaperAuthorCandidate("https://openalex.org/A42", "Known Author", "0000-0001-2345-6789", 2, true),
				new PaperAuthorCandidate(null, "Same Name", null, 0, false));
		PaperView paper = paperCatalog.upsert(
				paper(
						"Authorship paper",
						"Abstract",
						1,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-AUTHORS")),
						authors),
				providerRecord("W-AUTHORS", NOW, Map.of()),
				NOW);

		assertThat(paper.authors()).extracting(author -> author.position())
				.containsExactly(0, 1, 2);
		assertThat(paper.authors().get(0).displayName()).isEqualTo("Same Name");
		assertThat(paper.authors().get(1).displayName()).isEqualTo("Same Name");
		assertThat(paper.authors().get(0).id()).isNotEqualTo(paper.authors().get(1).id());
		assertThat(paper.authors().get(2).corresponding()).isTrue();
		assertThat(paper.authors().get(2).openAlexId()).isEqualTo("a42");

		List<Map<String, Object>> provenance = jdbcTemplate.queryForList("""
				select pr.provider, pr.provider_record_id, pa.author_position
				from paper_author pa
				join provider_record pr on pr.id = pa.provider_record_id
				where pa.paper_id = ?
				order by pa.author_position
				""", paper.id());
		assertThat(provenance).hasSize(3);
		assertThat(provenance).allSatisfy(row -> {
			assertThat(row.get("provider")).isEqualTo("openalex");
			assertThat(row.get("provider_record_id")).isEqualTo("W-AUTHORS");
		});
		assertThat(count("author")).isEqualTo(3);

		PaperView repeated = paperCatalog.upsert(
				paper(
						"Authorship paper",
						"Abstract",
						1,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-AUTHORS")),
						authors),
				providerRecord("W-AUTHORS", NOW.plusSeconds(30), Map.of()),
				NOW.plusSeconds(30));

		assertThat(repeated.id()).isEqualTo(paper.id());
		assertThat(count("paper_author")).isEqualTo(3);
		assertThat(count("author")).isEqualTo(3);

		PaperView secondPaper = paperCatalog.upsert(
				paper(
						"Second authorship paper",
						null,
						0,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-AUTHORS-2")),
						List.of(new PaperAuthorCandidate(
								null, "Known Author Alias", "https://orcid.org/0000-0001-2345-6789", 0, false))),
				providerRecord("W-AUTHORS-2", NOW.plusSeconds(60), Map.of()),
				NOW.plusSeconds(60));

		assertThat(secondPaper.authors()).singleElement()
				.satisfies(author -> assertThat(author.id()).isEqualTo(paper.authors().get(2).id()));
		assertThat(count("author")).isEqualTo(3);
	}

	@Test
	void newerEmptyAuthorshipClearsProviderAssociations() {
		PaperView created = paperCatalog.upsert(
				paper(
						"Authors can be corrected",
						null,
						0,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-CLEAR-AUTHORS")),
						List.of(new PaperAuthorCandidate("A-CLEAR", "Original Author", null, 0, false))),
				providerRecord("W-CLEAR-AUTHORS", NOW, Map.of()),
				NOW);

		PaperView corrected = paperCatalog.upsert(
				paper(
						"Authors can be corrected",
						null,
						0,
						NOW.plusSeconds(60),
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-CLEAR-AUTHORS")),
						List.of()),
				providerRecord("W-CLEAR-AUTHORS", NOW.plusSeconds(60), Map.of()),
				NOW.plusSeconds(60));

		assertThat(created.authors()).hasSize(1);
		assertThat(corrected.authors()).isEmpty();
		assertThat(count("paper_author")).isZero();
	}

	@Test
	void supportsSingleAndBulkLookup() {
		PaperView first = paperCatalog.upsert(
				paper(
						"First lookup paper",
						null,
						0,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-LOOKUP-1")),
						List.of()),
				providerRecord("W-LOOKUP-1", NOW, Map.of()),
				NOW);
		PaperView second = paperCatalog.upsert(
				paper(
						"Second lookup paper",
						null,
						0,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-LOOKUP-2")),
						List.of()),
				providerRecord("W-LOOKUP-2", NOW, Map.of()),
				NOW);
		UUID missing = UUID.randomUUID();

		assertThat(paperCatalog.findById(first.id())).contains(first);
		assertThat(paperCatalog.findById(missing)).isEmpty();
		assertThat(paperCatalog.findAllByIds(List.of(second.id(), missing, first.id())))
				.containsOnlyKeys(first.id(), second.id());
	}

	@Test
	void rejectsIdentifiersThatWouldMergeCanonicalPapers() {
		PaperView doiPaper = paperCatalog.upsert(
				paper(
						"DOI paper",
						null,
						0,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.DOI, "", "10.1000/conflict")),
						List.of()),
				providerRecord("doi-only", NOW, Map.of()),
				NOW);
		PaperView openAlexPaper = paperCatalog.upsert(
				paper(
						"OpenAlex paper",
						null,
						0,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-CONFLICT")),
						List.of()),
				providerRecord("openalex-only", NOW, Map.of()),
				NOW);

		assertThat(doiPaper.id()).isNotEqualTo(openAlexPaper.id());
		assertThatThrownBy(() -> paperCatalog.upsert(
				paper(
						"Conflicting record",
						null,
						0,
						NOW,
						List.of(
								new PaperIdentifier(PaperIdentifierType.DOI, "", "doi:10.1000/CONFLICT"),
								new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-CONFLICT")),
						List.of()),
				providerRecord("conflicting-record", NOW, Map.of()),
				NOW))
				.isInstanceOf(PaperCatalogConflictException.class)
				.hasMessageContaining("different canonical papers");
	}

	@Test
	void rejectsOversizedProviderMetadata() {
		String oversized = "x".repeat(31 * 1024);
		assertThatThrownBy(() -> paperCatalog.upsert(
				paper(
						"Oversized metadata",
						null,
						0,
						NOW,
						List.of(new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W-LARGE")),
						List.of()),
				providerRecord("W-LARGE", NOW, Map.of("payload", oversized)),
				NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must not exceed");
	}

	private CanonicalPaperCandidate paper(
			String title,
			String abstractText,
			Integer citationCount,
			Instant citationCountAsOf,
			List<PaperIdentifier> identifiers,
			List<PaperAuthorCandidate> authors) {
		return new CanonicalPaperCandidate(
				title,
				abstractText,
				LocalDate.of(2026, 8, 1),
				2026,
				DocumentType.ARTICLE,
				"en",
				"Research Journal",
				citationCount,
				citationCountAsOf,
				identifiers,
				authors);
	}

	private ProviderRecordCandidate providerRecord(
			String providerRecordId, Instant retrievedAt, Map<String, Object> metadata) {
		return new ProviderRecordCandidate(
				"OpenAlex",
				providerRecordId,
				retrievedAt,
				retrievedAt,
				URI.create("https://api.openalex.org/works/" + providerRecordId),
				true,
				URI.create("https://openalex.org/" + providerRecordId),
				null,
				metadata);
	}

	private long count(String table) {
		return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
	}
}
