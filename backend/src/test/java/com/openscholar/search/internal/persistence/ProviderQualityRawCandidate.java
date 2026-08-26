package com.openscholar.search.internal.persistence;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;

record ProviderQualityRawCandidate(
		int schemaVersion,
		String reviewKey,
		String queryKey,
		int providerRank,
		ProviderId provider,
		String providerRecordId,
		String title,
		String abstractText,
		String publicationDate,
		Integer publicationYear,
		DocumentType documentType,
		String language,
		String venueName,
		Integer citationCount,
		List<Author> authors,
		boolean reportedOpenAccess,
		String providerUpdatedAt,
		List<Identifier> identifiers,
		String sourceUrl,
		String publisher,
		String institution,
		String volume,
		String issue,
		String pages,
		String articleNumber,
		String edition,
		List<String> isbn,
		List<String> issn,
		String degree) {

	private static final int MAX_ABSTRACT_CHARACTERS = 200_000;
	private static final int MAX_AUTHORS = 1_000;
	private static final int MAX_IDENTIFIERS = 32;
	private static final int MAX_SERIAL_VALUES = 100;
	private static final Set<ProviderId> EXPECTED_PROVIDERS =
			Set.of(ProviderId.OPENALEX, ProviderId.EUROPE_PMC);

	ProviderQualityRawCandidate {
		authors = List.copyOf(Objects.requireNonNull(authors, "authors"));
		identifiers = List.copyOf(Objects.requireNonNull(identifiers, "identifiers"));
		isbn = List.copyOf(Objects.requireNonNull(isbn, "isbn"));
		issn = List.copyOf(Objects.requireNonNull(issn, "issn"));
	}

	static ProviderQualityRawCandidate from(
			String querySetId,
			String queryKey,
			int providerRank,
			ProviderPaperRecord record) {
		Objects.requireNonNull(record, "record");
		if (providerRank < 1 || providerRank > 20) {
			throw new IllegalArgumentException("providerRank must be an integer from 1 through 20");
		}
		if (!EXPECTED_PROVIDERS.contains(record.provider())) {
			throw new IllegalArgumentException("provider must be OPENALEX or EUROPE_PMC");
		}
		if (record.documentType() != DocumentType.ARTICLE) {
			throw new IllegalArgumentException("raw provider-quality candidates must be ARTICLE metadata");
		}
		String boundedQueryKey = required(queryKey, "queryKey", 3, 80);
		String rawProviderRecordId = record.providerRecordId();
		String providerRecordId = required(
				rawProviderRecordId, "providerRecordId", 1, 1_024);
		if (!providerRecordId.equals(rawProviderRecordId)
				|| providerRecordId.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(
					"providerRecordId must not contain surrounding whitespace or control characters");
		}
		Integer publicationYear = publicationYear(record.publicationYear());
		Integer citationCount = nonNegative(record.citationCount(), "citationCount");
		List<Author> authors = sanitizeAuthors(record.authors());
		List<Identifier> identifiers = sanitizeIdentifiers(record.identifiers());
		return new ProviderQualityRawCandidate(
				1,
				ProviderQualityRawReviewKey.create(
						querySetId, boundedQueryKey, record.provider(), providerRecordId),
				boundedQueryKey,
				providerRank,
				record.provider(),
				providerRecordId,
				required(record.title(), "title", 1, 10_000),
				optional(record.abstractText(), "abstractText", MAX_ABSTRACT_CHARACTERS),
				date(record.publicationDate()),
				publicationYear,
				record.documentType(),
				optional(record.language(), "language", 100),
				optional(record.venueName(), "venueName", 10_000),
				citationCount,
				authors,
				record.reportedOpenAccess(),
				instant(record.providerUpdatedAt()),
				identifiers,
				publicUrl(record.sourceUrl()),
				optional(record.publisher(), "publisher", 10_000),
				optional(record.institution(), "institution", 10_000),
				optional(record.volume(), "volume", 1_000),
				optional(record.issue(), "issue", 1_000),
				optional(record.pages(), "pages", 2_000),
				optional(record.articleNumber(), "articleNumber", 1_000),
				optional(record.edition(), "edition", 1_000),
				sanitizeStrings(record.isbn(), "isbn", MAX_SERIAL_VALUES, 256),
				sanitizeStrings(record.issn(), "issn", MAX_SERIAL_VALUES, 256),
				optional(record.degree(), "degree", 2_000));
	}

	private static List<Author> sanitizeAuthors(List<ProviderAuthor> values) {
		if (values.size() > MAX_AUTHORS) {
			throw new IllegalArgumentException("authors must contain at most " + MAX_AUTHORS + " entries");
		}
		List<Author> sanitized = new ArrayList<>(values.size());
		for (ProviderAuthor value : values) {
			ProviderAuthor author = Objects.requireNonNull(value, "authors must not contain null");
			if (author.position() < 0 || author.position() > MAX_AUTHORS) {
				throw new IllegalArgumentException("author position is outside the bounded range");
			}
			sanitized.add(new Author(
					required(author.displayName(), "author displayName", 1, 1_000),
					optional(author.orcid(), "author orcid", 200),
					author.position(),
					author.corresponding()));
		}
		return List.copyOf(sanitized);
	}

	private static List<Identifier> sanitizeIdentifiers(List<PaperIdentifier> values) {
		if (values.size() > MAX_IDENTIFIERS) {
			throw new IllegalArgumentException(
					"identifiers must contain at most " + MAX_IDENTIFIERS + " entries");
		}
		List<Identifier> sanitized = new ArrayList<>(values.size());
		Set<String> unique = new HashSet<>();
		for (PaperIdentifier value : values) {
			PaperIdentifier identifier = Objects.requireNonNull(
					value, "identifiers must not contain null");
			PaperIdentifierType type = Objects.requireNonNull(identifier.type(), "identifier type");
			String namespace = optional(identifier.namespace(), "identifier namespace", 200);
			String identifierValue = required(identifier.value(), "identifier value", 1, 2_048);
			String key = type.name() + '\n' + Objects.toString(namespace, "") + '\n' + identifierValue;
			if (!unique.add(key)) {
				throw new IllegalArgumentException("identifiers must not contain duplicates");
			}
			sanitized.add(new Identifier(type, namespace, identifierValue));
		}
		sanitized.sort(Comparator.comparing((Identifier value) -> value.type().name())
				.thenComparing(value -> Objects.toString(value.namespace(), ""))
				.thenComparing(Identifier::value));
		return List.copyOf(sanitized);
	}

	private static List<String> sanitizeStrings(
			List<String> values, String field, int maximumItems, int maximumCharacters) {
		if (values.size() > maximumItems) {
			throw new IllegalArgumentException(
					field + " must contain at most " + maximumItems + " entries");
		}
		List<String> sanitized = values.stream()
				.map(value -> required(value, field + " value", 1, maximumCharacters))
				.distinct()
				.sorted()
				.toList();
		if (sanitized.size() != values.size()) {
			throw new IllegalArgumentException(field + " must not contain duplicate values");
		}
		return sanitized;
	}

	private static String required(String value, String field, int minimum, int maximum) {
		if (value == null) {
			throw new IllegalArgumentException(field + " must not be null");
		}
		String stripped = value.strip();
		if (stripped.length() < minimum || stripped.length() > maximum) {
			throw new IllegalArgumentException(
					field + " must contain " + minimum + " through " + maximum + " characters");
		}
		return stripped;
	}

	private static String optional(String value, String field, int maximum) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return required(value, field, 1, maximum);
	}

	private static Integer publicationYear(Integer value) {
		if (value != null && (value < 1_000 || value > 9_999)) {
			throw new IllegalArgumentException("publicationYear must be from 1000 through 9999");
		}
		return value;
	}

	private static Integer nonNegative(Integer value, String field) {
		if (value != null && value < 0) {
			throw new IllegalArgumentException(field + " must not be negative");
		}
		return value;
	}

	private static String date(LocalDate value) {
		return value == null ? null : value.toString();
	}

	private static String instant(Instant value) {
		return value == null ? null : value.toString();
	}

	private static String publicUrl(URI value) {
		if (value == null) {
			return null;
		}
		String scheme = value.getScheme();
		String serialized = value.toASCIIString();
		if (!value.isAbsolute()
				|| value.getHost() == null
				|| value.getHost().isBlank()
				|| value.getUserInfo() != null
				|| value.getRawQuery() != null
				|| value.getRawFragment() != null
				|| (!("http".equalsIgnoreCase(scheme)) && !("https".equalsIgnoreCase(scheme)))
				|| serialized.length() > 4_096) {
			throw new IllegalArgumentException(
					"sourceUrl must be a bounded absolute HTTP(S) URI with a host and without credentials, query, or fragment");
		}
		return serialized;
	}

	record Author(String displayName, String orcid, int position, boolean corresponding) {
	}

	record Identifier(PaperIdentifierType type, String namespace, String value) {
	}
}
