package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchPaperSnapshotCompatibilityTests {

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@Test
	void deserializesLegacySnapshotsThatPredateTypedPublicationMetadata() throws Exception {
		UUID paperId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		SearchPaperSnapshot snapshot = objectMapper.readValue("""
				{
				  "id": "%s",
				  "title": "Legacy cached paper",
				  "documentType": "ARTICLE",
				  "identifiers": [],
				  "authors": []
				}
				""".formatted(paperId), SearchPaperSnapshot.class);

		PaperView paper = snapshot.toView();

		assertThat(paper.id()).isEqualTo(paperId);
		assertThat(paper.title()).isEqualTo("Legacy cached paper");
		assertThat(paper.documentType()).isEqualTo(DocumentType.ARTICLE);
		assertThat(paper.publisher()).isNull();
		assertThat(paper.institution()).isNull();
		assertThat(paper.volume()).isNull();
		assertThat(paper.issue()).isNull();
		assertThat(paper.pages()).isNull();
		assertThat(paper.articleNumber()).isNull();
		assertThat(paper.edition()).isNull();
		assertThat(paper.isbn()).isEmpty();
		assertThat(paper.issn()).isEmpty();
		assertThat(paper.degree()).isNull();
	}
}
