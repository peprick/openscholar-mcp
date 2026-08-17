package com.openscholar.provider;

public record ProviderAuthor(
		String providerAuthorId,
		String displayName,
		String orcid,
		int position,
		boolean corresponding) {
}
