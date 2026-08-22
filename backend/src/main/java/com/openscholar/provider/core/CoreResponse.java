package com.openscholar.provider.core;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
record CoreResponse(
		@JsonAlias("total_hits") Long totalHits,
		Integer limit,
		Integer offset,
		List<CoreWork> results) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record CoreWork(
		JsonNode id,
		String title,
		@JsonProperty("abstract") String abstractText,
		String doi,
		@JsonAlias("arxiv_id") String arxivId,
		List<JsonNode> authors,
		@JsonAlias("citation_count") Integer citationCount,
		@JsonAlias("data_providers") List<JsonNode> dataProviders,
		@JsonAlias("document_type") JsonNode documentType,
		@JsonAlias("download_url") String downloadUrl,
		JsonNode language,
		List<CoreIdentifier> identifiers,
		@JsonAlias("oai_ids") List<String> oaiIds,
		@JsonAlias("published_date") String publishedDate,
		JsonNode publisher,
		@JsonAlias("pubmed_id") JsonNode pubmedId,
		@JsonAlias("source_fulltext_urls") List<JsonNode> sourceFulltextUrls,
		List<CoreJournal> journals,
		@JsonAlias("updated_date") String updatedDate,
		@JsonAlias("year_published") Integer yearPublished,
		List<JsonNode> links) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record CoreIdentifier(String identifier, String type) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record CoreJournal(String title, List<JsonNode> identifiers) {
}
