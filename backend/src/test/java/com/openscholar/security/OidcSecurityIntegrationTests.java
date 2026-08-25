package com.openscholar.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"management.server.address=127.0.0.1",
	"management.server.port=0",
	"openscholar.security.oidc.enabled=true",
	"openscholar.security.oidc.issuer-uri=https://issuer.example/tenant",
	"openscholar.security.oidc.jwk-set-uri=https://issuer.example/tenant/jwks",
	"openscholar.security.oidc.audience=https://research.example/mcp",
	"openscholar.security.oidc.mcp-resource-uri=https://research.example/mcp"
})
class OidcSecurityIntegrationTests {

	private static final String ISSUER = "https://issuer.example/tenant";
	private static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@LocalManagementPort
	private int managementPort;

	@BeforeEach
	@AfterEach
	void removeHostedFixtures() {
		jdbcTemplate.update("DELETE FROM research_refresh_job");
		jdbcTemplate.update("DELETE FROM search_snapshot");
		jdbcTemplate.update("DELETE FROM library_collection");
		jdbcTemplate.update("DELETE FROM app_user WHERE id <> ?", LOCAL_USER_ID);
	}

	@Test
	void enforcesTheHostedScopeMatrixAndPublishesMcpOAuthDiscovery() throws Exception {
		mockMvc.perform(get("/api/v1/system/status"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isNotFound());
		assertManagementMetricsAreScrapeable();
		mockMvc.perform(get("/api/v1/papers/{paperId}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/papers/resolve")
						.queryParam("identifier", "10.1000/missing"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/papers/resolve")
						.queryParam("identifier", "10.1000/missing")
						.with(user("alice", "Alice", "openscholar.library")))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/papers/resolve")
						.queryParam("identifier", "10.1000/missing")
						.with(user("alice", "Alice", "openscholar.search")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PAPER_IDENTIFIER_NOT_FOUND"));

		mockMvc.perform(get("/api/v1/collections"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/collections").with(user("alice", "Alice", "openscholar.search")))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/collections").with(user("alice", "Alice", "openscholar.library")))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/searches/{searchId}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/searches/{searchId}", UUID.randomUUID())
						.with(user("alice", "Alice", "openscholar.search")))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/privacy/export")
						.with(user("alice", "Alice", "openscholar.library")))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/mcp"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
						containsString("resource_metadata=\"https://research.example/.well-known/oauth-protected-resource/mcp\"")))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
						containsString("scope=\"openscholar.mcp\"")));
		mockMvc.perform(post("/mcp").with(user("alice", "Alice", "openscholar.library")))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
						containsString("error=\"insufficient_scope\"")));

		mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(jsonPath("$.resource").value("https://research.example/mcp"))
				.andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
				.andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
				.andExpect(jsonPath("$.scopes_supported[0]").value("openscholar.mcp"));
	}

	private void assertManagementMetricsAreScrapeable() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
				.GET()
				.build();
		HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("content-type"))
				.hasValueSatisfying(contentType -> assertThat(contentType).startsWith("text/plain"));
		assertThat(response.body()).contains("jvm_");
	}

	@Test
	void isolatesCollectionsAndSearchSnapshotsByIssuerAndSubject() throws Exception {
		RequestPostProcessor alice = user("alice-subject", "Alice", "openscholar.library");
		RequestPostProcessor bob = user("bob-subject", "Bob", "openscholar.library");
		String created = mockMvc.perform(post("/api/v1/collections")
						.with(alice)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Alice papers\",\"description\":null}"))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		UUID collectionId = UUID.fromString(objectMapper.readTree(created).required("collectionId").asString());

		mockMvc.perform(get("/api/v1/collections").with(alice))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
		mockMvc.perform(get("/api/v1/collections").with(bob))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(get("/api/v1/collections/{collectionId}", collectionId).with(bob))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/collections/{collectionId}/offline-pack", collectionId).with(bob))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COLLECTION_NOT_FOUND"));
		mockMvc.perform(get("/api/v1/collections/{collectionId}/offline-pack", collectionId).with(alice))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.collection.collectionId").value(collectionId.toString()));

		UUID aliceId = userId("alice-subject");
		UUID bobId = userId("bob-subject");
		assertThat(aliceId).isNotEqualTo(bobId);
		UUID aliceSearch = insertSearch(aliceId, "alice private query", "c".repeat(64));
		insertSearch(bobId, "bob private query", "c".repeat(64));

		mockMvc.perform(get("/api/v1/searches/{searchId}", aliceSearch)
						.with(user("alice-subject", "Alice Updated", "openscholar.search")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.query").value("alice private query"));
		mockMvc.perform(get("/api/v1/searches/{searchId}", aliceSearch)
						.with(user("bob-subject", "Bob", "openscholar.search")))
				.andExpect(status().isNotFound());
		assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM app_user WHERE id = ?", String.class, aliceId))
				.isEqualTo("Alice Updated");
	}

	@Test
	void exportsOnlyTheHostedPrincipalsOwnedDataOnTheRequestThread() throws Exception {
		mockMvc.perform(get("/api/v1/collections")
						.with(user("alice-export", "Alice Export", "openscholar.library")))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/collections")
						.with(user("bob-export", "Bob Export", "openscholar.library")))
				.andExpect(status().isOk());
		UUID aliceId = userId("alice-export");
		UUID bobId = userId("bob-export");
		insertSearch(aliceId, "alice export marker", "1".repeat(64));
		insertSearch(bobId, "bob export secret marker", "2".repeat(64));

		MvcResult result = mockMvc.perform(get("/api/v1/privacy/export")
						.with(user("alice-export", "Alice Export Updated", "openscholar.privacy")))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-transform"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andReturn();

		byte[] body = result.getResponse().getContentAsByteArray();
		assertThat(result.getResponse().getContentLengthLong()).isEqualTo(body.length);
		JsonNode export = objectMapper.readTree(body);
		assertThat(export.required("userId").asString()).isEqualTo(aliceId.toString());
		assertThat(export.required("displayName").asString()).isEqualTo("Alice Export Updated");
		assertThat(export.required("searches")).hasSize(1);
		String payload = new String(body, StandardCharsets.UTF_8);
		assertThat(payload)
				.contains("alice export marker")
				.doesNotContain("bob export secret marker", "alice-export", "bob-export", ISSUER);
	}

	@Test
	void propagatesHostedOwnerThroughImmediateMcpCollectionResourceReads() throws Exception {
		String collectionName = "Alice MCP resource " + UUID.randomUUID();
		String created = mockMvc.perform(post("/api/v1/collections")
						.with(user("alice-mcp-resource", "Alice", "openscholar.library"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of(
								"name", collectionName,
								"description", "Hosted owner propagation test"))))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		UUID collectionId = UUID.fromString(objectMapper.readTree(created).required("collectionId").asString());
		UUID missingCollectionId = UUID.randomUUID();
		String collectionUri = "openscholar://collections/" + collectionId;

		JsonNode owned = readMcpResource(201, collectionUri,
				user("alice-mcp-resource", "Alice via MCP", "openscholar.mcp"));
		assertThat(owned.has("error")).isFalse();
		JsonNode contents = owned.required("result").required("contents");
		assertThat(contents).hasSize(1);
		JsonNode ownedContent = contents.required(0);
		assertThat(ownedContent.required("uri").asString()).isEqualTo(collectionUri);
		assertThat(ownedContent.required("mimeType").asString()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		JsonNode ownedPayload = objectMapper.readTree(ownedContent.required("text").asString());
		assertThat(ownedPayload.toString()).contains(collectionId.toString(), collectionName);

		RequestPostProcessor bob = user("bob-mcp-resource", "Bob", "openscholar.mcp");
		JsonNode forbidden = readMcpResource(202, collectionUri, bob);
		JsonNode missing = readMcpResource(203,
				"openscholar://collections/" + missingCollectionId, bob);

		assertThat(forbidden.has("result")).isFalse();
		assertThat(missing.has("result")).isFalse();
		assertThat(forbidden.required("error")).isEqualTo(missing.required("error"));
		assertThat(forbidden.required("error").required("code").asInt()).isEqualTo(-32002);
		assertThat(forbidden.required("error").required("message").asString()).isEqualTo("Resource not found");
		assertThat(forbidden.toString()).doesNotContain(collectionId.toString(), collectionName);
		assertThat(missing.toString()).doesNotContain(missingCollectionId.toString(), collectionName);
	}

	@Test
	void localCatalogSearchCannotRevealAnotherOwnersKnownPapers() throws Exception {
		RequestPostProcessor alice = user("alice-local", "Alice", "openscholar.search");
		RequestPostProcessor bob = user("bob-local", "Bob", "openscholar.search");
		mockMvc.perform(get("/api/v1/searches/{searchId}", UUID.randomUUID()).with(alice))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/searches/{searchId}", UUID.randomUUID()).with(bob))
				.andExpect(status().isNotFound());

		UUID paperId = UUID.randomUUID();
		UUID collectionId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		String title = "Alice private local catalog paper "
				+ UUID.randomUUID().toString().replace("-", "");
		try {
			jdbcTemplate.update("""
					INSERT INTO paper (
					    id, title, normalized_title, document_type, metadata_quality,
					    metadata_updated_at, version, created_at, updated_at
					)
					VALUES (?, ?, lower(?), 'ARTICLE', 0, ?, 0, ?, ?)
					""", paperId, title, title, now, now, now);
			jdbcTemplate.update("""
					INSERT INTO provider_record (
					    id, paper_id, provider, provider_record_id, retrieved_at,
					    reported_open_access, metadata_fragment, created_at, updated_at
					)
					VALUES (?, ?, 'openalex', ?, ?, true, '{}'::jsonb, ?, ?)
					""", UUID.randomUUID(), paperId, "W-PRIVATE-" + paperId, now, now, now);
			jdbcTemplate.update("""
					INSERT INTO library_collection (
					    id, owner_id, name, version, created_at, updated_at
					)
					VALUES (?, ?, 'Alice private papers', 0, ?, ?)
					""", collectionId, userId("alice-local"), now, now);
			jdbcTemplate.update("""
					INSERT INTO collection_paper (
					    id, collection_id, paper_id, reading_status, version, saved_at, updated_at
					)
					VALUES (?, ?, ?, 'UNREAD', 0, ?, ?)
					""", UUID.randomUUID(), collectionId, paperId, now, now);

			String localRequest = """
					{"query":"%s","mode":"LOCAL","pageSize":20}
					""".formatted(title);
			mockMvc.perform(post("/api/v1/searches")
						.with(bob)
						.contentType(MediaType.APPLICATION_JSON)
						.content(localRequest))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.executionSource").value("LOCAL_CATALOG"))
					.andExpect(jsonPath("$.results").isEmpty());
			mockMvc.perform(post("/api/v1/searches")
						.with(alice)
						.contentType(MediaType.APPLICATION_JSON)
						.content(localRequest))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.results[0].paperId").value(paperId.toString()));
		}
		finally {
			jdbcTemplate.update("DELETE FROM library_collection WHERE id = ?", collectionId);
			jdbcTemplate.update("""
					DELETE FROM search_snapshot
					WHERE id IN (SELECT search_id FROM search_result WHERE paper_id = ?)
					""", paperId);
			jdbcTemplate.update("DELETE FROM paper WHERE id = ?", paperId);
		}
	}

	@Test
	void deletionErasesOnlyTheCurrentPrincipalAndAValidTokenReprovisionsAnEmptyAccount() throws Exception {
		mockMvc.perform(post("/api/v1/collections")
						.with(user("alice-delete", "Alice", "openscholar.library"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Delete me\",\"description\":null}"))
				.andExpect(status().isCreated());
		mockMvc.perform(get("/api/v1/collections")
						.with(user("bob-keep", "Bob", "openscholar.library")))
				.andExpect(status().isOk());
		UUID aliceId = userId("alice-delete");
		UUID bobId = userId("bob-keep");
		insertSearch(aliceId, "delete this query", "d".repeat(64));

		mockMvc.perform(delete("/api/v1/privacy/account")
						.with(user("alice-delete", "Alice", "openscholar.privacy"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"confirmation\":\"DELETE_MY_DATA\"}"))
				.andExpect(status().isNoContent());

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, aliceId))
				.isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, bobId))
				.isOne();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM search_snapshot WHERE owner_id = ?", Integer.class,
				aliceId)).isZero();

		mockMvc.perform(get("/api/v1/collections")
						.with(user("alice-delete", "Alice", "openscholar.library")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
		UUID reprovisionedId = userId("alice-delete");
		assertThat(reprovisionedId).isNotEqualTo(aliceId);
	}

	@Test
	void rejectsSubjectsThatCannotBeStoredWithoutChangingIdentity() throws Exception {
		mockMvc.perform(get("/api/v1/collections")
						.with(user("s".repeat(256), "Oversized", "openscholar.library")))
				.andExpect(status().isForbidden());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM app_user WHERE identity_issuer = ?", Integer.class, ISSUER)).isZero();
	}

	@Test
	void scopesSearchRefreshJobsToTheirSearchOwner() throws Exception {
		RequestPostProcessor alice = user("alice-jobs", "Alice", "openscholar.jobs");
		RequestPostProcessor bob = user("bob-jobs", "Bob", "openscholar.jobs");
		mockMvc.perform(get("/api/v1/refresh-jobs").with(alice))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/refresh-jobs").with(bob))
				.andExpect(status().isOk());
		UUID aliceSearch = insertSearch(userId("alice-jobs"), "alice job query", "e".repeat(64));
		UUID jobId = insertFailedSearchJob(aliceSearch);

		mockMvc.perform(get("/api/v1/refresh-jobs/{jobId}", jobId).with(alice))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targetId").value(aliceSearch.toString()));
		mockMvc.perform(get("/api/v1/refresh-jobs").with(bob))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(get("/api/v1/refresh-jobs/{jobId}", jobId).with(bob))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/refresh-jobs/{jobId}/retry", jobId).with(bob))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/refresh-jobs/{jobId}/retry", jobId).with(alice))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	private RequestPostProcessor user(String subject, String name, String scope) {
		return jwt()
				.jwt(builder -> builder
						.issuer(ISSUER)
						.subject(subject)
						.claim("name", name)
						.audience(java.util.List.of("https://research.example/mcp"))
						.issuedAt(Instant.now().minusSeconds(5))
						.expiresAt(Instant.now().plusSeconds(300)))
				.authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
	}

	private JsonNode readMcpResource(int id, String uri, RequestPostProcessor principal) throws Exception {
		String request = objectMapper.writeValueAsString(Map.of(
				"jsonrpc", "2.0",
				"id", id,
				"method", "resources/read",
				"params", Map.of("uri", uri)));
		MvcResult result = mockMvc.perform(post("/mcp")
						.with(principal)
						.header("MCP-Protocol-Version", "2025-11-25")
						.contentType(MediaType.APPLICATION_JSON)
						.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
						.content(request))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(response.required("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(response.required("id").asInt()).isEqualTo(id);
		return response;
	}

	private UUID userId(String subject) {
		return jdbcTemplate.queryForObject("""
				SELECT id FROM app_user
				WHERE identity_issuer = ? AND identity_subject = ?
				""", UUID.class, ISSUER, subject);
	}

	private UUID insertSearch(UUID ownerId, String query, String fingerprint) {
		UUID searchId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO search_snapshot (
				    id, owner_id, original_query, normalized_query, fingerprint, fingerprint_version,
				    pipeline_version, filters, status, searched_at, fresh_until,
				    provider_coverage, warnings, total_provider_matches, result_count, created_at
				)
				VALUES (?, ?, ?, lower(?), ?, 1, 'test-v1',
				        '{"documentTypes":[],"openAccessOnly":false,"minimumCitations":0,"languages":[],"pageSize":20,"cursor":"*"}'::jsonb,
				        'COMPLETED', ?, ?, '[]'::jsonb, '[]'::jsonb, 0, 0, ?)
				""", searchId, ownerId, query, query, fingerprint, now, now.plusHours(1), now);
		return searchId;
	}

	private UUID insertFailedSearchJob(UUID searchId) {
		UUID jobId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO research_refresh_job (
				    id, job_type, target_id, trigger_kind, status, attempt_count, max_attempts,
				    available_at, last_error_code, last_error_detail, created_at,
				    completed_at, updated_at
				)
				VALUES (?, 'SEARCH_METADATA', ?, 'MANUAL', 'FAILED', 3, 3, ?,
				        'SEARCH_PROVIDER_UNAVAILABLE', 'Synthetic terminal provider outage', ?, ?, ?)
				""", jobId, searchId, now, now, now, now);
		return jobId;
	}
}
