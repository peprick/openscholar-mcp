package com.openscholar.provider.europepmc;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcResponse(
		String version,
		Long hitCount,
		String nextCursorMark,
		EuropePmcRequest request,
		EuropePmcResultList resultList) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcRequest(
		String queryString,
		String resultType,
		String cursorMark,
		Integer pageSize) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcResultList(List<EuropePmcWork> result) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcWork(
		String id,
		String source,
		String pmid,
		String pmcid,
		String doi,
		String title,
		String authorString,
		EuropePmcAuthorList authorList,
		EuropePmcJournalInfo journalInfo,
		String pubYear,
		String pageInfo,
		String abstractText,
		String publicationStatus,
		String language,
		String pubModel,
		EuropePmcPubTypeList pubTypeList,
		String inPMC,
		String isOpenAccess,
		Integer citedByCount,
		String license,
		String dateOfRevision,
		String firstPublicationDate,
		String electronicPublicationDate) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcAuthorList(List<EuropePmcAuthor> author) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcAuthor(
		String fullName,
		EuropePmcAuthorId authorId) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcAuthorId(String type, String value) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcJournalInfo(
		String issue,
		String volume,
		Integer yearOfPublication,
		String printPublicationDate,
		EuropePmcJournal journal) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcJournal(
		String title,
		String issn,
		String essn) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EuropePmcPubTypeList(List<String> pubType) {
}
