package com.openscholar.access.internal.provider.unpaywall;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record UnpaywallResponse(
		String doi,
		@JsonProperty("is_oa") Boolean openAccess,
		@JsonProperty("oa_status") String openAccessStatus,
		@JsonProperty("best_oa_location") UnpaywallLocation bestOpenAccessLocation,
		@JsonProperty("oa_locations") List<UnpaywallLocation> openAccessLocations,
		String updated,
		@JsonProperty("data_standard") Integer dataStandard) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record UnpaywallLocation(
		@JsonProperty("endpoint_id") String endpointId,
		String evidence,
		@JsonProperty("host_type") String hostType,
		@JsonProperty("is_best") Boolean best,
		String license,
		@JsonProperty("oa_date") String openAccessDate,
		@JsonProperty("pmh_id") String pmhId,
		@JsonProperty("repository_institution") String repositoryInstitution,
		String updated,
		String url,
		@JsonProperty("url_for_landing_page") String landingPageUrl,
		@JsonProperty("url_for_pdf") String pdfUrl,
		String version) {
}
