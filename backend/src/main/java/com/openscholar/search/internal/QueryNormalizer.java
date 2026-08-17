package com.openscholar.search.internal;

import java.text.Normalizer;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
class QueryNormalizer {

	String normalize(String query) {
		return Normalizer.normalize(query, Normalizer.Form.NFKC)
				.replaceAll("[\\p{Z}\\s]+", " ")
				.strip()
				.toLowerCase(Locale.ROOT);
	}
}
