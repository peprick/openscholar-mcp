package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.provider.europepmc.EuropePmcProperties;
import com.openscholar.provider.openalex.OpenAlexProperties;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ComparativeCapture;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ProviderCallEvidence;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.QueryCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Manual, opt-in live evidence capture. Ordinary tests never create this
 * context and never contact a scholarly provider.
 */
@EnabledIfEnvironmentVariable(
		named = "RUN_PROVIDER_QUALITY_COMPARATIVE_CAPTURE",
		matches = "true")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"openscholar.providers.openalex.base-url=https://api.openalex.org",
				"openscholar.providers.openalex.api-key=",
				"openscholar.providers.openalex.connect-timeout=3s",
				"openscholar.providers.openalex.request-timeout=10s",
				"openscholar.providers.openalex.max-response-bytes=8388608",
				"openscholar.providers.europe-pmc.enabled=true",
				"openscholar.providers.europe-pmc.base-url=https://www.ebi.ac.uk/europepmc/webservices/rest",
				"openscholar.providers.europe-pmc.connect-timeout=3s",
				"openscholar.providers.europe-pmc.request-timeout=10s",
				"openscholar.providers.europe-pmc.max-response-bytes=8388608",
				"openscholar.providers.core.enabled=false",
				"openscholar.providers.datacite.enabled=false",
				"openscholar.providers.doaj.enabled=false",
				"openscholar.jobs.refresh.worker-enabled=false",
				"openscholar.jobs.refresh.scheduled-enqueue-enabled=false"
		})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EuropePmcComparativeLiveEvaluationTests {

	private static final String OPENALEX_BASE_URL = "https://api.openalex.org";
	private static final String EUROPE_PMC_BASE_URL =
			"https://www.ebi.ac.uk/europepmc/webservices/rest";
	private static final String EXPECTED_QUERY_SET_SHA256 =
			"125783646c293b254eaeda633e15a40fd72aeded4b45effeef2b1ecaaafe36d1";
	private static final Pattern COMMIT_REVISION = Pattern.compile("^[0-9a-f]{40}(?:[0-9a-f]{24})?$");
	private static final Pattern EVIDENCE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{2,127}$");
	private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
	private static final byte[] BLINDED_ORDER_DOMAIN =
			"openscholar-provider-quality-blinded-order-v1"
					.getBytes(StandardCharsets.UTF_8);
	private static final long MAXIMUM_EVIDENCE_BYTES = 64L * 1024L * 1024L;
	private static final int MAXIMUM_GIT_OUTPUT_BYTES = 64 * 1024;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SearchSnapshotStore snapshotStore;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private List<ResearchProvider> researchProviders;

	@Autowired
	private OpenAlexProperties openAlexProperties;

	@Autowired
	private EuropePmcProperties europePmcProperties;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void capturesEachProviderOnceAndReplaysTheSameMetadataThroughEveryScenario()
			throws Exception {
		Path repositoryRoot = repositoryRoot();
		String repositoryRevision = requiredRepositoryRevision(repositoryRoot);
		assertProviderConfigurationIsPinned();
		assertDisposableDatabaseIsEmpty();

		ProviderQualityLiveQuerySet querySet = ProviderQualityLiveQuerySet.load(
				objectMapper, ProviderQualityLiveQuerySet.RESOURCE_PATH);
		String querySetSha256 = classpathSha256(ProviderQualityLiveQuerySet.RESOURCE_PATH);
		assertThat(querySetSha256).isEqualTo(EXPECTED_QUERY_SET_SHA256);

		Instant measuredAt = Instant.now();
		ComparativeCapture capture = new ProviderQualityComparativeEvaluator(
				snapshotStore, transactionManager).capture(querySet, researchProviders);
		assertDisposableDatabaseIsEmpty();

		String evidenceId = "europe-pmc-comparative-"
				+ measuredAt.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
						.replaceAll("-+$", "");
		Map<String, Object> artifacts = artifacts(
				evidenceId, measuredAt, repositoryRevision, querySetSha256, capture);
		ProviderQualityEvidenceWriter.WriteResult written =
				ProviderQualityEvidenceWriter.forRepository(
						objectMapper, repositoryRoot, MAXIMUM_EVIDENCE_BYTES)
						.write(evidenceId, artifacts);

		System.out.printf(
				Locale.ROOT,
				"provider-quality-comparative-v1 evidence=%s revision=%s queries=%d "
						+ "quality-review-eligible=%s bytes=%d%n",
				written.directory(),
				repositoryRevision,
				capture.queries().size(),
				capture.qualityReviewEligible(),
				written.totalBytes());
		assertThat(capture.qualityReviewEligible())
				.as("both providers must complete every query; retain an incomplete artifact only as diagnostics")
				.isTrue();
	}

	static Map<String, Object> artifacts(
			String evidenceId,
			Instant measuredAt,
			String repositoryRevision,
			String querySetSha256,
			ComparativeCapture capture) {
		List<ProviderCallEvidence> calls = capture.queries().stream()
				.flatMap(query -> query.providerCalls().stream())
				.toList();
		Map<ProviderId, Long> requests = new LinkedHashMap<>();
		Map<ProviderId, Long> failures = new LinkedHashMap<>();
		for (ProviderId provider : List.of(ProviderId.OPENALEX, ProviderId.EUROPE_PMC)) {
			requests.put(provider, calls.stream().filter(call -> call.provider() == provider).count());
			failures.put(provider, calls.stream()
					.filter(call -> call.provider() == provider && "FAILED".equals(call.status()))
					.count());
		}

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("schemaVersion", 1);
		summary.put("evidenceType", "LIVE_COMPARATIVE_METADATA_CAPTURE");
		summary.put("evidenceId", evidenceId);
		summary.put("measuredAt", measuredAt.toString());
		summary.put("repositoryRevision", repositoryRevision);
		summary.put("querySet", Map.of(
				"id", capture.querySetId(),
				"sha256", querySetSha256,
				"sourcePolicy", capture.sourcePolicy(),
				"pageSize", capture.pageSize()));
		summary.put("providerConfiguration", Map.of(
				ProviderId.OPENALEX.name(), Map.of(
						"baseUrl", OPENALEX_BASE_URL,
						"apiKeyConfigured", false,
						"maxResponseBytes", 8_388_608),
				ProviderId.EUROPE_PMC.name(), Map.of(
						"baseUrl", EUROPE_PMC_BASE_URL,
						"maxResponseBytes", 8_388_608)));
		summary.put("boundaries", Map.of(
				"metadataOnly", true,
				"firstPageOnly", true,
				"providerFetchesPerProviderQuery", 1,
				"fetchesPdf", false,
				"fetchesFullText", false,
				"fetchesSupplementaryFiles", false,
				"serializesPdfUrl", false,
				"mutatesUserCatalog", false,
				"readerFacing", false,
				"defaultEnablementDecision", false));
		summary.put("qualityReviewEligible", capture.qualityReviewEligible());
		summary.put("providerRequests", requests);
		summary.put("providerFailures", failures);
		summary.put("queries", capture.queries().stream()
				.map(EuropePmcComparativeLiveEvaluationTests::querySummary)
				.toList());

		Map<String, Object> blinded = Map.of(
				"schemaVersion", 1,
				"evidenceId", evidenceId,
				"qualityReviewEligible", capture.qualityReviewEligible(),
				"instructions", capture.qualityReviewEligible()
						? "Assign one integer relevanceGrade from 0 through 3 without consulting provenance-map.json or reconciliation-trace.json."
						: "Do not label this incomplete capture; inspect summary.json and repeat the isolated run.",
				"candidates", capture.queries().stream()
						.flatMap(query -> query.rawCandidates().stream()
								.sorted(Comparator.comparing(candidate ->
										blindedOrderingKey(evidenceId, candidate.reviewKey()))))
						.map(EuropePmcComparativeLiveEvaluationTests::blindedCandidate)
						.toList());
		Map<String, Object> provenance = Map.of(
				"schemaVersion", 1,
				"evidenceId", evidenceId,
				"warning", "Keep this file hidden during blinded relevance grading.",
				"candidates", capture.queries().stream()
						.flatMap(query -> query.rawCandidates().stream())
						.toList());
		Map<String, Object> reconciliation = Map.of(
				"schemaVersion", 1,
				"evidenceId", evidenceId,
				"warning", "These are candidate merge decisions, not duplicate ground truth.",
				"queries", capture.queries().stream()
						.map(query -> Map.of(
								"queryKey", query.key(),
								"complete", query.complete(),
								"scenarios", query.scenarios()))
						.toList());

		Map<String, Object> artifacts = new LinkedHashMap<>();
		artifacts.put("summary.json", summary);
		artifacts.put("blinded-candidates.json", blinded);
		artifacts.put("provenance-map.json", provenance);
		artifacts.put("reconciliation-trace.json", reconciliation);
		return artifacts;
	}

	private static Map<String, Object> querySummary(QueryCapture query) {
		return Map.of(
				"queryKey", query.key(),
				"complete", query.complete(),
				"rawCandidateCount", query.rawCandidates().size(),
				"providerCalls", query.providerCalls(),
				"scenarioResultCounts", query.scenarios().entrySet().stream()
						.collect(java.util.stream.Collectors.toMap(
								entry -> entry.getKey().name(),
								entry -> entry.getValue().rankedResults().size())));
	}

	private static Map<String, Object> blindedCandidate(ProviderQualityRawCandidate candidate) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("reviewKey", candidate.reviewKey());
		value.put("queryKey", candidate.queryKey());
		value.put("title", candidate.title());
		value.put("abstractText", candidate.abstractText());
		value.put("publicationDate", candidate.publicationDate());
		value.put("publicationYear", candidate.publicationYear());
		value.put("documentType", candidate.documentType());
		value.put("language", candidate.language());
		value.put("venueName", candidate.venueName());
		value.put("authors", candidate.authors().stream()
				.map(author -> Map.of(
						"displayName", author.displayName(),
						"position", author.position(),
						"corresponding", author.corresponding()))
				.toList());
		return value;
	}

	static String blindedOrderingKey(String evidenceId, String reviewKey) {
		if (evidenceId == null || !EVIDENCE_ID.matcher(evidenceId).matches()) {
			throw new IllegalArgumentException("evidenceId is not a bounded safe identifier");
		}
		if (reviewKey == null || !SHA256_HEX.matcher(reviewKey).matches()) {
			throw new IllegalArgumentException("reviewKey is not a SHA-256 identifier");
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(BLINDED_ORDER_DOMAIN);
			digest.update((byte) 0);
			digest.update(evidenceId.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(reviewKey.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private void assertDisposableDatabaseIsEmpty() {
		for (String table : List.of("search_result", "search_snapshot", "provider_record", "paper")) {
			Long count = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
			assertThat(count).as("disposable evaluation table %s", table).isZero();
		}
	}

	private void assertProviderConfigurationIsPinned() {
		assertThat(openAlexProperties.baseUrl()).hasToString(OPENALEX_BASE_URL);
		assertThat(openAlexProperties.apiKey()).isNull();
		assertThat(openAlexProperties.maxResponseBytes()).isEqualTo(8_388_608);
		assertThat(europePmcProperties.baseUrl()).hasToString(EUROPE_PMC_BASE_URL);
		assertThat(europePmcProperties.maxResponseBytes()).isEqualTo(8_388_608);
	}

	private static String requiredRepositoryRevision(Path repositoryRoot) throws Exception {
		String value = System.getenv("OPENSCHOLAR_PROVIDER_QUALITY_REVISION");
		if (value == null || !COMMIT_REVISION.matcher(value).matches()) {
			throw new IllegalStateException(
					"OPENSCHOLAR_PROVIDER_QUALITY_REVISION must be the lowercase committed Git revision under evaluation");
		}
		String actualRevision = gitOutput(
				repositoryRoot, "rev-parse", "--verify", "HEAD^{commit}").strip();
		String porcelainStatus = gitOutput(
				repositoryRoot, "status", "--porcelain=v1", "--untracked-files=all");
		return verifyRepositoryState(value, actualRevision, porcelainStatus);
	}

	static String verifyRepositoryState(
			String claimedRevision, String actualRevision, String porcelainStatus) {
		if (claimedRevision == null || !COMMIT_REVISION.matcher(claimedRevision).matches()) {
			throw new IllegalStateException(
					"The claimed provider-quality revision is not a lowercase Git commit ID");
		}
		if (actualRevision == null || !COMMIT_REVISION.matcher(actualRevision).matches()) {
			throw new IllegalStateException("Could not resolve the checked-out Git commit");
		}
		if (!claimedRevision.equals(actualRevision)) {
			throw new IllegalStateException(
					"OPENSCHOLAR_PROVIDER_QUALITY_REVISION must match the checked-out Git commit");
		}
		if (porcelainStatus == null || !porcelainStatus.isEmpty()) {
			throw new IllegalStateException(
					"Provider-quality evidence must be captured from a clean Git worktree");
		}
		return claimedRevision;
	}

	private static String gitOutput(Path repositoryRoot, String... arguments) throws Exception {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.add("-C");
		command.add(repositoryRoot.toString());
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
		builder.environment().put("LC_ALL", "C");
		Process process = builder.start();
		if (!process.waitFor(10, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			process.waitFor();
			throw new IllegalStateException("Git repository verification timed out");
		}
		byte[] output;
		try (InputStream input = process.getInputStream()) {
			output = input.readNBytes(MAXIMUM_GIT_OUTPUT_BYTES + 1);
		}
		if (process.exitValue() != 0 || output.length > MAXIMUM_GIT_OUTPUT_BYTES) {
			throw new IllegalStateException("Git repository verification failed");
		}
		return new String(output, StandardCharsets.UTF_8);
	}

	private static Path repositoryRoot() {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
			if (Files.isRegularFile(candidate.resolve("backend/pom.xml"))
					&& Files.isRegularFile(candidate.resolve("README.md"))) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not locate the OpenScholar repository root");
	}

	private static String classpathSha256(String resourcePath) throws Exception {
		ClassPathResource resource = new ClassPathResource(resourcePath);
		try (InputStream input = resource.getInputStream()) {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
		}
	}
}
