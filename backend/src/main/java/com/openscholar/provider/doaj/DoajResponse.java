package com.openscholar.provider.doaj;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record DoajResponse(
		Long total,
		Integer page,
		Integer pageSize,
		String timestamp,
		String query,
		List<DoajArticle> results,
		String next,
		String last,
		String prev) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DoajArticle(
		String id,
		@JsonProperty("created_date") String createdDate,
		@JsonProperty("last_updated") String lastUpdated,
		DoajBibjson bibjson) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DoajBibjson(
		String title,
		String year,
		String month,
		@JsonProperty("abstract") String abstractText,
		List<DoajAuthor> author,
		DoajJournal journal,
		List<DoajIdentifier> identifier,
		List<DoajLink> link,
		List<String> keywords,
		List<DoajSubject> subject,
		@JsonProperty("start_page") String startPage,
		@JsonProperty("end_page") String endPage) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DoajAuthor(String name, @JsonProperty("orcid_id") String orcidId) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DoajJournal(
		String title,
		String publisher,
		String volume,
		String number,
		String country,
		List<String> language,
		@JsonProperty("start_page") String startPage,
		@JsonProperty("end_page") String endPage) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DoajIdentifier(String type, String id) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DoajLink(String type, String url, @JsonProperty("content_type") String contentType) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DoajSubject(String scheme, String term, String code) {
}
