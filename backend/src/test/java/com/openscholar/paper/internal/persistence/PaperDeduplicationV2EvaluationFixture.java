package com.openscholar.paper.internal.persistence;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifierNormalizer;
import com.openscholar.paper.PaperIdentifierType;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record PaperDeduplicationV2EvaluationFixture(
		int schemaVersion,
		String fixtureId,
		String policyId,
		String split,
		String labelUnit,
		String sourcePolicy,
		String retrievedAt,
		List<FixtureRecord> records,
		List<CriticalPair> criticalPairs,
		List<IngestOrder> ingestOrders) {

	private static final Set<String> ROOT_REQUIRED = Set.of(
			"schemaVersion", "fixtureId", "policyId", "split", "labelUnit", "sourcePolicy",
			"retrievedAt", "records", "criticalPairs", "ingestOrders");
	private static final Set<String> RECORD_REQUIRED = Set.of(
			"key", "goldCluster", "caseFamily", "provider", "paper");
	private static final Set<String> PROVIDER_REQUIRED = Set.of("name", "recordId");
	private static final Set<String> PAPER_REQUIRED = Set.of(
			"title", "publicationYear", "documentType", "language", "identifiers", "authors");
	private static final Set<String> PAPER_OPTIONAL = Set.of(
			"abstractText", "publicationDate", "venueName");
	private static final Set<String> IDENTIFIER_REQUIRED = Set.of("type", "value");
	private static final Set<String> IDENTIFIER_OPTIONAL = Set.of("namespace");
	private static final Set<String> AUTHOR_REQUIRED = Set.of(
			"displayName", "position", "corresponding");
	private static final Set<String> AUTHOR_OPTIONAL = Set.of("openAlexId", "orcid");
	private static final Set<String> CRITICAL_PAIR_REQUIRED = Set.of(
			"left", "right", "relation", "caseFamily", "reason");
	private static final Set<String> INGEST_ORDER_REQUIRED = Set.of("key", "recordKeys");

	PaperDeduplicationV2EvaluationFixture {
		records = List.copyOf(Objects.requireNonNull(records, "records"));
		criticalPairs = List.copyOf(Objects.requireNonNull(criticalPairs, "criticalPairs"));
		ingestOrders = List.copyOf(Objects.requireNonNull(ingestOrders, "ingestOrders"));
	}

	static PaperDeduplicationV2EvaluationFixture load(
			ObjectMapper objectMapper, String resourcePath) throws Exception {
		ClassPathResource resource = new ClassPathResource(resourcePath);
		try (InputStream input = resource.getInputStream()) {
			return parse(objectMapper, objectMapper.readTree(input));
		}
	}

	static PaperDeduplicationV2EvaluationFixture parse(
			ObjectMapper objectMapper, JsonNode root) throws Exception {
		validateSchema(root);
		PaperDeduplicationV2EvaluationFixture fixture = objectMapper.treeToValue(
				root, PaperDeduplicationV2EvaluationFixture.class);
		fixture.validateReferencesAndValues();
		return fixture;
	}

	Map<String, FixtureRecord> recordsByKey() {
		Map<String, FixtureRecord> values = new LinkedHashMap<>();
		for (FixtureRecord record : records) {
			values.put(record.key(), record);
		}
		return Map.copyOf(values);
	}

	Instant retrievedInstant() {
		return Instant.parse(retrievedAt);
	}

	private static void validateSchema(JsonNode root) {
		requireObjectKeys(root, "$", ROOT_REQUIRED, Set.of());
		requireInteger(root.required("schemaVersion"), "$.schemaVersion");
		requireTextFields(root, "$", List.of(
				"fixtureId", "policyId", "split", "labelUnit", "sourcePolicy", "retrievedAt"));
		Instant.parse(root.required("retrievedAt").asString());

		JsonNode records = requireArray(root.required("records"), "$.records");
		for (int index = 0; index < records.size(); index++) {
			JsonNode record = records.get(index);
			String path = "$.records[" + index + "]";
			requireObjectKeys(record, path, RECORD_REQUIRED, Set.of());
			requireTextFields(record, path, List.of("key", "goldCluster", "caseFamily"));

			JsonNode provider = record.required("provider");
			requireObjectKeys(provider, path + ".provider", PROVIDER_REQUIRED, Set.of());
			requireTextFields(provider, path + ".provider", List.of("name", "recordId"));

			JsonNode paper = record.required("paper");
			requireObjectKeys(paper, path + ".paper", PAPER_REQUIRED, PAPER_OPTIONAL);
			requireTextFields(paper, path + ".paper", List.of(
					"title", "documentType", "language"));
			requireInteger(paper.required("publicationYear"), path + ".paper.publicationYear");
			for (String optional : PAPER_OPTIONAL) {
				if (paper.has(optional)) {
					requireText(paper.required(optional), path + ".paper." + optional);
				}
			}

			JsonNode identifiers = requireArray(
					paper.required("identifiers"), path + ".paper.identifiers");
			for (int identifierIndex = 0; identifierIndex < identifiers.size(); identifierIndex++) {
				JsonNode identifier = identifiers.get(identifierIndex);
				String identifierPath = path + ".paper.identifiers[" + identifierIndex + "]";
				requireObjectKeys(
						identifier, identifierPath, IDENTIFIER_REQUIRED, IDENTIFIER_OPTIONAL);
				requireTextFields(identifier, identifierPath, List.of("type", "value"));
				if (identifier.has("namespace")) {
					requireText(identifier.required("namespace"), identifierPath + ".namespace");
				}
			}

			JsonNode authors = requireArray(paper.required("authors"), path + ".paper.authors");
			for (int authorIndex = 0; authorIndex < authors.size(); authorIndex++) {
				JsonNode author = authors.get(authorIndex);
				String authorPath = path + ".paper.authors[" + authorIndex + "]";
				requireObjectKeys(author, authorPath, AUTHOR_REQUIRED, AUTHOR_OPTIONAL);
				requireText(author.required("displayName"), authorPath + ".displayName");
				requireInteger(author.required("position"), authorPath + ".position");
				requireBoolean(author.required("corresponding"), authorPath + ".corresponding");
				for (String optional : AUTHOR_OPTIONAL) {
					if (author.has(optional)) {
						requireText(author.required(optional), authorPath + "." + optional);
					}
				}
			}
		}

		JsonNode criticalPairs = requireArray(root.required("criticalPairs"), "$.criticalPairs");
		for (int index = 0; index < criticalPairs.size(); index++) {
			JsonNode pair = criticalPairs.get(index);
			String path = "$.criticalPairs[" + index + "]";
			requireObjectKeys(pair, path, CRITICAL_PAIR_REQUIRED, Set.of());
			requireTextFields(
					pair, path, List.of("left", "right", "relation", "caseFamily", "reason"));
		}

		JsonNode ingestOrders = requireArray(root.required("ingestOrders"), "$.ingestOrders");
		for (int index = 0; index < ingestOrders.size(); index++) {
			JsonNode order = ingestOrders.get(index);
			String path = "$.ingestOrders[" + index + "]";
			requireObjectKeys(order, path, INGEST_ORDER_REQUIRED, Set.of());
			requireText(order.required("key"), path + ".key");
			JsonNode recordKeys = requireArray(order.required("recordKeys"), path + ".recordKeys");
			for (int recordIndex = 0; recordIndex < recordKeys.size(); recordIndex++) {
				requireText(recordKeys.get(recordIndex), path + ".recordKeys[" + recordIndex + "]");
			}
		}
	}

	private void validateReferencesAndValues() {
		if (schemaVersion != 2) {
			throw new IllegalArgumentException("schemaVersion must be 2");
		}
		requireNonBlank(fixtureId, "fixtureId");
		requireNonBlank(policyId, "policyId");
		if (!"DEVELOPMENT".equals(split)) {
			throw new IllegalArgumentException("split must be DEVELOPMENT");
		}
		if (records.isEmpty()) {
			throw new IllegalArgumentException("records must not be empty");
		}

		Set<String> recordKeys = new LinkedHashSet<>();
		for (FixtureRecord record : records) {
			requireNonBlank(record.key(), "record key");
			requireNonBlank(record.goldCluster(), "goldCluster for " + record.key());
			requireNonBlank(record.caseFamily(), "caseFamily for " + record.key());
			if (!recordKeys.add(record.key())) {
				throw new IllegalArgumentException("Duplicate record key: " + record.key());
			}
			validatePaper(record);
		}
		Map<String, List<FixtureRecord>> recordsByProviderKey = records.stream()
				.collect(Collectors.groupingBy(record ->
						record.provider().name().strip().toLowerCase(Locale.ROOT)
								+ "\u0000" + record.provider().recordId().strip()));
		for (List<FixtureRecord> providerRecords : recordsByProviderKey.values()) {
			if (providerRecords.size() < 2) {
				continue;
			}
			Set<String> families = providerRecords.stream()
					.map(FixtureRecord::caseFamily)
					.collect(Collectors.toSet());
			Set<String> clusters = providerRecords.stream()
					.map(FixtureRecord::goldCluster)
					.collect(Collectors.toSet());
			if (!families.equals(Set.of("PROVIDER_REPLAY")) || clusters.size() != 1) {
				throw new IllegalArgumentException(
						"Provider record reuse requires one PROVIDER_REPLAY gold cluster: "
								+ providerRecords.stream().map(FixtureRecord::key).toList());
			}
		}
		Map<String, Set<String>> familiesByGoldCluster = records.stream()
				.collect(Collectors.groupingBy(
						FixtureRecord::goldCluster,
						Collectors.mapping(FixtureRecord::caseFamily, Collectors.toSet())));
		familiesByGoldCluster.forEach((cluster, families) -> {
			if (families.size() != 1) {
				throw new IllegalArgumentException(
						"Gold cluster must belong to one case family: " + cluster + "=" + families);
			}
		});

		Set<String> criticalKeys = new HashSet<>();
		for (CriticalPair pair : criticalPairs) {
			if (!recordKeys.contains(pair.left()) || !recordKeys.contains(pair.right())) {
				throw new IllegalArgumentException(
						"Critical pair references an unknown record: " + pair.left() + "/" + pair.right());
			}
			if (pair.left().equals(pair.right())) {
				throw new IllegalArgumentException("Critical pair must reference two records");
			}
			String pairKey = pair.left().compareTo(pair.right()) < 0
					? pair.left() + "\u0000" + pair.right()
					: pair.right() + "\u0000" + pair.left();
			if (!criticalKeys.add(pairKey)) {
				throw new IllegalArgumentException("Duplicate critical pair: " + pairKey);
			}
			FixtureRecord left = recordsByKey().get(pair.left());
			FixtureRecord right = recordsByKey().get(pair.right());
			if (!left.caseFamily().equals(pair.caseFamily())
					|| !right.caseFamily().equals(pair.caseFamily())) {
				throw new IllegalArgumentException(
						"Critical pair case family does not match both records: " + pairKey);
			}
			boolean sameGoldCluster = left.goldCluster().equals(right.goldCluster());
			if (pair.relation() == CriticalRelation.MUST_LINK && !sameGoldCluster) {
				throw new IllegalArgumentException("MUST_LINK pair crosses gold clusters: " + pairKey);
			}
			if (pair.relation() == CriticalRelation.MUST_SEPARATE && sameGoldCluster) {
				throw new IllegalArgumentException("MUST_SEPARATE pair shares a gold cluster: " + pairKey);
			}
		}

		if (ingestOrders.isEmpty()) {
			throw new IllegalArgumentException("ingestOrders must not be empty");
		}
		Set<String> orderKeys = new LinkedHashSet<>();
		for (IngestOrder order : ingestOrders) {
			if (!orderKeys.add(order.key())) {
				throw new IllegalArgumentException("Duplicate ingest-order key: " + order.key());
			}
			Set<String> orderRecords = new LinkedHashSet<>(order.recordKeys());
			if (orderRecords.size() != order.recordKeys().size()) {
				throw new IllegalArgumentException(
						"Ingest order contains duplicate records: " + order.key());
			}
			if (!orderRecords.equals(recordKeys)) {
				Set<String> missing = new LinkedHashSet<>(recordKeys);
				missing.removeAll(orderRecords);
				Set<String> unknown = new LinkedHashSet<>(orderRecords);
				unknown.removeAll(recordKeys);
				throw new IllegalArgumentException(
						"Ingest order must reference every record exactly once: " + order.key()
								+ " missing=" + missing + " unknown=" + unknown);
			}
		}
	}

	private static void validatePaper(FixtureRecord record) {
		FixturePaper paper = Objects.requireNonNull(record.paper(), "paper for " + record.key());
		if (paper.publicationYear() < 1000 || paper.publicationYear() > 9999) {
			throw new IllegalArgumentException("Invalid publicationYear for " + record.key());
		}
		if (paper.publicationDate() != null
				&& LocalDate.parse(paper.publicationDate()).getYear() != paper.publicationYear()) {
			throw new IllegalArgumentException(
					"publicationDate and publicationYear disagree for " + record.key());
		}
		Set<String> identifiers = new HashSet<>();
		for (FixtureIdentifier identifier : paper.identifiers()) {
			String namespace = identifier.namespace() == null ? "" : identifier.namespace().strip();
			if (identifier.type() == PaperIdentifierType.REPOSITORY && namespace.isEmpty()) {
				throw new IllegalArgumentException(
						"Repository identifier requires namespace for " + record.key());
			}
			if (identifier.type() != PaperIdentifierType.REPOSITORY && !namespace.isEmpty()) {
				throw new IllegalArgumentException(
						"Only repository identifiers may declare namespace for " + record.key());
			}
			String normalizedNamespace = namespace.toLowerCase(Locale.ROOT);
			String key = identifier.type() + "\u0000" + normalizedNamespace + "\u0000"
					+ PaperIdentifierNormalizer.normalize(identifier.type(), identifier.value());
			if (!identifiers.add(key)) {
				throw new IllegalArgumentException("Duplicate normalized identifier for " + record.key());
			}
		}
		Set<Integer> authorPositions = new HashSet<>();
		for (FixtureAuthor author : paper.authors()) {
			if (author.position() < 0 || !authorPositions.add(author.position())) {
				throw new IllegalArgumentException(
						"Author positions must be non-negative and unique for " + record.key());
			}
		}
	}

	private static void requireObjectKeys(
			JsonNode node, String path, Set<String> required, Set<String> optional) {
		if (!node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		Set<String> allowed = new HashSet<>(required);
		allowed.addAll(optional);
		Set<String> unknown = new LinkedHashSet<>(actual);
		unknown.removeAll(allowed);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown keys at " + path + ": " + unknown);
		}
		Set<String> missing = new LinkedHashSet<>(required);
		missing.removeAll(actual);
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Missing keys at " + path + ": " + missing);
		}
	}

	private static JsonNode requireArray(JsonNode node, String path) {
		if (!node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return node;
	}

	private static void requireTextFields(JsonNode node, String path, List<String> fields) {
		for (String field : fields) {
			requireText(node.required(field), path + "." + field);
		}
	}

	private static void requireText(JsonNode node, String path) {
		if (!node.isString() || node.asString().isBlank()) {
			throw new IllegalArgumentException(path + " must be a non-blank string");
		}
	}

	private static void requireInteger(JsonNode node, String path) {
		if (!node.isInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
	}

	private static void requireBoolean(JsonNode node, String path) {
		if (!node.isBoolean()) {
			throw new IllegalArgumentException(path + " must be a boolean");
		}
	}

	private static String requireNonBlank(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	record FixtureRecord(
			String key,
			String goldCluster,
			String caseFamily,
			FixtureProvider provider,
			FixturePaper paper) {
	}

	record FixtureProvider(String name, String recordId) {
	}

	record FixturePaper(
			String title,
			String abstractText,
			String publicationDate,
			int publicationYear,
			DocumentType documentType,
			String language,
			String venueName,
			List<FixtureIdentifier> identifiers,
			List<FixtureAuthor> authors) {

		FixturePaper {
			identifiers = List.copyOf(Objects.requireNonNull(identifiers, "identifiers"));
			authors = List.copyOf(Objects.requireNonNull(authors, "authors"));
		}
	}

	record FixtureIdentifier(PaperIdentifierType type, String namespace, String value) {
	}

	record FixtureAuthor(
			String openAlexId,
			String displayName,
			String orcid,
			int position,
			boolean corresponding) {
	}

	record CriticalPair(
			String left,
			String right,
			CriticalRelation relation,
			String caseFamily,
			String reason) {
	}

	enum CriticalRelation {
		MUST_LINK,
		MUST_SEPARATE
	}

	record IngestOrder(String key, List<String> recordKeys) {

		IngestOrder {
			recordKeys = List.copyOf(Objects.requireNonNull(recordKeys, "recordKeys"));
		}
	}
}
