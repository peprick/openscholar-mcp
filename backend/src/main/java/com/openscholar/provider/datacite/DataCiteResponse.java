package com.openscholar.provider.datacite;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteResponse(List<DataCiteResource> data, DataCiteMeta meta, DataCiteLinks links) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteResource(String id, String type, DataCiteAttributes attributes) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteAttributes(
		String doi,
		List<DataCiteTitle> titles,
		List<DataCiteCreator> creators,
		Object publisher,
		Integer publicationYear,
		List<DataCiteContributor> contributors,
		List<DataCiteDate> dates,
		String language,
		DataCiteTypes types,
		List<DataCiteRelatedIdentifier> relatedIdentifiers,
		List<DataCiteRelatedItem> relatedItems,
		List<DataCiteRight> rightsList,
		List<DataCiteDescription> descriptions,
		String updated,
		Integer citationCount,
		String schemaVersion,
		String version,
		String clientId) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteTitle(String title, String titleType) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteCreator(String name, List<DataCiteNameIdentifier> nameIdentifiers) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteNameIdentifier(
		String nameIdentifier,
		String nameIdentifierScheme,
		String schemeUri) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteContributor(String name, String contributorType) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteDate(String date, String dateType) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteTypes(String resourceTypeGeneral, String resourceType) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteRelatedIdentifier(
		String relatedIdentifier,
		String relatedIdentifierType,
		String relationType) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteRelatedItem(
		String relatedItemType,
		String relationType,
		DataCiteRelatedItemIdentifier relatedItemIdentifier,
		List<DataCiteTitle> titles,
		String volume,
		String issue,
		String number,
		String numberType,
		String firstPage,
		String lastPage,
		Object publisher,
		String edition) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteRelatedItemIdentifier(String relatedItemIdentifier, String relatedItemIdentifierType) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteRight(
		String rights,
		String rightsUri,
		String rightsIdentifier,
		String rightsIdentifierScheme) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteDescription(String description, String descriptionType) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteMeta(Long total, Integer totalPages, Integer page) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record DataCiteLinks(String self, String next) {
}
