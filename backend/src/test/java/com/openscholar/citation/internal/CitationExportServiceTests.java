package com.openscholar.citation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.openscholar.citation.CitationExport;
import com.openscholar.citation.CitationFormat;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CitationExportServiceTests {

	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final UUID PAPER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
	private static final String CITATION_KEY = "openscholar_123e4567e89b12d3a456426614174000";

	@Test
	void exportsDeterministicEscapedUtf8BibtexWithLiteralAuthors() throws Exception {
		PaperView paper = new PaperView(
				PAPER_ID,
				"  A&B_{100}% {Study} #1 $x$ ^ ~ \\ Ω\nSecond  ",
				null,
				LocalDate.of(2024, 8, 9),
				2023,
				DocumentType.ARTICLE,
				"  pt-BR ",
				"Journal & Testing",
				12,
				Instant.parse("2026-08-16T12:00:00Z"),
				List.of(new PaperIdentifier(PaperIdentifierType.DOI, "", "https://doi.org/10.5555/MiXeD")),
				List.of(
						new PaperAuthorView(UUID.randomUUID(), "Zoë {Lee} & Co.", null, null, 2, false),
						new PaperAuthorView(UUID.randomUUID(), " \t ", null, null, 1, false),
						new PaperAuthorView(UUID.randomUUID(), "José_Núñez", null, null, 0, true)));
		CitationExportService service = service(paper);

		CitationExport first = service.export(PAPER_ID, CitationFormat.BIBTEX);
		CitationExport repeated = service.export(PAPER_ID, CitationFormat.BIBTEX);

		assertThat(repeated).isEqualTo(first);
		assertThat(first.format()).isEqualTo(CitationFormat.BIBTEX);
		assertThat(first.citationKey()).isEqualTo(CITATION_KEY);
		assertThat(first.filename()).isEqualTo(CITATION_KEY + ".bib");
		assertThat(first.mediaType()).isEqualTo("application/x-bibtex");
		assertThat(first.body())
				.startsWith("@article{" + CITATION_KEY + ",\n")
				.contains("  author = {{José\\_Núñez} and {Zoë \\{Lee\\} \\& Co.}},\n")
				.contains("  title = {{A\\&B\\_\\{100\\}\\% \\{Study\\} \\#1 \\$x\\$ "
						+ "\\textasciicircum{} \\textasciitilde{} \\textbackslash{} Ω Second}},\n")
				.contains("  journal = {Journal \\& Testing},\n")
				.contains("  year = {2024},\n")
				.contains("  month = aug,\n")
				.contains("  doi = {10.5555/mixed},\n")
				.contains("  url = {https://doi.org/10.5555/mixed},\n")
				.contains("  language = {pt-BR},\n")
				.endsWith("}\n");

		JsonNode csl = OBJECT_MAPPER.readTree(service.export(PAPER_ID, CitationFormat.CSL_JSON).body()).get(0);
		assertThat(csl.required("DOI").asString()).isEqualTo("10.5555/mixed");
		assertThat(csl.has("URL")).isFalse();
	}

	@Test
	void mapsEveryDocumentTypeForBibtexAndCslJson() throws Exception {
		Map<DocumentType, ExpectedMapping> mappings = Map.ofEntries(
				Map.entry(DocumentType.ARTICLE,
						new ExpectedMapping("article", "journal", "article-journal", "container-title", null)),
				Map.entry(DocumentType.PREPRINT,
						new ExpectedMapping("unpublished", null, "article", "container-title", "Preprint")),
				Map.entry(DocumentType.CONFERENCE_PAPER,
						new ExpectedMapping("inproceedings", "booktitle", "paper-conference", "container-title", null)),
				Map.entry(DocumentType.THESIS,
						new ExpectedMapping("misc", null, "thesis", "custom", "Thesis")),
				Map.entry(DocumentType.DISSERTATION,
						new ExpectedMapping("phdthesis", null, "thesis", "custom", "Doctoral dissertation")),
				Map.entry(DocumentType.BOOK,
						new ExpectedMapping("book", null, "book", "custom", null)),
				Map.entry(DocumentType.BOOK_CHAPTER,
						new ExpectedMapping("incollection", "booktitle", "chapter", "container-title", null)),
				Map.entry(DocumentType.REPORT,
						new ExpectedMapping("techreport", null, "report", "custom", null)),
				Map.entry(DocumentType.DATASET,
						new ExpectedMapping("misc", null, "dataset", "custom", null)),
				Map.entry(DocumentType.OTHER,
						new ExpectedMapping("misc", null, "document", "custom", null)));
		Map<UUID, PaperView> papers = new LinkedHashMap<>();
		for (DocumentType documentType : DocumentType.values()) {
			UUID id = new UUID(0, documentType.ordinal() + 1L);
			papers.put(id, paper(id, documentType, "Venue"));
		}
		CitationExportService service = service(papers.values().toArray(PaperView[]::new));

		for (Map.Entry<DocumentType, ExpectedMapping> mapping : mappings.entrySet()) {
			UUID id = new UUID(0, mapping.getKey().ordinal() + 1L);
			String citationKey = "openscholar_" + id.toString().replace("-", "");
			ExpectedMapping expected = mapping.getValue();

			CitationExport bibtex = service.export(id, CitationFormat.BIBTEX);
			assertThat(bibtex.body())
					.as("BibTeX mapping for %s", mapping.getKey())
					.startsWith("@" + expected.bibtexType() + "{" + citationKey + ",\n");
			if (expected.bibtexVenueField() == null) {
				assertThat(bibtex.body()).doesNotContain("Venue");
			}
			else {
				assertThat(bibtex.body())
						.contains("  " + expected.bibtexVenueField() + " = {Venue},\n");
			}
			if (mapping.getKey() == DocumentType.PREPRINT) {
				assertThat(bibtex.body()).contains("  note = {Preprint},\n");
			}
			if (mapping.getKey() == DocumentType.THESIS) {
				assertThat(bibtex.body()).contains("  type = {Thesis},\n");
			}
			if (mapping.getKey() == DocumentType.DISSERTATION) {
				assertThat(bibtex.body()).contains("  type = {Dissertation},\n");
			}

			JsonNode csl = OBJECT_MAPPER.readTree(service.export(id, CitationFormat.CSL_JSON).body()).get(0);
			assertThat(csl.required("type").asString())
					.as("CSL type mapping for %s", mapping.getKey())
					.isEqualTo(expected.cslType());
			if (expected.cslVenueField().equals("custom")) {
				assertThat(csl.required("custom").required("openscholar-venue").asString())
						.as("CSL custom venue mapping for %s", mapping.getKey())
						.isEqualTo("Venue");
			}
			else {
				assertThat(csl.required(expected.cslVenueField()).asString())
						.as("CSL venue mapping for %s", mapping.getKey())
						.isEqualTo("Venue");
			}
			if (expected.cslGenre() == null) {
				assertThat(csl.has("genre")).isFalse();
			}
			else {
				assertThat(csl.required("genre").asString()).isEqualTo(expected.cslGenre());
			}
		}
	}

	@Test
	void rendersOneItemCslJsonWithLiteralOrderedAuthorsFullDateAndNormalizedIdentifiers() throws Exception {
		PaperView paper = new PaperView(
				PAPER_ID,
				"A linked study",
				"  An abstract.\nSecond line  ",
				LocalDate.of(2025, 2, 3),
				2024,
				DocumentType.CONFERENCE_PAPER,
				"en",
				"Research Conference",
				0,
				null,
				List.of(
						new PaperIdentifier(PaperIdentifierType.ARXIV, "", "https://arxiv.org/pdf/2401.12345v2.pdf"),
						new PaperIdentifier(PaperIdentifierType.PMID, "", "123456"),
						new PaperIdentifier(PaperIdentifierType.PMCID, "", "pmc987654")),
				List.of(
						new PaperAuthorView(UUID.randomUUID(), "The Research Collective", null, null, 1, false),
						new PaperAuthorView(UUID.randomUUID(), "María del Río", null, null, 0, true)));
		CitationExport export = service(paper).export(PAPER_ID, CitationFormat.CSL_JSON);

		JsonNode root = OBJECT_MAPPER.readTree(export.body());
		assertThat(export.format()).isEqualTo(CitationFormat.CSL_JSON);
		assertThat(export.citationKey()).isEqualTo(CITATION_KEY);
		assertThat(export.filename()).isEqualTo(CITATION_KEY + ".csl.json");
		assertThat(export.mediaType()).isEqualTo("application/vnd.citationstyles.csl+json");
		assertThat(root.isArray()).isTrue();
		assertThat(root.size()).isEqualTo(1);

		JsonNode item = root.get(0);
		assertThat(item.required("id").asString()).isEqualTo(PAPER_ID.toString());
		assertThat(item.required("type").asString()).isEqualTo("paper-conference");
		assertThat(item.required("citation-key").asString()).isEqualTo(CITATION_KEY);
		assertThat(item.required("title").asString()).isEqualTo("A linked study");
		assertThat(item.required("abstract").asString()).isEqualTo("An abstract. Second line");
		assertThat(item.required("container-title").asString()).isEqualTo("Research Conference");
		assertThat(item.required("author").size()).isEqualTo(2);
		assertThat(item.required("author").get(0).propertyNames()).containsExactly("literal");
		assertThat(item.required("author").get(0).required("literal").asString()).isEqualTo("María del Río");
		assertThat(item.required("author").get(1).propertyNames()).containsExactly("literal");
		assertThat(item.required("author").get(1).required("literal").asString())
				.isEqualTo("The Research Collective");
		assertThat(item.at("/issued/date-parts/0/0").asInt()).isEqualTo(2025);
		assertThat(item.at("/issued/date-parts/0/1").asInt()).isEqualTo(2);
		assertThat(item.at("/issued/date-parts/0/2").asInt()).isEqualTo(3);
		assertThat(item.required("language").asString()).isEqualTo("en");
		assertThat(item.required("archive").asString()).isEqualTo("arXiv");
		assertThat(item.required("archive_location").asString()).isEqualTo("2401.12345v2");
		assertThat(item.required("PMID").asString()).isEqualTo("123456");
		assertThat(item.required("PMCID").asString()).isEqualTo("PMC987654");
		assertThat(item.required("URL").asString()).isEqualTo("https://arxiv.org/abs/2401.12345v2");
		assertThat(item.has("DOI")).isFalse();
	}

	@Test
	void omitsAbsentSparseMetadata() throws Exception {
		PaperView sparse = new PaperView(
				PAPER_ID,
				"  Minimal title  ",
				null,
				null,
				null,
				DocumentType.OTHER,
				" \t ",
				"\n",
				null,
				null,
				List.of(new PaperIdentifier(PaperIdentifierType.REPOSITORY, "", "ignored")),
				List.of(new PaperAuthorView(UUID.randomUUID(), " \n ", null, null, 0, false)));
		CitationExportService service = service(sparse);

		assertThat(service.export(PAPER_ID, CitationFormat.BIBTEX).body()).isEqualTo("""
				@misc{openscholar_123e4567e89b12d3a456426614174000,
				  title = {{Minimal title}},
				}
				""");

		JsonNode item = OBJECT_MAPPER.readTree(service.export(PAPER_ID, CitationFormat.CSL_JSON).body()).get(0);
		assertThat(item.propertyNames()).containsExactly("id", "type", "citation-key", "title");
		assertThat(item.required("title").asString()).isEqualTo("Minimal title");
	}

	@Test
	void retainsCslGenreWhenAThesisOrPreprintHasNoVenue() throws Exception {
		for (Map.Entry<DocumentType, String> expected : Map.of(
				DocumentType.PREPRINT, "Preprint",
				DocumentType.THESIS, "Thesis",
				DocumentType.DISSERTATION, "Doctoral dissertation").entrySet()) {
			UUID id = new UUID(1, expected.getKey().ordinal());
			JsonNode item = OBJECT_MAPPER.readTree(service(paper(id, expected.getKey(), null))
					.export(id, CitationFormat.CSL_JSON)
					.body()).get(0);

			assertThat(item.required("genre").asString()).isEqualTo(expected.getValue());
			assertThat(item.has("container-title")).isFalse();
			assertThat(item.has("custom")).isFalse();
		}
	}

	@Test
	void raisesPaperNotFoundWhenCatalogHasNoMatchingId() {
		UUID missing = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
		CitationExportService service = service();

		assertThatThrownBy(() -> service.export(missing, CitationFormat.BIBTEX))
				.isInstanceOf(PaperNotFoundException.class)
				.hasMessage("Paper not found: " + missing);
	}

	private static PaperView paper(UUID id, DocumentType documentType, String venueName) {
		return new PaperView(
				id,
				"Mapped paper",
				null,
				null,
				2025,
				documentType,
				null,
				venueName,
				null,
				null,
				List.of(),
				List.of());
	}

	private static CitationExportService service(PaperView... papers) {
		Map<UUID, PaperView> papersById = new LinkedHashMap<>();
		for (PaperView paper : papers) {
			papersById.put(paper.id(), paper);
		}
		return new CitationExportService(new FixedPaperCatalog(papersById), OBJECT_MAPPER);
	}

	private record ExpectedMapping(
			String bibtexType,
			String bibtexVenueField,
			String cslType,
			String cslVenueField,
			String cslGenre) {
	}

	private static final class FixedPaperCatalog implements PaperCatalog {

		private final Map<UUID, PaperView> papers;

		private FixedPaperCatalog(Map<UUID, PaperView> papers) {
			this.papers = Map.copyOf(papers);
		}

		@Override
		public PaperView upsert(
				CanonicalPaperCandidate candidate,
				ProviderRecordCandidate providerRecord,
				Instant now) {
			throw new UnsupportedOperationException("Test catalog is read-only");
		}

		@Override
		public Map<UUID, PaperView> findAllByIds(Collection<UUID> paperIds) {
			Map<UUID, PaperView> found = new LinkedHashMap<>();
			for (UUID paperId : paperIds) {
				if (papers.containsKey(paperId)) {
					found.put(paperId, papers.get(paperId));
				}
			}
			return Map.copyOf(found);
		}

		@Override
		public Optional<PaperView> findById(UUID paperId) {
			return Optional.ofNullable(papers.get(paperId));
		}
	}
}
