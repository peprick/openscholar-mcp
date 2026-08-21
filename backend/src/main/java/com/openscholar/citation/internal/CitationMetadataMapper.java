package com.openscholar.citation.internal;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import org.springframework.web.util.UriUtils;

final class CitationMetadataMapper {

	private static final Pattern DOI = Pattern.compile("^10\\.\\d{4,9}/\\S+$");
	private static final Pattern MODERN_ARXIV = Pattern.compile("^\\d{4}\\.\\d{4,5}(?:v\\d+)?$");
	private static final Pattern LEGACY_ARXIV = Pattern.compile(
			"^[a-z][a-z0-9.-]*/\\d{7}(?:v\\d+)?$");
	private static final Pattern PMID = Pattern.compile("^\\d{1,12}$");
	private static final Pattern PMCID = Pattern.compile("^PMC\\d{1,12}$", Pattern.CASE_INSENSITIVE);
	private static final Pattern LANGUAGE = Pattern.compile(
			"^(?<language>[a-zA-Z]{2,3})(?:[-_](?<region>[a-zA-Z]{2}))?$");

	private CitationMetadataMapper() {
	}

	static CitationItem from(PaperView paper) {
		String doi = identifier(paper, PaperIdentifierType.DOI, CitationMetadataMapper::normalizeDoi);
		String arxivId = identifier(paper, PaperIdentifierType.ARXIV, CitationMetadataMapper::normalizeArxiv);
		String pmid = identifier(
				paper, PaperIdentifierType.PMID, value -> matching(value, PMID, false));
		String pmcid = identifier(
				paper, PaperIdentifierType.PMCID, value -> matching(value, PMCID, true));
		List<String> authors = paper.authors().stream()
				.sorted(java.util.Comparator.comparingInt(author -> author.position()))
				.map(author -> clean(author.displayName()))
				.filter(java.util.Objects::nonNull)
				.toList();
		String title = clean(paper.title());
		String citationKey = "openscholar_" + paper.id().toString().replace("-", "");
		return new CitationItem(
				paper.id(),
				citationKey,
				paper.documentType(),
				title,
				clean(paper.abstractText()),
				authors,
				paper.publicationDate(),
				paper.publicationYear(),
				normalizeLanguage(paper.language()),
				clean(paper.venueName()),
				clean(paper.publisher()),
				clean(paper.institution()),
				clean(paper.volume()),
				clean(paper.issue()),
				clean(paper.pages()),
				clean(paper.articleNumber()),
				clean(paper.edition()),
				cleanList(paper.isbn()),
				cleanList(paper.issn()),
				clean(paper.degree()),
				doi,
				arxivId,
				pmid,
				pmcid,
				canonicalUrl(doi, arxivId));
	}

	private static String identifier(
			PaperView paper, PaperIdentifierType type, Function<String, String> normalizer) {
		return paper.identifiers().stream()
				.filter(identifier -> identifier.type() == type)
				.map(identifier -> identifier.value())
				.map(normalizer)
				.filter(java.util.Objects::nonNull)
				.sorted()
				.findFirst()
				.orElse(null);
	}

	private static String normalizeDoi(String value) {
		String clean = clean(value);
		if (clean == null) {
			return null;
		}
		clean = clean.replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/", "")
				.replaceFirst("(?i)^doi:\\s*", "")
				.toLowerCase(Locale.ROOT);
		return clean.length() <= 500 && DOI.matcher(clean).matches() ? clean : null;
	}

	private static String normalizeArxiv(String value) {
		String clean = clean(value);
		if (clean == null) {
			return null;
		}
		clean = clean.replaceFirst("(?i)^arxiv:\\s*", "")
				.replaceFirst("(?i)^https?://(?:www\\.)?arxiv\\.org/(?:abs|pdf)/", "")
				.replaceFirst("(?i)\\.pdf$", "")
				.toLowerCase(Locale.ROOT);
		return MODERN_ARXIV.matcher(clean).matches() || LEGACY_ARXIV.matcher(clean).matches()
				? clean
				: null;
	}

	private static String matching(String value, Pattern pattern, boolean uppercase) {
		String clean = clean(value);
		if (clean == null || !pattern.matcher(clean).matches()) {
			return null;
		}
		return uppercase ? clean.toUpperCase(Locale.ROOT) : clean;
	}

	private static String canonicalUrl(String doi, String arxivId) {
		if (doi != null) {
			return "https://doi.org/" + UriUtils.encodePath(doi, StandardCharsets.UTF_8);
		}
		return arxivId == null
				? null
				: "https://arxiv.org/abs/" + UriUtils.encodePath(arxivId, StandardCharsets.UTF_8);
	}

	private static String normalizeLanguage(String value) {
		String clean = clean(value);
		if (clean == null) {
			return null;
		}
		java.util.regex.Matcher matcher = LANGUAGE.matcher(clean);
		if (!matcher.matches()) {
			return null;
		}
		String language = matcher.group("language").toLowerCase(Locale.ROOT);
		String region = matcher.group("region");
		return region == null ? language : language + "-" + region.toUpperCase(Locale.ROOT);
	}

	private static List<String> cleanList(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream()
				.map(CitationMetadataMapper::clean)
				.filter(java.util.Objects::nonNull)
				.distinct()
				.sorted()
				.toList();
	}

	static String clean(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String withoutControls = value.codePoints()
				.map(codePoint -> Character.isISOControl(codePoint)
						|| codePoint >= Character.MIN_SURROGATE
						&& codePoint <= Character.MAX_SURROGATE ? ' ' : codePoint)
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
				.toString();
		String normalized = Normalizer.normalize(withoutControls, Normalizer.Form.NFC)
				.replaceAll("[\\p{Z}\\s]+", " ")
				.strip();
		return normalized.isEmpty() ? null : normalized;
	}

}
