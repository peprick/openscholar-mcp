package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProviderQualityEvidenceWriterTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@TempDir
	private Path temporaryDirectory;

	@Test
	void writesCreateNewPrivateEvidenceAndDigestBoundManifest() throws Exception {
		ProviderQualityEvidenceWriter writer = ProviderQualityEvidenceWriter.forRepository(
				OBJECT_MAPPER, temporaryDirectory, 64 * 1_024);
		Map<String, Object> artifacts = new LinkedHashMap<>();
		artifacts.put("summary.json", Map.of("schemaVersion", 1, "status", "COMPLETE"));
		artifacts.put("raw-candidates.json", Map.of("schemaVersion", 1, "candidates", java.util.List.of()));

		ProviderQualityEvidenceWriter.WriteResult result = writer.write(
				"provider-comparative-live-test", artifacts);

		Path expectedRoot = temporaryDirectory.toAbsolutePath().normalize()
				.resolve("backend/target/provider-quality");
		assertThat(result.directory()).isEqualTo(expectedRoot.resolve("provider-comparative-live-test"));
		assertThat(filenames(result.directory()))
				.containsExactlyInAnyOrder("manifest.json", "summary.json", "raw-candidates.json");
		assertThat(result.manifest().files()).hasSize(2).allSatisfy(file -> {
			Path path = result.directory().resolve(file.filename());
			assertThat(Files.size(path)).isEqualTo(file.bytes());
			assertThat(sha256(path)).isEqualTo(file.sha256());
		});
		assertThat(result.manifest().files())
				.extracting(ProviderQualityEvidenceWriter.FileDigest::filename)
				.containsExactly("raw-candidates.json", "summary.json");
		Path manifestPath = result.directory().resolve(ProviderQualityEvidenceWriter.MANIFEST_FILENAME);
		assertThat(sha256(manifestPath)).isEqualTo(result.manifestFile().sha256());
		assertThat(result.totalBytes()).isEqualTo(
				result.manifest().payloadBytes() + result.manifestFile().bytes());
		JsonNode manifest = OBJECT_MAPPER.readTree(Files.readAllBytes(manifestPath));
		assertThat(manifest.required("evidenceId").asString())
				.isEqualTo("provider-comparative-live-test");
		assertThat(manifest.required("files")).hasSize(2);

		assertPrivatePermissionsWhenPosix(expectedRoot, "rwx------");
		assertPrivatePermissionsWhenPosix(result.directory(), "rwx------");
		for (String filename : java.util.List.of(
				"manifest.json", "summary.json", "raw-candidates.json")) {
			assertPrivatePermissionsWhenPosix(result.directory().resolve(filename), "rw-------");
		}
		assertThat(filenames(expectedRoot))
				.containsExactly("provider-comparative-live-test");
	}

	@Test
	void refusesToOverwriteAnExistingEvidenceDirectory() throws Exception {
		ProviderQualityEvidenceWriter writer = ProviderQualityEvidenceWriter.forRepository(
				OBJECT_MAPPER, temporaryDirectory, 64 * 1_024);
		Map<String, ?> artifact = Map.of("summary.json", Map.of("status", "FIRST"));
		ProviderQualityEvidenceWriter.WriteResult first = writer.write(
				"provider-comparative-create-new", artifact);
		byte[] original = Files.readAllBytes(first.directory().resolve("summary.json"));

		assertThatThrownBy(() -> writer.write(
				"provider-comparative-create-new",
				Map.of("summary.json", Map.of("status", "SECOND"))))
				.isInstanceOf(FileAlreadyExistsException.class);
		assertThat(Files.readAllBytes(first.directory().resolve("summary.json")))
				.isEqualTo(original);
		Path outputRoot = temporaryDirectory.toAbsolutePath().normalize()
				.resolve("backend/target/provider-quality");
		assertThat(filenames(outputRoot))
				.containsExactly("provider-comparative-create-new");
	}

	@Test
	void serializesMapEntriesCanonicallyBeforeHashing() throws Exception {
		ProviderQualityEvidenceWriter writer = ProviderQualityEvidenceWriter.forRepository(
				OBJECT_MAPPER, temporaryDirectory, 64 * 1_024);
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("zeta", 2);
		first.put("alpha", 1);
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("alpha", 1);
		second.put("zeta", 2);

		ProviderQualityEvidenceWriter.WriteResult firstResult = writer.write(
				"provider-comparative-order-a", Map.of("summary.json", first));
		ProviderQualityEvidenceWriter.WriteResult secondResult = writer.write(
				"provider-comparative-order-b", Map.of("summary.json", second));

		byte[] firstBytes = Files.readAllBytes(firstResult.directory().resolve("summary.json"));
		byte[] secondBytes = Files.readAllBytes(secondResult.directory().resolve("summary.json"));
		assertThat(firstBytes).isEqualTo(secondBytes);
		assertThat(new String(firstBytes, java.nio.charset.StandardCharsets.UTF_8))
				.isEqualTo("{\"alpha\":1,\"zeta\":2}\n");
	}

	@Test
	void rejectsUnsafeNamesAndOversizedEvidenceBeforeCreatingItsDirectory() {
		ProviderQualityEvidenceWriter writer = ProviderQualityEvidenceWriter.forRepository(
				OBJECT_MAPPER, temporaryDirectory, 256);

		assertThatThrownBy(() -> writer.write(
				"provider-comparative-oversized",
				Map.of("raw-candidates.json", Map.of("payload", "x".repeat(1_000)))))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("maximum is 256");
		Path outputRoot = temporaryDirectory.toAbsolutePath().normalize()
				.resolve("backend/target/provider-quality");
		assertThat(outputRoot.resolve("provider-comparative-oversized")).doesNotExist();

		assertThatThrownBy(() -> writer.write(
				"provider-comparative-safe-name",
				Map.of("../escape.json", Map.of("value", 1))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("safe JSON basename");
		assertThatThrownBy(() -> writer.write(
				"provider-comparative-safe-name",
				Map.of("manifest.json", Map.of("value", 1))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("manifest.json");
	}

	private static String sha256(Path path) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
	}

	private static List<String> filenames(Path directory) throws IOException {
		try (Stream<Path> paths = Files.list(directory)) {
			return paths.map(path -> path.getFileName().toString()).toList();
		}
	}

	private static void assertPrivatePermissionsWhenPosix(Path path, String expected)
			throws Exception {
		PosixFileAttributeView view = Files.getFileAttributeView(
				path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (view != null) {
			assertThat(view.readAttributes().permissions())
					.isEqualTo(PosixFilePermissions.fromString(expected));
		}
	}
}
