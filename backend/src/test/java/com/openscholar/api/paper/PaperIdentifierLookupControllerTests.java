package com.openscholar.api.paper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.function.Function;

import com.openscholar.api.ApiExceptionHandler;
import com.openscholar.paper.InvalidPaperIdentifierException;
import com.openscholar.paper.PaperIdentifierLookupUseCase;
import com.openscholar.paper.PaperIdentifierNotFoundException;
import com.openscholar.paper.PaperIdentifierResolutionView;
import com.openscholar.paper.ResolvablePaperIdentifierType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaperIdentifierLookupControllerTests {

	private FakeLookup lookup;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		lookup = new FakeLookup();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new PaperIdentifierLookupController(lookup))
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	void returnsTheCanonicalPaperReference() throws Exception {
		UUID paperId = UUID.randomUUID();
		lookup.answerWith(new PaperIdentifierResolutionView(
				paperId, ResolvablePaperIdentifierType.DOI, "10.1000/example"));

		mockMvc.perform(get("/api/v1/papers/resolve")
						.queryParam("identifier", "https://doi.org/10.1000/Example"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.paperId").value(paperId.toString()))
				.andExpect(jsonPath("$.identifierType").value("DOI"))
				.andExpect(jsonPath("$.normalizedValue").value("10.1000/example"));

		org.assertj.core.api.Assertions.assertThat(lookup.lastIdentifier)
				.isEqualTo("https://doi.org/10.1000/Example");
	}

	@Test
	void mapsInvalidAndMissingInputsToTheStableIdentifierProblem() throws Exception {
		lookup.failWith(identifier -> new InvalidPaperIdentifierException());

		mockMvc.perform(get("/api/v1/papers/resolve").queryParam("identifier", "not-an-id"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.code").value("INVALID_PAPER_IDENTIFIER"))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("not-an-id"))));
		mockMvc.perform(get("/api/v1/papers/resolve"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.code").value("INVALID_PAPER_IDENTIFIER"));
	}

	@Test
	void mapsMissingAndInvisiblePapersToTheSameSafeNotFoundProblem() throws Exception {
		lookup.failWith(identifier -> new PaperIdentifierNotFoundException());

		mockMvc.perform(get("/api/v1/papers/resolve")
						.queryParam("identifier", "10.1000/private"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.code").value("PAPER_IDENTIFIER_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("No visible paper was found for that identifier."))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("10.1000/private"))));
	}

	private static final class FakeLookup implements PaperIdentifierLookupUseCase {

		private Function<String, PaperIdentifierResolutionView> behavior = identifier -> {
			throw new AssertionError("Lookup response was not configured");
		};

		private String lastIdentifier;

		@Override
		public PaperIdentifierResolutionView resolve(String identifier) {
			lastIdentifier = identifier;
			return behavior.apply(identifier);
		}

		void answerWith(PaperIdentifierResolutionView response) {
			behavior = identifier -> response;
		}

		void failWith(Function<String, RuntimeException> failure) {
			behavior = identifier -> {
				throw failure.apply(identifier);
			};
		}
	}
}
