package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.openscholar.paper.DocumentType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ProviderQualityLiveQuerySetTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@Test
	void loadsTheFrozenEightQuerySetAndBuildsMetadataOnlyArticleCommands() throws Exception {
		ProviderQualityLiveQuerySet querySet = ProviderQualityLiveQuerySet.load(
				OBJECT_MAPPER, ProviderQualityLiveQuerySet.RESOURCE_PATH);

		assertThat(querySet.schemaVersion()).isEqualTo(1);
		assertThat(querySet.querySetId()).isEqualTo("europe-pmc-live-queries-v1");
		assertThat(querySet.sourcePolicy())
				.isEqualTo("AUTHOR_WRITTEN_TOPICS_WITHOUT_RELEVANCE_LABELS");
		assertThat(querySet.pageSize()).isEqualTo(20);
		assertThat(querySet.queries()).hasSize(8);
		assertThat(querySet.commands()).hasSize(8).allSatisfy(request -> {
			assertThat(request.key()).isNotBlank();
			assertThat(request.command().query()).isNotBlank();
			assertThat(request.command().yearFrom()).isNull();
			assertThat(request.command().yearTo()).isNull();
			assertThat(request.command().documentTypes()).isEqualTo(Set.of(DocumentType.ARTICLE));
			assertThat(request.command().openAccessOnly()).isFalse();
			assertThat(request.command().minimumCitations()).isZero();
			assertThat(request.command().languages()).isEmpty();
			assertThat(request.command().pageSize()).isEqualTo(20);
			assertThat(request.command().cursor()).isEqualTo("*");
		});
	}

	@Test
	void rejectsUnknownMissingAndNonCanonicalFields() throws Exception {
		ObjectNode unknownRoot = resourceTree();
		unknownRoot.put("provider", "EUROPE_PMC");
		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(unknownRoot))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown keys at $")
				.hasMessageContaining("provider");

		ObjectNode missingQuery = resourceTree();
		((ObjectNode) missingQuery.required("queries").get(0)).remove("query");
		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(missingQuery))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Missing keys at $.queries[0]")
				.hasMessageContaining("query");

		ObjectNode nonCanonicalKey = resourceTree();
		((ObjectNode) nonCanonicalKey.required("queries").get(0)).put("key", "Not Canonical");
		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(nonCanonicalKey))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("canonical lowercase slug");
	}

	@Test
	void rejectsPageAndQuerySetBoundsBeforeACommandCanBeBuilt() throws Exception {
		for (int pageSize : new int[] {0, 21}) {
			ObjectNode invalidPageSize = resourceTree();
			invalidPageSize.put("pageSize", pageSize);
			assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(invalidPageSize))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("pageSize");
		}

		ObjectNode sevenQueries = resourceTree();
		((ArrayNode) sevenQueries.required("queries")).remove(7);
		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(sevenQueries))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly eight queries");

		ObjectNode duplicateKey = resourceTree();
		JsonNode queries = duplicateKey.required("queries");
		((ObjectNode) queries.get(1)).put("key", queries.get(0).required("key").asString());
		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(duplicateKey))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicate query key");

		ObjectNode wrongSourcePolicy = resourceTree();
		wrongSourcePolicy.put("sourcePolicy", "LIVE_PROVIDER_RESULTS");
		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(wrongSourcePolicy))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sourcePolicy");
	}

	@Test
	void rejectsDuplicateFieldsTrailingDocumentsAndOversizedResources() throws Exception {
		byte[] valid = resourceBytes();
		String json = new String(valid, StandardCharsets.UTF_8);
		byte[] duplicateField = json.replaceFirst(
				"\\\"schemaVersion\\\"\\s*:\\s*1,",
				"\"schemaVersion\": 1, \"schemaVersion\": 1,")
				.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(
				OBJECT_MAPPER, duplicateField))
				.isInstanceOf(tools.jackson.core.JacksonException.class)
				.hasMessageContaining("Duplicate Object property");
		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(
				OBJECT_MAPPER, (json + "\n{}").getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(tools.jackson.core.JacksonException.class)
				.hasMessageContaining("Trailing token");
		assertThatThrownBy(() -> ProviderQualityLiveQuerySet.parse(
				OBJECT_MAPPER, new byte[64 * 1_024 + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("65536 bytes");
	}

	private static ObjectNode resourceTree() throws Exception {
		ClassPathResource resource = new ClassPathResource(ProviderQualityLiveQuerySet.RESOURCE_PATH);
		try (InputStream input = resource.getInputStream()) {
			return (ObjectNode) OBJECT_MAPPER.readTree(input);
		}
	}

	private static byte[] resourceBytes() throws Exception {
		ClassPathResource resource = new ClassPathResource(ProviderQualityLiveQuerySet.RESOURCE_PATH);
		try (InputStream input = resource.getInputStream()) {
			return input.readAllBytes();
		}
	}
}
