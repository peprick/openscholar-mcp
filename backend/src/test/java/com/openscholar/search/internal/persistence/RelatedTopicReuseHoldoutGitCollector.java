package com.openscholar.search.internal.persistence;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.RepositoryState;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal;

/**
 * Test-only collector for an externally frozen related-topic holdout evaluator.
 *
 * <p>The accepting boundary always requires an independently retained freeze
 * record. It reads committed blobs rather than trusting caller-supplied source
 * lists and has no operation that blesses the current checkout or invents the
 * expected digests.</p>
 */
final class RelatedTopicReuseHoldoutGitCollector {

	static final int FREEZE_SCHEMA_VERSION = 1;
	static final String INVENTORY_ID =
			"related-topic-reuse-holdout-source-inventory-v1";

	private static final int MAXIMUM_SMALL_OUTPUT_BYTES = 4 * 1024;
	private static final int MAXIMUM_STATUS_BYTES = 2 * 1024 * 1024;
	private static final int MAXIMUM_TREE_BYTES = 8 * 1024 * 1024;
	private static final int MAXIMUM_BATCH_OUTPUT_BYTES =
			(int) RelatedTopicReuseHoldoutEvaluatorSeal.MAXIMUM_TOTAL_SOURCE_BYTES
					+ 128 * 1024;
	private static final int MAXIMUM_STANDARD_INPUT_BYTES = 128 * 1024;
	private static final int MAXIMUM_WORKTREE_FILE_BYTES =
			2 * RelatedTopicReuseHoldoutEvaluatorSeal.MAXIMUM_SOURCE_FILE_BYTES;
	private static final int MAXIMUM_ERROR_BYTES = 64 * 1024;
	private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);
	private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern TREE_ENTRY = Pattern.compile(
			"(100644|100755) blob ([0-9a-f]{40}) +([0-9]+)\\t(.+)");
	private static final Pattern BATCH_HEADER = Pattern.compile(
			"([0-9a-f]{40}) blob ([0-9]+)");
	private static final Pattern SAFE_PATH_SEGMENT =
			Pattern.compile("[A-Za-z0-9._-]{1,100}");
	private static final String PERSISTENCE_DIRECTORY =
			"backend/src/test/java/com/openscholar/search/internal/persistence/";
	private static final List<String> CANDIDATE_EXACT_PATHS = List.of(
			"backend/src/test/java/com/openscholar/TestcontainersConfiguration.java",
			"backend/src/test/java/com/openscholar/search/internal/LocalCatalogSearchEvaluationAdapter.java",
			PERSISTENCE_DIRECTORY + "OwnerScopedRelatedTopicComparator.java",
			PERSISTENCE_DIRECTORY + "OwnerScopedRelatedTopicReuseEvaluationTests.java",
			PERSISTENCE_DIRECTORY + "RelatedTopicRankFusion.java",
			PERSISTENCE_DIRECTORY + "RelatedTopicRankFusionTests.java",
			PERSISTENCE_DIRECTORY + "RelatedTopicReuseEvaluationFixture.java",
			PERSISTENCE_DIRECTORY + "RelatedTopicReuseEvaluationFixtureContractTests.java",
			PERSISTENCE_DIRECTORY + "RelatedTopicReuseEvaluationPolicy.java",
			PERSISTENCE_DIRECTORY + "RelatedTopicReuseEvaluationPolicyContractTests.java",
			"backend/src/test/resources/search/relevance/related-topic-reuse-development-v1.json",
			"backend/src/test/resources/search/relevance/related-topic-reuse-policy-v1.json");
	private static final List<String> CANDIDATE_RECURSIVE_PATHS =
			List.of("backend/src/main");
	private static final List<String> CANDIDATE_PATHS = withScopes(
			CANDIDATE_RECURSIVE_PATHS, CANDIDATE_EXACT_PATHS);
	private static final List<String> EVALUATOR_EXACT_PATHS = List.of(
			"backend/pom.xml",
			"backend/mvnw",
			"backend/mvnw.cmd",
			"backend/.mvn/wrapper/maven-wrapper.properties",
			"backend/.gitattributes",
			"backend/.gitignore",
			".gitattributes",
			".gitignore",
			"docs/RELATED_TOPIC_REUSE_HOLDOUT_PROTOCOL.md");
	private static final List<String> EVALUATOR_RECURSIVE_PATHS =
			List.of("backend/src", "backend/.mvn");
	private static final List<String> EVALUATOR_PATHS = withScopes(
			EVALUATOR_RECURSIVE_PATHS, EVALUATOR_EXACT_PATHS);
	private static final ProcessGitRunner SYSTEM_GIT = ProcessGitRunner.configured();

	private RelatedTopicReuseHoldoutGitCollector() {
	}

	static VerifiedCleanCheckout verifyCleanCheckout(
			Path repositoryRoot, FreezeRecord externallyRetainedFreeze)
			throws IOException {
		return verifyCleanCheckout(repositoryRoot, externallyRetainedFreeze, SYSTEM_GIT);
	}

	static VerifiedCleanCheckout verifyCleanCheckout(
			Path repositoryRoot,
			FreezeRecord externallyRetainedFreeze,
			GitRunner git)
			throws IOException {
		Objects.requireNonNull(externallyRetainedFreeze, "externallyRetainedFreeze");
		Objects.requireNonNull(git, "git");
		try {
			Path root = realRepositoryRoot(repositoryRoot, git);
			requireNoGrafts(root);
			requireExactOutput(
					runSuccess(git, root, List.of(
							"rev-parse", "--show-object-format"),
							MAXIMUM_SMALL_OUTPUT_BYTES,
							"HOLDOUT_GIT_OBJECT_FORMAT_INVALID"),
					"sha1",
					"HOLDOUT_GIT_OBJECT_FORMAT_INVALID");

			String initialHead = requireStableCleanHead(
					git,
					root,
					externallyRetainedFreeze.evaluatorRevision(),
					"HOLDOUT_GIT_EVALUATOR_REVISION_MISMATCH");
			String candidateRevision = resolvedRevision(
					git, root, externallyRetainedFreeze.candidateRevision());
			if (!candidateRevision.equals(externallyRetainedFreeze.candidateRevision())) {
				throw failure("HOLDOUT_GIT_CANDIDATE_REVISION_MISMATCH");
			}
			requireAncestor(git, root, candidateRevision, initialHead);

			Map<String, byte[]> objectCache = new HashMap<>();
			LoadedInventory candidateAtFreeze = loadInventory(
					git, root, candidateRevision, InventoryRole.CANDIDATE, objectCache);
			LoadedInventory candidateAtHead = loadInventory(
					git, root, initialHead, InventoryRole.CANDIDATE, objectCache);
			requireSameCandidateTree(candidateAtFreeze, candidateAtHead);
			String frozenCandidateSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
					SourceRole.CANDIDATE,
					candidateRevision,
					candidateAtFreeze.sources());
			String observedCandidateSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
					SourceRole.CANDIDATE,
					candidateRevision,
					candidateAtHead.sources());
			if (!constantTimeEquals(
					externallyRetainedFreeze.candidateSourceSha256(),
					frozenCandidateSha256)
					|| !constantTimeEquals(
							externallyRetainedFreeze.candidateSourceSha256(),
							observedCandidateSha256)) {
				throw failure("HOLDOUT_GIT_CANDIDATE_SOURCE_MISMATCH");
			}

			LoadedInventory evaluator = loadInventory(
					git, root, initialHead, InventoryRole.EVALUATOR, objectCache);
			verifyWorktree(root, evaluator);

			requireStableCleanHead(
					git, root, initialHead, "HOLDOUT_GIT_HEAD_CHANGED");

			RepositoryState repositoryState = new RepositoryState(
					initialHead,
					"",
					candidateRevision,
					observedCandidateSha256,
					true);
			try {
				VerifiedEvaluatorSeal evaluatorSeal =
						RelatedTopicReuseHoldoutEvaluatorSeal.verify(
						initialHead,
						externallyRetainedFreeze.evaluatorSourceSha256(),
						candidateRevision,
						externallyRetainedFreeze.candidateSourceSha256(),
						repositoryState,
						evaluator.sources(),
						candidateAtHead.sources());
				return new VerifiedCleanCheckout(
						externallyRetainedFreeze, evaluatorSeal);
			}
			catch (IllegalArgumentException | ArithmeticException exception) {
				throw failure("HOLDOUT_GIT_SOURCE_SEAL_REJECTED");
			}
		}
		catch (CollectionException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_GIT_COLLECTION_FAILED");
		}
	}

	static List<String> candidateInventoryPaths() {
		return CANDIDATE_PATHS;
	}

	static List<String> evaluatorInventoryPaths() {
		return EVALUATOR_PATHS;
	}

	private static Path realRepositoryRoot(Path supplied, GitRunner git)
			throws IOException {
		if (supplied == null || !supplied.isAbsolute()) {
			throw failure("HOLDOUT_GIT_REPOSITORY_INVALID");
		}
		Path normalized;
		try {
			normalized = supplied.normalize();
			if (Files.isSymbolicLink(normalized)
					|| !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("HOLDOUT_GIT_REPOSITORY_INVALID");
			}
			normalized = normalized.toRealPath();
			Path gitDirectory = normalized.resolve(".git");
			Path gitInfoDirectory = gitDirectory.resolve("info");
			Path commonDirectoryPointer = gitDirectory.resolve("commondir");
			if (Files.isSymbolicLink(gitDirectory)
					|| !Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(gitInfoDirectory)
					|| !Files.isDirectory(
							gitInfoDirectory, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(commonDirectoryPointer)
					|| Files.exists(
							commonDirectoryPointer, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("HOLDOUT_GIT_REPOSITORY_INVALID");
			}
		}
		catch (CollectionException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_GIT_REPOSITORY_INVALID");
		}
		if (normalized.toString().length() > 4096
				|| normalized.toString().codePoints().anyMatch(Character::isISOControl)) {
			throw failure("HOLDOUT_GIT_REPOSITORY_INVALID");
		}
		byte[] topLevel = runSuccess(
				git,
				normalized,
				List.of("rev-parse", "--show-toplevel"),
				MAXIMUM_SMALL_OUTPUT_BYTES,
				"HOLDOUT_GIT_REPOSITORY_INVALID");
		String reported = exactLine(topLevel, "HOLDOUT_GIT_REPOSITORY_INVALID");
		try {
			if (!Path.of(reported).toRealPath().equals(normalized)) {
				throw failure("HOLDOUT_GIT_REPOSITORY_INVALID");
			}
		}
		catch (CollectionException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_GIT_REPOSITORY_INVALID");
		}
		return normalized;
	}

	private static void requireNoGrafts(Path root) throws IOException {
		try {
			Path grafts = root.resolve(".git/info/grafts");
			if (Files.isSymbolicLink(grafts)
					|| Files.exists(grafts, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("HOLDOUT_GIT_GRAPH_METADATA_UNTRUSTED");
			}
		}
		catch (CollectionException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw failure("HOLDOUT_GIT_GRAPH_METADATA_UNTRUSTED");
		}
	}

	private static String requireStableCleanHead(
			GitRunner git,
			Path root,
			String expected,
			String mismatchDiagnostic)
			throws IOException {
		String before = resolvedRevision(git, root, "HEAD");
		if (!expected.equals(before)) {
			throw failure(mismatchDiagnostic);
		}
		requireCleanStatus(git, root);
		String after = resolvedRevision(git, root, "HEAD");
		if (!before.equals(after)) {
			throw failure("HOLDOUT_GIT_HEAD_CHANGED");
		}
		if (!expected.equals(after)) {
			throw failure(mismatchDiagnostic);
		}
		return before;
	}

	private static void requireCleanStatus(GitRunner git, Path root)
			throws IOException {
		CommandResult status = runSuccessResult(
				git,
				root,
				List.of(
						"status",
						"--porcelain=v1",
						"-z",
						"--untracked-files=all",
						"--ignore-submodules=none"),
				MAXIMUM_STATUS_BYTES,
				"HOLDOUT_GIT_STATUS_INVALID");
		if (status.stdout().length != 0) {
			throw failure("HOLDOUT_GIT_WORKTREE_NOT_CLEAN");
		}
		List<String> scopedArguments = new ArrayList<>(List.of(
				"status",
				"--porcelain=v1",
				"-z",
				"--untracked-files=all",
				"--ignored=matching",
				"--ignore-submodules=none",
				"--"));
		scopedArguments.addAll(EVALUATOR_PATHS);
		CommandResult scoped = runSuccessResult(
				git,
				root,
				scopedArguments,
				MAXIMUM_STATUS_BYTES,
				"HOLDOUT_GIT_STATUS_INVALID");
		if (scoped.stdout().length != 0) {
			throw failure("HOLDOUT_GIT_SOURCE_SCOPE_NOT_CLEAN");
		}
	}

	private static String resolvedRevision(
			GitRunner git, Path root, String revision) throws IOException {
		if (!revision.equals("HEAD") && !REVISION.matcher(revision).matches()) {
			throw failure("HOLDOUT_GIT_REVISION_INVALID");
		}
		byte[] output = runSuccess(
				git,
				root,
				List.of(
						"rev-parse",
						"--verify",
						"--end-of-options",
						revision + "^{commit}"),
				MAXIMUM_SMALL_OUTPUT_BYTES,
				"HOLDOUT_GIT_REVISION_INVALID");
		String resolved = exactLine(output, "HOLDOUT_GIT_REVISION_INVALID");
		if (!REVISION.matcher(resolved).matches()) {
			throw failure("HOLDOUT_GIT_REVISION_INVALID");
		}
		return resolved;
	}

	private static void requireAncestor(
			GitRunner git, Path root, String ancestor, String descendant)
			throws IOException {
		requireNoGrafts(root);
		CommandResult result = run(
				git,
				root,
				List.of("merge-base", "--is-ancestor", ancestor, descendant),
				MAXIMUM_SMALL_OUTPUT_BYTES,
				"HOLDOUT_GIT_ANCESTRY_FAILED");
		if (result.exitCode() == 1 && result.stdout().length == 0
				&& result.stderr().length == 0) {
			throw failure("HOLDOUT_GIT_CANDIDATE_NOT_ANCESTOR");
		}
		if (result.exitCode() != 0
				|| result.stdout().length != 0
				|| result.stderr().length != 0) {
			throw failure("HOLDOUT_GIT_ANCESTRY_FAILED");
		}
		requireNoGrafts(root);
	}

	private static LoadedInventory loadInventory(
			GitRunner git,
			Path root,
			String revision,
			InventoryRole role,
			Map<String, byte[]> objectCache)
			throws IOException {
		List<String> arguments = new ArrayList<>(List.of(
				"ls-tree", "-r", "-z", "--full-tree", "--long", revision, "--"));
		arguments.addAll(role.paths().stream()
				.map(path -> ":(top,literal)" + path)
				.toList());
		byte[] treeBytes = runSuccess(
				git,
				root,
				arguments,
				MAXIMUM_TREE_BYTES,
				"HOLDOUT_GIT_TREE_INVALID");
		List<TreeEntry> entries = parseTree(treeBytes, role);
		loadObjects(git, root, entries, objectCache);
		List<SourceFile> sources = new ArrayList<>(entries.size());
		for (TreeEntry entry : entries) {
			byte[] content = objectCache.get(entry.objectId());
			if (content == null || content.length != entry.bytes()) {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
			try {
				sources.add(new SourceFile(entry.gitMode(), entry.path(), content));
			}
			catch (IllegalArgumentException exception) {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
		}
		return new LoadedInventory(entries, sources);
	}

	private static void loadObjects(
			GitRunner git,
			Path root,
			List<TreeEntry> entries,
			Map<String, byte[]> objectCache)
			throws IOException {
		Map<String, Long> missing = new LinkedHashMap<>();
		for (TreeEntry entry : entries) {
			byte[] cached = objectCache.get(entry.objectId());
			if (cached != null) {
				if (cached.length != entry.bytes()) {
					throw failure("HOLDOUT_GIT_BLOB_INVALID");
				}
				continue;
			}
			Long previous = missing.putIfAbsent(entry.objectId(), entry.bytes());
			if (previous != null && previous.longValue() != entry.bytes()) {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
		}
		if (missing.isEmpty()) {
			return;
		}

		ByteArrayOutputStream input = new ByteArrayOutputStream();
		long maximumOutputBytes = 0L;
		for (Map.Entry<String, Long> entry : missing.entrySet()) {
			input.writeBytes(entry.getKey().getBytes(StandardCharsets.US_ASCII));
			input.write('\n');
			try {
				maximumOutputBytes = Math.addExact(
						maximumOutputBytes, Math.addExact(entry.getValue(), 96L));
			}
			catch (ArithmeticException exception) {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
		}
		byte[] standardInput = input.toByteArray();
		if (standardInput.length > MAXIMUM_STANDARD_INPUT_BYTES
				|| maximumOutputBytes > MAXIMUM_BATCH_OUTPUT_BYTES) {
			throw failure("HOLDOUT_GIT_BLOB_INVALID");
		}
		byte[] output = runSuccess(
				git,
				root,
				List.of("cat-file", "--batch"),
				standardInput,
				Math.toIntExact(maximumOutputBytes),
				"HOLDOUT_GIT_BLOB_INVALID");
		parseBatchObjects(output, missing, objectCache);
	}

	private static void parseBatchObjects(
			byte[] output,
			Map<String, Long> expected,
			Map<String, byte[]> objectCache)
			throws IOException {
		int offset = 0;
		for (Map.Entry<String, Long> entry : expected.entrySet()) {
			int headerEnd = offset;
			while (headerEnd < output.length && output[headerEnd] != '\n') {
				headerEnd++;
			}
			if (headerEnd == output.length || headerEnd - offset > 96) {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
			String header = strictUtf8(
					Arrays.copyOfRange(output, offset, headerEnd),
					"HOLDOUT_GIT_BLOB_INVALID");
			Matcher matcher = BATCH_HEADER.matcher(header);
			if (!matcher.matches()
					|| !entry.getKey().equals(matcher.group(1))) {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
			long announcedBytes;
			try {
				announcedBytes = Long.parseLong(matcher.group(2));
			}
			catch (NumberFormatException exception) {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
			if (announcedBytes != entry.getValue()) {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
			long contentStart = (long) headerEnd + 1L;
			long delimiter = contentStart + announcedBytes;
			if (delimiter >= output.length || output[Math.toIntExact(delimiter)] != '\n') {
				throw failure("HOLDOUT_GIT_BLOB_INVALID");
			}
			byte[] content = Arrays.copyOfRange(
					output,
					Math.toIntExact(contentStart),
					Math.toIntExact(delimiter));
			objectCache.put(entry.getKey(), content);
			offset = Math.toIntExact(delimiter + 1L);
		}
		if (offset != output.length) {
			throw failure("HOLDOUT_GIT_BLOB_INVALID");
		}
	}

	private static List<TreeEntry> parseTree(byte[] treeBytes, InventoryRole role)
			throws IOException {
		if (treeBytes.length == 0 || treeBytes[treeBytes.length - 1] != 0) {
			throw failure("HOLDOUT_GIT_TREE_INVALID");
		}
		List<TreeEntry> entries = new ArrayList<>();
		Set<String> paths = new HashSet<>();
		Set<String> foldedPaths = new HashSet<>();
		long totalBytes = 0L;
		int start = 0;
		String previousPath = null;
		while (start < treeBytes.length) {
			int end = start;
			while (end < treeBytes.length && treeBytes[end] != 0) {
				end++;
			}
			if (end == start) {
				throw failure("HOLDOUT_GIT_TREE_INVALID");
			}
			String value = strictUtf8(
					Arrays.copyOfRange(treeBytes, start, end),
					"HOLDOUT_GIT_TREE_INVALID");
			Matcher matcher = TREE_ENTRY.matcher(value);
			if (!matcher.matches()) {
				throw failure("HOLDOUT_GIT_TREE_INVALID");
			}
			int mode = Integer.parseInt(matcher.group(1));
			String objectId = matcher.group(2);
			long bytes;
			try {
				bytes = Long.parseLong(matcher.group(3));
			}
			catch (NumberFormatException exception) {
				throw failure("HOLDOUT_GIT_TREE_INVALID");
			}
			String path = matcher.group(4);
			requireSafePath(path);
			if (!role.includes(path)
					|| !paths.add(path)
					|| !foldedPaths.add(path.toLowerCase(Locale.ROOT))
					|| previousPath != null && previousPath.compareTo(path) >= 0
					|| bytes < 1
					|| bytes > RelatedTopicReuseHoldoutEvaluatorSeal.MAXIMUM_SOURCE_FILE_BYTES) {
				throw failure("HOLDOUT_GIT_TREE_INVALID");
			}
			try {
				totalBytes = Math.addExact(totalBytes, bytes);
			}
			catch (ArithmeticException exception) {
				throw failure("HOLDOUT_GIT_TREE_INVALID");
			}
			if (totalBytes > RelatedTopicReuseHoldoutEvaluatorSeal.MAXIMUM_TOTAL_SOURCE_BYTES
					|| entries.size() >= RelatedTopicReuseHoldoutEvaluatorSeal.MAXIMUM_SOURCE_FILES) {
				throw failure("HOLDOUT_GIT_TREE_INVALID");
			}
			entries.add(new TreeEntry(mode, path, objectId, bytes));
			previousPath = path;
			start = end + 1;
		}
		role.requireComplete(paths);
		return List.copyOf(entries);
	}

	private static void requireSameCandidateTree(
			LoadedInventory frozen, LoadedInventory observed) throws IOException {
		if (frozen.entries().size() != observed.entries().size()) {
			throw failure("HOLDOUT_GIT_CANDIDATE_TREE_DRIFT");
		}
		for (int index = 0; index < frozen.entries().size(); index++) {
			TreeEntry left = frozen.entries().get(index);
			TreeEntry right = observed.entries().get(index);
			if (left.gitMode() != right.gitMode()
					|| left.bytes() != right.bytes()
					|| !left.path().equals(right.path())
					|| !left.objectId().equals(right.objectId())
					|| !MessageDigest.isEqual(
							frozen.sources().get(index).content(),
							observed.sources().get(index).content())) {
				throw failure("HOLDOUT_GIT_CANDIDATE_TREE_DRIFT");
			}
		}
	}

	private static void verifyWorktree(Path root, LoadedInventory evaluator)
			throws IOException {
		for (int index = 0; index < evaluator.entries().size(); index++) {
			TreeEntry entry = evaluator.entries().get(index);
			Path path = root.resolve(entry.path()).normalize();
			if (!path.startsWith(root) || Files.isSymbolicLink(path)) {
				throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_INVALID");
			}
			BasicFileAttributes before = attributes(path);
			if (!before.isRegularFile()
					|| entry.gitMode() == 100755 && !Files.isExecutable(path)) {
				throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_INVALID");
			}
			byte[] expected = expectedWorktreeBytes(
					entry.path(), evaluator.sources().get(index).content());
			if (before.size() != expected.length) {
				throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_MISMATCH");
			}
			byte[] observed = readBoundedFile(path, expected.length);
			BasicFileAttributes after = attributes(path);
			if (!after.isRegularFile()
					|| before.size() != after.size()
					|| !Objects.equals(before.fileKey(), after.fileKey())
					|| !MessageDigest.isEqual(observed, expected)) {
				throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_MISMATCH");
			}
		}
	}

	private static byte[] expectedWorktreeBytes(String path, byte[] committed)
			throws IOException {
		if (!path.endsWith(".cmd")) {
			return committed;
		}
		ByteArrayOutputStream projected = new ByteArrayOutputStream();
		for (byte value : committed) {
			if (value == '\r') {
				throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_INVALID");
			}
			if (value == '\n') {
				projected.write('\r');
			}
			projected.write(value);
			if (projected.size() > MAXIMUM_WORKTREE_FILE_BYTES) {
				throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_INVALID");
			}
		}
		return projected.toByteArray();
	}

	private static BasicFileAttributes attributes(Path path) throws IOException {
		try {
			return Files.readAttributes(
					path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_INVALID");
		}
	}

	private static byte[] readBoundedFile(Path path, int maximum) throws IOException {
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			byte[] bytes = input.readNBytes(maximum + 1);
			if (bytes.length > maximum || channel.size() != bytes.length) {
				throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_INVALID");
			}
			return bytes;
		}
		catch (CollectionException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_GIT_WORKTREE_SOURCE_INVALID");
		}
	}

	private static byte[] runSuccess(
			GitRunner git,
			Path root,
			List<String> arguments,
			int maximumOutputBytes,
			String diagnostic)
			throws IOException {
		return runSuccessResult(
				git, root, arguments, maximumOutputBytes, diagnostic).stdout();
	}

	private static byte[] runSuccess(
			GitRunner git,
			Path root,
			List<String> arguments,
			byte[] standardInput,
			int maximumOutputBytes,
			String diagnostic)
			throws IOException {
		return runSuccessResult(
				git,
				root,
				arguments,
				standardInput,
				maximumOutputBytes,
				diagnostic).stdout();
	}

	private static CommandResult runSuccessResult(
			GitRunner git,
			Path root,
			List<String> arguments,
			int maximumOutputBytes,
			String diagnostic)
			throws IOException {
		CommandResult result = run(
				git, root, arguments, new byte[0], maximumOutputBytes, diagnostic);
		if (result.exitCode() != 0 || result.stderr().length != 0) {
			throw failure(diagnostic);
		}
		return result;
	}

	private static CommandResult runSuccessResult(
			GitRunner git,
			Path root,
			List<String> arguments,
			byte[] standardInput,
			int maximumOutputBytes,
			String diagnostic)
			throws IOException {
		CommandResult result = run(
				git, root, arguments, standardInput, maximumOutputBytes, diagnostic);
		if (result.exitCode() != 0 || result.stderr().length != 0) {
			throw failure(diagnostic);
		}
		return result;
	}

	private static CommandResult run(
			GitRunner git,
			Path root,
			List<String> arguments,
			int maximumOutputBytes,
			String diagnostic)
			throws IOException {
		return run(
				git, root, arguments, new byte[0], maximumOutputBytes, diagnostic);
	}

	private static CommandResult run(
			GitRunner git,
			Path root,
			List<String> arguments,
			byte[] standardInput,
			int maximumOutputBytes,
			String diagnostic)
			throws IOException {
		try {
			return git.run(
					root,
					List.copyOf(arguments),
					Objects.requireNonNull(standardInput, "standardInput").clone(),
					maximumOutputBytes);
		}
		catch (CollectionException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static void requireExactOutput(
			byte[] output, String expected, String diagnostic) throws IOException {
		if (!expected.equals(exactLine(output, diagnostic))) {
			throw failure(diagnostic);
		}
	}

	private static String exactLine(byte[] output, String diagnostic)
			throws IOException {
		String value = strictUtf8(output, diagnostic);
		if (value.endsWith("\r\n")) {
			value = value.substring(0, value.length() - 2);
		}
		else if (value.endsWith("\n")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.isEmpty()
				|| value.indexOf('\n') >= 0
				|| value.indexOf('\r') >= 0
				|| !value.equals(value.strip())) {
			throw failure(diagnostic);
		}
		return value;
	}

	private static String strictUtf8(byte[] value, String diagnostic)
			throws IOException {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(value))
					.toString();
		}
		catch (CharacterCodingException exception) {
			throw failure(diagnostic);
		}
	}

	private static void requireSafePath(String value) throws IOException {
		if (value.isEmpty()
				|| value.length() > 240
				|| value.startsWith("/")
				|| value.endsWith("/")
				|| value.contains("\\")
				|| value.contains("//")) {
			throw failure("HOLDOUT_GIT_TREE_INVALID");
		}
		for (String segment : value.split("/", -1)) {
			if (segment.equals(".")
					|| segment.equals("..")
					|| !SAFE_PATH_SEGMENT.matcher(segment).matches()) {
				throw failure("HOLDOUT_GIT_TREE_INVALID");
			}
		}
	}

	private static boolean constantTimeEquals(String left, String right) {
		return MessageDigest.isEqual(
				left.getBytes(StandardCharsets.US_ASCII),
				right.getBytes(StandardCharsets.US_ASCII));
	}

	private static List<String> withScopes(
			List<String> recursive, List<String> exact) {
		List<String> values = new ArrayList<>(recursive.size() + exact.size());
		values.addAll(recursive);
		values.addAll(exact);
		return List.copyOf(values);
	}

	private static CollectionException failure(String diagnostic) {
		return new CollectionException(diagnostic);
	}

	record FreezeRecord(
			int schemaVersion,
			String inventoryId,
			String evaluatorRevision,
			String evaluatorSourceSha256,
			String candidateRevision,
			String candidateSourceSha256) {

		FreezeRecord {
			if (schemaVersion != FREEZE_SCHEMA_VERSION
					|| !INVENTORY_ID.equals(inventoryId)
					|| evaluatorRevision == null
					|| !REVISION.matcher(evaluatorRevision).matches()
					|| evaluatorSourceSha256 == null
					|| !SHA256.matcher(evaluatorSourceSha256).matches()
					|| !RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION
							.equals(candidateRevision)
					|| candidateSourceSha256 == null
					|| !SHA256.matcher(candidateSourceSha256).matches()) {
				throw new IllegalArgumentException("invalid external holdout freeze record");
			}
		}
	}

	/**
	 * Opaque proof that the collector, rather than only the pure source-seal
	 * helper, verified a clean checkout against an externally retained freeze.
	 */
	static final class VerifiedCleanCheckout {

		private final FreezeRecord freeze;
		private final VerifiedEvaluatorSeal evaluatorSeal;

		private VerifiedCleanCheckout(
				FreezeRecord freeze, VerifiedEvaluatorSeal evaluatorSeal) {
			this.freeze = Objects.requireNonNull(freeze, "freeze");
			this.evaluatorSeal = Objects.requireNonNull(evaluatorSeal, "evaluatorSeal");
			if (freeze.schemaVersion() != FREEZE_SCHEMA_VERSION
					|| !INVENTORY_ID.equals(freeze.inventoryId())
					|| !freeze.evaluatorRevision().equals(evaluatorSeal.evaluatorRevision())
					|| !constantTimeEquals(
							freeze.evaluatorSourceSha256(),
							evaluatorSeal.evaluatorSourceSha256())
					|| !freeze.candidateRevision().equals(evaluatorSeal.candidateRevision())
					|| !constantTimeEquals(
							freeze.candidateSourceSha256(),
							evaluatorSeal.candidateSourceSha256())
					|| evaluatorSeal.externalBundleAcceptanceAuthorized()
					|| evaluatorSeal.custodyReleaseAuthorized()) {
				throw new IllegalArgumentException("invalid verified clean checkout");
			}
		}

		int freezeSchemaVersion() {
			return freeze.schemaVersion();
		}

		String inventoryId() {
			return freeze.inventoryId();
		}

		String evaluatorRevision() {
			return evaluatorSeal.evaluatorRevision();
		}

		String evaluatorSourceSha256() {
			return evaluatorSeal.evaluatorSourceSha256();
		}

		String candidateRevision() {
			return evaluatorSeal.candidateRevision();
		}

		String candidateSourceSha256() {
			return evaluatorSeal.candidateSourceSha256();
		}

		List<RelatedTopicReuseHoldoutEvaluatorSeal.SourceFileCommitment> files() {
			return evaluatorSeal.files();
		}

		VerifiedEvaluatorSeal evaluatorSeal() {
			return evaluatorSeal;
		}

		boolean externalBundleAcceptanceAuthorized() {
			return false;
		}

		boolean custodyReleaseAuthorized() {
			return false;
		}
	}

	@FunctionalInterface
	interface GitRunner {

		CommandResult run(
				Path root,
				List<String> arguments,
				byte[] standardInput,
				int maximumOutputBytes)
				throws IOException;
	}

	record CommandResult(int exitCode, byte[] stdout, byte[] stderr) {

		CommandResult {
			if (exitCode < 0) {
				throw new IllegalArgumentException("invalid Git exit code");
			}
			stdout = Objects.requireNonNull(stdout, "stdout").clone();
			stderr = Objects.requireNonNull(stderr, "stderr").clone();
		}

		@Override
		public byte[] stdout() {
			return stdout.clone();
		}

		@Override
		public byte[] stderr() {
			return stderr.clone();
		}
	}

	static final class CollectionException extends IOException {

		private CollectionException(String diagnostic) {
			super(diagnostic);
		}
	}

	private enum InventoryRole {
		CANDIDATE(
				CANDIDATE_PATHS,
				CANDIDATE_RECURSIVE_PATHS,
				CANDIDATE_EXACT_PATHS),
		EVALUATOR(
				EVALUATOR_PATHS,
				EVALUATOR_RECURSIVE_PATHS,
				EVALUATOR_EXACT_PATHS);

		private final List<String> paths;
		private final List<String> recursivePrefixes;
		private final List<String> exactPaths;

		InventoryRole(
				List<String> paths,
				List<String> recursivePaths,
				List<String> exactPaths) {
			this.paths = paths;
			this.recursivePrefixes = recursivePaths.stream()
					.map(path -> path + "/")
					.toList();
			this.exactPaths = exactPaths;
		}

		List<String> paths() {
			return paths;
		}

		boolean includes(String path) {
			return recursivePrefixes.stream().anyMatch(path::startsWith)
					|| exactPaths.contains(path);
		}

		void requireComplete(Set<String> observed) throws IOException {
			boolean recursiveScopesComplete = recursivePrefixes.stream().allMatch(
					prefix -> observed.stream().anyMatch(path -> path.startsWith(prefix)));
			if (!recursiveScopesComplete || !observed.containsAll(exactPaths)) {
				throw failure("HOLDOUT_GIT_TREE_INCOMPLETE");
			}
		}
	}

	private record TreeEntry(int gitMode, String path, String objectId, long bytes) {
	}

	private record LoadedInventory(List<TreeEntry> entries, List<SourceFile> sources) {

		LoadedInventory {
			entries = List.copyOf(entries);
			sources = List.copyOf(sources);
		}
	}

	static final class ProcessGitRunner implements GitRunner {

		private static final String GIT_EXECUTABLE_PROPERTY =
				"openscholar.holdout.git-executable";
		private static final String GIT_EXECUTABLE_ENVIRONMENT =
				"OPENSCHOLAR_HOLDOUT_GIT_EXECUTABLE";

		private final Path gitExecutable;
		private final Duration processTimeout;

		ProcessGitRunner(Path gitExecutable) {
			this(gitExecutable, PROCESS_TIMEOUT);
		}

		ProcessGitRunner(Path gitExecutable, Duration processTimeout) {
			this.gitExecutable = canonicalExecutable(gitExecutable);
			this.processTimeout = validProcessTimeout(processTimeout);
		}

		static ProcessGitRunner configured() {
			String configured = null;
			try {
				configured = System.getProperty(GIT_EXECUTABLE_PROPERTY);
				if (configured == null || configured.isBlank()) {
					configured = System.getenv(GIT_EXECUTABLE_ENVIRONMENT);
				}
			}
			catch (RuntimeException exception) {
				// The accepting boundary will fail closed with a stable diagnostic.
			}
			Path path = null;
			try {
				if (configured != null && !configured.isBlank()) {
					path = Path.of(configured);
				}
			}
			catch (RuntimeException exception) {
				// The constructor will retain an invalid, unusable configuration.
			}
			return new ProcessGitRunner(path);
		}

		@Override
		public CommandResult run(
				Path root,
				List<String> arguments,
				byte[] standardInput,
				int maximumOutputBytes)
				throws IOException {
			if (root == null
					|| gitExecutable == null
					|| processTimeout == null
					|| gitExecutable.startsWith(root)
					|| maximumOutputBytes < 0
					|| maximumOutputBytes > MAXIMUM_BATCH_OUTPUT_BYTES
					|| standardInput == null
					|| standardInput.length > MAXIMUM_STANDARD_INPUT_BYTES
					|| arguments.isEmpty()
					|| arguments.stream().anyMatch(Objects::isNull)) {
				throw failure("HOLDOUT_GIT_COMMAND_INVALID");
			}
			List<String> command = new ArrayList<>();
			command.add(gitExecutable.toString());
			command.add("--no-replace-objects");
			command.add("-c");
			command.add("core.fsmonitor=false");
			command.add("-c");
			command.add("core.untrackedCache=false");
			command.add("-c");
			command.add("core.commitGraph=false");
			command.add("-c");
			command.add("submodule.recurse=false");
			command.addAll(arguments);
			ProcessBuilder builder = new ProcessBuilder(command)
					.directory(root.toFile())
					.redirectErrorStream(false);
			Map<String, String> environment = builder.environment();
			environment.clear();
			environment.put("LC_ALL", "C");
			environment.put("LANG", "C");
			if (File.separatorChar != '\\') {
				environment.put("TMPDIR", "/tmp");
			}
			environment.put("GIT_OPTIONAL_LOCKS", "0");
			environment.put("GIT_TERMINAL_PROMPT", "0");
			environment.put("GIT_CONFIG_NOSYSTEM", "1");
			environment.put(
					"GIT_CONFIG_GLOBAL", File.separatorChar == '\\' ? "NUL" : "/dev/null");
			environment.put("GIT_NO_LAZY_FETCH", "1");
			environment.put("GIT_NO_REPLACE_OBJECTS", "1");

			Process process;
			try {
				process = builder.start();
			}
			catch (IOException | RuntimeException exception) {
				throw failure("HOLDOUT_GIT_COMMAND_FAILED");
			}
			long deadline = System.nanoTime() + processTimeout.toNanos();
			AtomicReference<byte[]> stdout = new AtomicReference<>();
			AtomicReference<byte[]> stderr = new AtomicReference<>();
			AtomicReference<Throwable> ioFailure = new AtomicReference<>();
			Thread stdoutReader = reader(
					process.getInputStream(), maximumOutputBytes, stdout, ioFailure);
			Thread stderrReader = reader(
					process.getErrorStream(), MAXIMUM_ERROR_BYTES, stderr, ioFailure);
			Thread stdinWriter = writer(
					process.getOutputStream(), standardInput.clone(), ioFailure);
			boolean completed;
			try {
				long remaining = deadline - System.nanoTime();
				completed = remaining > 0
						&& process.waitFor(remaining, TimeUnit.NANOSECONDS);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				terminate(process);
				throw failure("HOLDOUT_GIT_COMMAND_INTERRUPTED");
			}
			if (!completed) {
				terminate(process);
				throw failure("HOLDOUT_GIT_COMMAND_TIMEOUT");
			}
			try {
				if (!joinUntil(deadline, stdinWriter, stdoutReader, stderrReader)) {
					throw failure("HOLDOUT_GIT_COMMAND_TIMEOUT");
				}
			}
			catch (CollectionException exception) {
				terminate(process);
				throw exception;
			}
			if (ioFailure.get() != null
					|| stdout.get() == null
					|| stderr.get() == null
					|| stdout.get().length > maximumOutputBytes
					|| stderr.get().length > MAXIMUM_ERROR_BYTES) {
				throw failure("HOLDOUT_GIT_COMMAND_OUTPUT_INVALID");
			}
			return new CommandResult(process.exitValue(), stdout.get(), stderr.get());
		}

		private static Path canonicalExecutable(Path supplied) {
			try {
				if (supplied == null
						|| !supplied.isAbsolute()
						|| supplied.toString().codePoints().anyMatch(Character::isISOControl)
						|| Files.isSymbolicLink(supplied)
						|| !Files.isRegularFile(supplied, LinkOption.NOFOLLOW_LINKS)
						|| !Files.isExecutable(supplied)) {
					return null;
				}
				Path real = supplied.toRealPath();
				if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
						|| !Files.isExecutable(real)) {
					return null;
				}
				return real;
			}
			catch (IOException | RuntimeException exception) {
				return null;
			}
		}

		private static Duration validProcessTimeout(Duration supplied) {
			try {
				if (supplied == null
						|| supplied.isZero()
						|| supplied.isNegative()
						|| supplied.compareTo(PROCESS_TIMEOUT) > 0) {
					return null;
				}
				return supplied;
			}
			catch (RuntimeException exception) {
				return null;
			}
		}

		private static Thread reader(
				InputStream input,
				int maximum,
				AtomicReference<byte[]> target,
				AtomicReference<Throwable> failure) {
			return Thread.ofVirtual().start(() -> {
				try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
					byte[] buffer = new byte[8192];
					int remaining = maximum + 1;
					while (remaining > 0) {
						int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
						if (read < 0) {
							break;
						}
						output.write(buffer, 0, read);
						remaining -= read;
					}
					target.set(output.toByteArray());
				}
				catch (Throwable exception) {
					failure.compareAndSet(null, exception);
				}
			});
		}

		private static Thread writer(
				OutputStream output,
				byte[] standardInput,
				AtomicReference<Throwable> failure) {
			return Thread.ofVirtual().start(() -> {
				try (output) {
					output.write(standardInput);
				}
				catch (Throwable exception) {
					failure.compareAndSet(null, exception);
				}
			});
		}

		private static boolean joinUntil(long deadline, Thread... threads)
				throws IOException {
			for (Thread thread : threads) {
				while (thread.isAlive()) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0) {
						return false;
					}
					try {
						thread.join(Math.max(
								1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
					}
					catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw failure("HOLDOUT_GIT_COMMAND_INTERRUPTED");
					}
				}
			}
			return true;
		}

		private static void terminate(Process process) {
			try {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
			}
			catch (RuntimeException exception) {
				// Stream closure below is the final local cleanup boundary.
			}
			process.destroyForcibly();
			closeQuietly(process.getOutputStream());
			closeQuietly(process.getInputStream());
			closeQuietly(process.getErrorStream());
		}

		private static void closeQuietly(AutoCloseable resource) {
			try {
				resource.close();
			}
			catch (Exception exception) {
				// Best-effort cleanup after a failed command.
			}
		}
	}
}
