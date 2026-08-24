package com.openscholar.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class OpenApiContractTests {

	private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
	private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
	private static final Pattern METHOD_MAPPING = Pattern.compile(
			"@(Get|Post|Put|Patch|Delete)Mapping\\s*(?:\\(([^)]*)\\))?");
	private static final Pattern FIRST_STRING = Pattern.compile("\"([^\"]*)\"");

	private static final Map<String, String> HOSTED_SCOPES = Map.ofEntries(
			Map.entry("POST /api/v1/searches", "openscholar.search"),
			Map.entry("POST /api/v1/searches/{searchId}/next", "openscholar.search"),
			Map.entry("GET /api/v1/searches/{searchId}", "openscholar.search"),
			Map.entry("GET /api/v1/papers/resolve", "openscholar.search"),
			Map.entry("POST /api/v1/papers/{paperId}/access/verify", "openscholar.search"),
			Map.entry("GET /api/v1/collections", "openscholar.library"),
			Map.entry("POST /api/v1/collections", "openscholar.library"),
			Map.entry("GET /api/v1/collections/{collectionId}", "openscholar.library"),
			Map.entry("PATCH /api/v1/collections/{collectionId}", "openscholar.library"),
			Map.entry("DELETE /api/v1/collections/{collectionId}", "openscholar.library"),
			Map.entry("PUT /api/v1/collections/{collectionId}/papers/{paperId}", "openscholar.library"),
			Map.entry("PATCH /api/v1/collections/{collectionId}/papers/{paperId}", "openscholar.library"),
			Map.entry("DELETE /api/v1/collections/{collectionId}/papers/{paperId}", "openscholar.library"),
			Map.entry("GET /api/v1/library/papers", "openscholar.library"),
			Map.entry("GET /api/v1/refresh-jobs", "openscholar.jobs"),
			Map.entry("POST /api/v1/refresh-jobs", "openscholar.jobs"),
			Map.entry("GET /api/v1/refresh-jobs/{jobId}", "openscholar.jobs"),
			Map.entry("POST /api/v1/refresh-jobs/{jobId}/retry", "openscholar.jobs"),
			Map.entry("GET /api/v1/privacy/export", "openscholar.privacy"),
			Map.entry("DELETE /api/v1/privacy/account", "openscholar.privacy"));

	@Test
	void staticSpecificationIsParseableCompleteAndScopeAccurate() throws IOException {
		Path repository = repositoryRoot();
		Map<String, Object> document = loadYaml(repository.resolve("docs/openapi.yaml"));

		assertThat(document.get("openapi")).isEqualTo("3.1.0");
		Map<String, Object> paths = map(document.get("paths"), "paths");
		Map<String, Map<String, Object>> operations = operations(paths);

		assertThat(operations.keySet())
				.as("documented operations must match every current /api/v1 controller mapping")
				.containsExactlyInAnyOrderElementsOf(controllerOperations(repository));
		assertThat(operations).hasSize(26);

		Set<String> operationIds = new HashSet<>();
		operations.forEach((endpoint, operation) -> {
			assertThat(operation.get("operationId")).as(endpoint + " operationId").isInstanceOf(String.class);
			assertThat((String) operation.get("operationId")).as(endpoint + " operationId").isNotBlank();
			assertThat(operationIds.add((String) operation.get("operationId")))
					.as(endpoint + " operationId must be unique").isTrue();
			assertThat(operation.get("summary")).as(endpoint + " summary").isInstanceOf(String.class);
			assertThat(operation.get("tags")).as(endpoint + " tags").isInstanceOf(List.class);

			Map<String, Object> responses = map(operation.get("responses"), endpoint + " responses");
			assertThat(responses.keySet()).as(endpoint + " success response")
					.anyMatch(status -> status.matches("2\\d\\d"));
			assertSecurity(endpoint, operation, responses);
		});

		assertLocalReferencesResolve(document, document);
	}

	private static void assertSecurity(
			String endpoint, Map<String, Object> operation, Map<String, Object> responses) {
		assertThat(operation).as(endpoint + " explicit security decision").containsKey("security");
		Object value = operation.get("security");
		assertThat(value).as(endpoint + " security").isInstanceOf(List.class);
		List<?> security = (List<?>) value;
		String requiredScope = HOSTED_SCOPES.get(endpoint);
		if (requiredScope == null) {
			assertThat(security).as(endpoint + " is public in hosted mode").isEmpty();
			return;
		}

		assertThat(security).as(endpoint + " hosted security requirement").hasSize(1);
		Map<String, Object> requirement = map(security.getFirst(), endpoint + " security requirement");
		assertThat(requirement).containsOnlyKeys("HostedOidc");
		assertThat(requirement.get("HostedOidc")).isEqualTo(List.of(requiredScope));
		assertThat(responses).as(endpoint + " principal responses").containsKeys("401", "403");
	}

	private static Map<String, Map<String, Object>> operations(Map<String, Object> paths) {
		Map<String, Map<String, Object>> operations = new LinkedHashMap<>();
		paths.forEach((path, pathValue) -> {
			assertThat(path).startsWith("/api/v1/");
			Map<String, Object> pathItem = map(pathValue, path);
			pathItem.forEach((method, operationValue) -> {
				if (!HTTP_METHODS.contains(method)) {
					return;
				}
				String endpoint = method.toUpperCase(Locale.ROOT) + " " + path;
				assertThat(operations.put(endpoint, map(operationValue, endpoint)))
						.as("duplicate " + endpoint).isNull();
			});
		});
		return operations;
	}

	private static Set<String> controllerOperations(Path repository) throws IOException {
		Path sourceRoot = repository.resolve("backend/src/main/java");
		Set<String> endpoints = new TreeSet<>();
		try (Stream<Path> files = Files.walk(sourceRoot)) {
			for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String java = Files.readString(source);
				if (!java.contains("@RestController") || !java.contains("/api/v1/")) {
					continue;
				}
				Matcher baseMatcher = CLASS_MAPPING.matcher(java);
				assertThat(baseMatcher.find()).as(source + " class request mapping").isTrue();
				String base = baseMatcher.group(1);
				Matcher methodMatcher = METHOD_MAPPING.matcher(java);
				while (methodMatcher.find()) {
					String suffix = "";
					String arguments = methodMatcher.group(2);
					if (arguments != null) {
						Matcher string = FIRST_STRING.matcher(arguments);
						if (string.find()) {
							suffix = string.group(1);
						}
					}
					endpoints.add(methodMatcher.group(1).toUpperCase(Locale.ROOT) + " " + base + suffix);
				}
			}
		}
		return endpoints;
	}

	private static void assertLocalReferencesResolve(Object value, Map<String, Object> document) {
		if (value instanceof Map<?, ?> values) {
			Object reference = values.get("$ref");
			if (reference != null) {
				assertThat(reference).isInstanceOf(String.class);
				String pointer = (String) reference;
				assertThat(pointer).as("only repository-local OpenAPI references are allowed").startsWith("#/");
				assertThat(resolvePointer(document, pointer)).as(pointer).isNotNull();
			}
			values.values().forEach(child -> assertLocalReferencesResolve(child, document));
		}
		else if (value instanceof List<?> values) {
			values.forEach(child -> assertLocalReferencesResolve(child, document));
		}
	}

	private static Object resolvePointer(Map<String, Object> document, String pointer) {
		Object current = document;
		for (String token : pointer.substring(2).split("/")) {
			if (!(current instanceof Map<?, ?> values)) {
				return null;
			}
			current = values.get(token.replace("~1", "/").replace("~0", "~"));
		}
		return current;
	}

	private static Map<String, Object> loadYaml(Path specification) throws IOException {
		LoaderOptions options = new LoaderOptions();
		options.setAllowDuplicateKeys(false);
		options.setMaxAliasesForCollections(0);
		Yaml yaml = new Yaml(new SafeConstructor(options));
		try (InputStream input = Files.newInputStream(specification)) {
			return map(yaml.load(input), "OpenAPI root");
		}
	}

	private static Path repositoryRoot() {
		Path current = Path.of("").toAbsolutePath().normalize();
		List<Path> candidates = new ArrayList<>(List.of(current));
		if (current.getParent() != null) {
			candidates.add(current.getParent());
		}
		return candidates.stream()
				.filter(path -> Files.isRegularFile(path.resolve("docs/openapi.yaml")))
				.filter(path -> Files.isDirectory(path.resolve("backend/src/main/java")))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Could not locate the repository root"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value, String label) {
		assertThat(value).as(label).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}
}
