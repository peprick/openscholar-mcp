package com.openscholar.provider.openalex;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAlexResponse(OpenAlexMeta meta, List<OpenAlexWork> results) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAlexMeta(Long count, @JsonProperty("next_cursor") String nextCursor) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAlexWork(
		String id,
		Map<String, Object> ids,
		String doi,
		String title,
		@JsonProperty("display_name") String displayName,
		@JsonProperty("abstract_inverted_index") Map<String, List<Integer>> abstractInvertedIndex,
		@JsonProperty("publication_date") String publicationDate,
		@JsonProperty("publication_year") Integer publicationYear,
		String type,
		String language,
		@JsonProperty("primary_location") OpenAlexLocation primaryLocation,
		@JsonProperty("best_oa_location") OpenAlexLocation bestOpenAccessLocation,
		@JsonProperty("open_access") OpenAlexOpenAccess openAccess,
		@JsonProperty("cited_by_count") Integer citedByCount,
		List<OpenAlexAuthorship> authorships,
		@JsonProperty("relevance_score") Double relevanceScore,
		@JsonProperty("updated_date") String updatedDate,
		@JsonProperty("is_retracted") Boolean retracted) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAlexLocation(
		@JsonProperty("landing_page_url") String landingPageUrl,
		@JsonProperty("pdf_url") String pdfUrl,
		@JsonProperty("is_oa") Boolean openAccess,
		OpenAlexSource source) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAlexSource(String id, @JsonProperty("display_name") String displayName) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAlexOpenAccess(
		@JsonProperty("is_oa") Boolean openAccess,
		@JsonProperty("oa_status") String status,
		@JsonProperty("oa_url") String url) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAlexAuthorship(
		@JsonProperty("author_position") String authorPosition,
		@JsonProperty("is_corresponding") Boolean corresponding,
		OpenAlexAuthor author) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAlexAuthor(String id, @JsonProperty("display_name") String displayName, String orcid) {
}
