package com.openscholar.paper.internal.persistence;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.openscholar.paper.DocumentType;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

record RelatedPaperEvaluationFixture(
		String fixtureId,
		Split split,
		int version,
		String rankingMethod,
		List<FixturePaper> papers,
		List<EvaluationQuery> queries) {

	RelatedPaperEvaluationFixture {
		fixtureId = requireText(fixtureId, "fixtureId");
		split = Objects.requireNonNull(split, "split");
		papers = List.copyOf(Objects.requireNonNull(papers, "papers"));
		queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
	}

	static RelatedPaperEvaluationFixture load(ObjectMapper objectMapper, String fixturePath)
			throws Exception {
		ClassPathResource resource = new ClassPathResource(fixturePath);
		try (InputStream input = resource.getInputStream()) {
			return objectMapper.readValue(input, RelatedPaperEvaluationFixture.class);
		}
	}

	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field);
		String clean = value.strip();
		if (clean.isEmpty()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return clean;
	}

	enum Split {
		DEVELOPMENT,
		HOLDOUT
	}

	record FixturePaper(
			String key,
			String title,
			String abstractText,
			Integer year,
			DocumentType type,
			String language,
			Integer citationCount) {
	}

	record EvaluationQuery(
			String key,
			String sourceKey,
			int cutoff,
			Map<String, Integer> judgments) {
	}
}
