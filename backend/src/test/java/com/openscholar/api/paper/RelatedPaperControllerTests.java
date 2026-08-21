package com.openscholar.api.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.openscholar.api.ApiExceptionHandler;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.RelatedPaperFallbackReason;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPaperRankingFeature;
import com.openscholar.paper.RelatedPaperRankingMode;
import com.openscholar.paper.RelatedPaperRankingReason;
import com.openscholar.paper.RelatedPaperUseCase;
import com.openscholar.paper.RelatedPapersView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@ContextConfiguration(classes = {
	RelatedPaperController.class,
	ApiExceptionHandler.class,
	RelatedPaperControllerTests.FakeRelatedPaperConfiguration.class
})
@WebMvcTest(RelatedPaperController.class)
class RelatedPaperControllerTests {

	private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID MATCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FakeRelatedPaperUseCase relatedPapers;

	@BeforeEach
	void resetFakeState() {
		relatedPapers.missingPaperId = null;
		relatedPapers.lexicalFallback = false;
	}

	@Test
	void returnsCanonicalRelatedPapersWithExplainableLocalRanking() throws Exception {
		mockMvc.perform(get("/api/v1/papers/{paperId}/related", SOURCE_ID))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", startsWith(MediaType.APPLICATION_JSON_VALUE)))
				.andExpect(jsonPath("$.sourcePaperId").value(SOURCE_ID.toString()))
				.andExpect(jsonPath("$.rankingMode").value("HYBRID"))
				.andExpect(jsonPath("$.fallbackReason").doesNotExist())
				.andExpect(jsonPath("$.results.length()").value(1))
				.andExpect(jsonPath("$.results[0].rank").value(1))
				.andExpect(jsonPath("$.results[0].paperId").value(MATCH_ID.toString()))
				.andExpect(jsonPath("$.results[0].title").value("Clinical reinforcement learning systems"))
				.andExpect(jsonPath("$.results[0].abstractText").value("A related abstract."))
				.andExpect(jsonPath("$.results[0].authors[0].name").value("Ada Researcher"))
				.andExpect(jsonPath("$.results[0].publicationDate").value("2025-06-01"))
				.andExpect(jsonPath("$.results[0].publicationYear").value(2025))
				.andExpect(jsonPath("$.results[0].documentType").value("ARTICLE"))
				.andExpect(jsonPath("$.results[0].language").value("en"))
				.andExpect(jsonPath("$.results[0].venue").value("Journal of Clinical AI"))
				.andExpect(jsonPath("$.results[0].publisher").value("Clinical AI Press"))
				.andExpect(jsonPath("$.results[0].institution").value("Example Medical School"))
				.andExpect(jsonPath("$.results[0].volume").value("8"))
				.andExpect(jsonPath("$.results[0].issue").value("2"))
				.andExpect(jsonPath("$.results[0].pages").value("45-61"))
				.andExpect(jsonPath("$.results[0].articleNumber").value("e101"))
				.andExpect(jsonPath("$.results[0].edition").value("1st"))
				.andExpect(jsonPath("$.results[0].isbn[0]").value("978-0-306-40615-7"))
				.andExpect(jsonPath("$.results[0].issn[0]").value("2049-3630"))
				.andExpect(jsonPath("$.results[0].degree").value("Doctor of Medicine"))
				.andExpect(jsonPath("$.results[0].citationCount").value(12))
				.andExpect(jsonPath("$.results[0].identifiers.doi").value("10.1000/related"))
				.andExpect(jsonPath("$.results[0].identifiers.arxiv").value("2501.00001"))
				.andExpect(jsonPath("$.results[0].identifiers.openAlex").value("W200"))
				.andExpect(jsonPath("$.results[0].score").isNumber())
				.andExpect(jsonPath("$.results[0].rankingReasons[0].feature")
						.value("POSTGRES_FULL_TEXT"))
				.andExpect(jsonPath("$.results[0].rankingReasons[0].value").value(0.18d))
				.andExpect(jsonPath("$.results[0].rankingReasons[1].feature")
						.value("CLAMPED_COSINE"))
				.andExpect(jsonPath("$.results[0].rankingReasons[1].value").value(0.66d));

		assertThat(relatedPapers.lastPaperId).isEqualTo(SOURCE_ID);
		assertThat(relatedPapers.lastLimit).isEqualTo(10);
	}

	@Test
	void exposesTheTypedLexicalFallbackReason() throws Exception {
		relatedPapers.lexicalFallback = true;

		mockMvc.perform(get("/api/v1/papers/{paperId}/related", SOURCE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rankingMode").value("LEXICAL"))
				.andExpect(jsonPath("$.fallbackReason").value("SOURCE_VECTOR_MISSING"))
				.andExpect(jsonPath("$.results[0].rankingReasons.length()").value(1))
				.andExpect(jsonPath("$.results[0].rankingReasons[0].feature")
						.value("POSTGRES_FULL_TEXT"));
	}

	@Test
	void acceptsTheMaximumBoundedLimit() throws Exception {
		mockMvc.perform(get("/api/v1/papers/{paperId}/related", SOURCE_ID).queryParam("limit", "25"))
				.andExpect(status().isOk());

		assertThat(relatedPapers.lastLimit).isEqualTo(25);
	}

	@Test
	void rejectsLimitsOutsideThePublicContract() throws Exception {
		mockMvc.perform(get("/api/v1/papers/{paperId}/related", SOURCE_ID).queryParam("limit", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc.perform(get("/api/v1/papers/{paperId}/related", SOURCE_ID).queryParam("limit", "26"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void returnsTheStablePaperNotFoundProblem() throws Exception {
		UUID missingId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		relatedPapers.missingPaperId = missingId;

		mockMvc.perform(get("/api/v1/papers/{paperId}/related", missingId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("Paper not found: " + missingId));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeRelatedPaperConfiguration {

		@Bean
		FakeRelatedPaperUseCase relatedPaperUseCase() {
			return new FakeRelatedPaperUseCase();
		}
	}

	static final class FakeRelatedPaperUseCase implements RelatedPaperUseCase {

		private UUID lastPaperId;
		private int lastLimit;
		private UUID missingPaperId;
		private boolean lexicalFallback;

		@Override
		public RelatedPapersView findRelated(UUID paperId, int limit) {
			lastPaperId = paperId;
			lastLimit = limit;
			if (paperId.equals(missingPaperId)) {
				throw new PaperNotFoundException(paperId);
			}
			PaperView paper = new PaperView(
					MATCH_ID,
					"Clinical reinforcement learning systems",
					"A related abstract.",
					LocalDate.parse("2025-06-01"),
					2025,
					DocumentType.ARTICLE,
					"en",
					"Journal of Clinical AI",
					12,
					Instant.parse("2026-08-18T00:00:00Z"),
					List.of(
							new PaperIdentifier(PaperIdentifierType.DOI, "", "10.1000/related"),
							new PaperIdentifier(PaperIdentifierType.ARXIV, "", "2501.00001"),
							new PaperIdentifier(PaperIdentifierType.OPENALEX, "", "W200")),
					List.of(new PaperAuthorView(
							UUID.randomUUID(), "Ada Researcher", null, "A100", 0, true)),
					"Clinical AI Press",
					"Example Medical School",
					"8",
					"2",
					"45-61",
					"e101",
					"1st",
					List.of("978-0-306-40615-7"),
					List.of("2049-3630"),
					"Doctor of Medicine");
			if (lexicalFallback) {
				return new RelatedPapersView(
						paperId,
						RelatedPaperRankingMode.LEXICAL,
						RelatedPaperFallbackReason.SOURCE_VECTOR_MISSING,
						List.of(new RelatedPaperMatch(
								1,
								paper,
								0.42d,
								List.of(new RelatedPaperRankingReason(
										RelatedPaperRankingFeature.POSTGRES_FULL_TEXT,
										0.42d)))));
			}
			return new RelatedPapersView(
					paperId,
					RelatedPaperRankingMode.HYBRID,
					null,
					List.of(new RelatedPaperMatch(
							1,
							paper,
							0.42d,
							List.of(
									new RelatedPaperRankingReason(
											RelatedPaperRankingFeature.POSTGRES_FULL_TEXT,
											0.18d),
									new RelatedPaperRankingReason(
											RelatedPaperRankingFeature.CLAMPED_COSINE,
											0.66d)))));
		}
	}
}
