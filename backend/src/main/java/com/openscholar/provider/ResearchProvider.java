package com.openscholar.provider;

public interface ResearchProvider {

	ProviderId id();

	ProviderSearchResult search(ProviderSearchQuery query);
}
