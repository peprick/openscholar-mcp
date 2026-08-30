package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.CollectionException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.CommandResult;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.FreezeRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.GitRunner;

class RelatedTopicReuseHoldoutGitCollectorTests {

	private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
	private static final String CHANGED_HEAD = "f".repeat(40);
	private static final Path GIT_EXECUTABLE = locateGitExecutable();

	@TempDir
	Path temporaryDirectory;

	@Test
	void cleanCommittedCloneProducesTheOpaqueExternallyBoundSeal() throws Exception {
		CloneState state = cleanClone("happy");

		var verified = RelatedTopicReuseHoldoutGitCollector.verifyCleanCheckout(
				state.root(), state.freeze(), processGitRunner());

		assertThat(verified.freezeSchemaVersion())
				.isEqualTo(RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION);
		assertThat(verified.inventoryId())
				.isEqualTo(RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID);
		assertThat(verified.evaluatorRevision()).isEqualTo(state.evaluatorRevision());
		assertThat(verified.evaluatorSourceSha256())
				.isEqualTo(state.freeze().evaluatorSourceSha256());
		assertThat(verified.candidateRevision())
				.isEqualTo(RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION);
		assertThat(verified.candidateSourceSha256())
				.isEqualTo(state.freeze().candidateSourceSha256());
		assertThat(verified.files())
				.hasSize(state.evaluatorSources().size() + state.candidateSources().size());
		assertThat(verified.files())
				.filteredOn(file -> file.role() == SourceRole.EVALUATOR)
				.hasSize(state.evaluatorSources().size());
		assertThat(verified.files())
				.filteredOn(file -> file.role() == SourceRole.CANDIDATE)
				.hasSize(state.candidateSources().size());
		assertThat(verified.externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(verified.custodyReleaseAuthorized()).isFalse();
		assertThat(verified.evaluatorSeal().evaluatorRevision())
				.isEqualTo(verified.evaluatorRevision());
		assertThat(Arrays.stream(verified.getClass().getDeclaredConstructors()))
				.singleElement()
				.satisfies(constructor ->
						assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
	}

	@Test
	void trackedOrUntrackedWorktreeChangesFailClosedWithStableDiagnostics()
			throws Exception {
		CloneState tracked = cleanClone("tracked-dirty");
		Files.writeString(
				tracked.root().resolve("docs/RELATED_TOPIC_REUSE_HOLDOUT_PROTOCOL.md"),
				"\nlocal drift\n",
				StandardCharsets.UTF_8,
				StandardOpenOption.APPEND);

		assertCollectionFailure(
				tracked,
				tracked.freeze(),
				"HOLDOUT_GIT_WORKTREE_NOT_CLEAN");

		CloneState untracked = cleanClone("untracked-dirty");
		Files.writeString(
				untracked.root().resolve("unexpected-source.txt"),
				"not committed\n",
				StandardCharsets.UTF_8);

		assertCollectionFailure(
				untracked,
				untracked.freeze(),
				"HOLDOUT_GIT_WORKTREE_NOT_CLEAN");
	}

	@Test
	void ignoredInjectionInsideEvaluatorScopeFailsClosed() throws Exception {
		CloneState state = cleanClone("ignored-source");
		String relative = "backend/.mvn/ignored-evaluator-extension.xml";
		Files.writeString(
				state.root().resolve(".git/info/exclude"),
				"\n/" + relative + "\n",
				StandardCharsets.UTF_8,
				StandardOpenOption.APPEND);
		Files.writeString(
				state.root().resolve(relative),
				"ignored but in the sealed source scope\n",
				StandardCharsets.UTF_8);

		assertCollectionFailure(
				state,
				state.freeze(),
				"HOLDOUT_GIT_SOURCE_SCOPE_NOT_CLEAN");
	}

	@Test
	void skipWorktreeCannotHideDifferentWindowsLauncherBytes() throws Exception {
		CloneState state = cleanClone("skip-worktree-cmd");
		String launcher = "backend/mvnw.cmd";
		gitSuccess(
				state.root(), "update-index", "--skip-worktree", "--", launcher);
		Files.writeString(
				state.root().resolve(launcher),
				"\r\n@REM hidden local mutation\r\n",
				StandardCharsets.US_ASCII,
				StandardOpenOption.APPEND);

		assertCollectionFailure(
				state,
				state.freeze(),
				"HOLDOUT_GIT_WORKTREE_SOURCE_MISMATCH");
	}

	@Test
	void repositoryLocalGraftsAreRejectedBeforeAncestry() throws Exception {
		CloneState state = cleanClone("grafts");
		Files.writeString(
				state.root().resolve(".git/info/grafts"),
				"# even an empty semantic graft file is forbidden\n",
				StandardCharsets.US_ASCII);

		assertCollectionFailure(
				state,
				state.freeze(),
				"HOLDOUT_GIT_GRAPH_METADATA_UNTRUSTED");
	}

	@Test
	void redirectedGitCommonDirectoryIsNotAStandaloneClone() throws Exception {
		CloneState state = cleanClone("common-directory");
		Files.writeString(
				state.root().resolve(".git/commondir"),
				"../external-common-directory\n",
				StandardCharsets.US_ASCII);

		assertCollectionFailure(
				state,
				state.freeze(),
				"HOLDOUT_GIT_REPOSITORY_INVALID");
	}

	@Test
	void evaluatorInventoryRequiresTheMavenWrapperConfiguration() throws Exception {
		CloneState state = cleanClone("missing-wrapper-properties");
		String wrapper = "backend/.mvn/wrapper/maven-wrapper.properties";
		gitSuccess(state.root(), "rm", "--quiet", "--", wrapper);
		gitSuccess(
				state.root(),
				"-c", "user.name=OpenScholar tests",
				"-c", "user.email=tests@invalid.example",
				"commit", "--quiet", "-m", "test missing wrapper configuration");
		String changedRevision = gitLine(
				state.root(), "rev-parse", "--verify", "HEAD^{commit}");
		List<SourceFile> incompleteEvaluator = committedSources(
				state.root(),
				changedRevision,
				RelatedTopicReuseHoldoutGitCollector.evaluatorInventoryPaths());
		FreezeRecord changedFreeze = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				changedRevision,
				RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
						SourceRole.EVALUATOR,
						changedRevision,
						incompleteEvaluator),
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				state.freeze().candidateSourceSha256());

		assertCollectionFailure(
				state,
				changedFreeze,
				"HOLDOUT_GIT_TREE_INCOMPLETE");
	}

	@Test
	void cleanCommittedCandidateDriftIsRejectedBeforeScoring() throws Exception {
		CloneState state = cleanClone("candidate-drift");
		String candidatePath = state.candidateSources().stream()
				.map(SourceFile::path)
				.filter(path -> path.startsWith("backend/src/main/"))
				.findFirst()
				.orElseThrow();
		Files.writeString(
				state.root().resolve(candidatePath),
				"\n// committed holdout test drift\n",
				StandardCharsets.UTF_8,
				StandardOpenOption.APPEND);
		gitSuccess(state.root(), "add", "--", candidatePath);
		gitSuccess(
				state.root(),
				"-c", "user.name=OpenScholar tests",
				"-c", "user.email=tests@invalid.example",
				"commit", "--quiet", "-m", "test candidate drift");
		String changedRevision = gitLine(
				state.root(), "rev-parse", "--verify", "HEAD^{commit}");
		List<SourceFile> changedEvaluator = committedSources(
				state.root(),
				changedRevision,
				RelatedTopicReuseHoldoutGitCollector.evaluatorInventoryPaths());
		String changedEvaluatorSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, changedRevision, changedEvaluator);
		FreezeRecord changedFreeze = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				changedRevision,
				changedEvaluatorSha256,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				state.freeze().candidateSourceSha256());

		assertCollectionFailure(
				state,
				changedFreeze,
				"HOLDOUT_GIT_CANDIDATE_TREE_DRIFT");
	}

	@Test
	void independentlyRetainedDigestsAreMandatory() throws Exception {
		CloneState state = cleanClone("external-digests");
		FreezeRecord wrongCandidate = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				state.evaluatorRevision(),
				state.freeze().evaluatorSourceSha256(),
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				"0".repeat(64));
		FreezeRecord wrongEvaluator = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				state.evaluatorRevision(),
				"0".repeat(64),
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				state.freeze().candidateSourceSha256());

		assertCollectionFailure(
				state,
				wrongCandidate,
				"HOLDOUT_GIT_CANDIDATE_SOURCE_MISMATCH");
		assertCollectionFailure(
				state,
				wrongEvaluator,
				"HOLDOUT_GIT_SOURCE_SEAL_REJECTED");
	}

	@Test
	void changingHeadDuringCollectionFailsClosed() throws Exception {
		CloneState state = cleanClone("head-race");
		AtomicInteger headReads = new AtomicInteger();
		GitRunner changingHead = (
				root, arguments, standardInput, maximumOutputBytes) -> {
			if (arguments.equals(List.of(
					"rev-parse", "--verify", "--end-of-options", "HEAD^{commit}"))
					&& headReads.incrementAndGet() == 4) {
				return new CommandResult(
						0,
						(CHANGED_HEAD + "\n").getBytes(StandardCharsets.US_ASCII),
						new byte[0]);
			}
			return collectorGit(
					root, arguments, standardInput, maximumOutputBytes);
		};

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutGitCollector
				.verifyCleanCheckout(state.root(), state.freeze(), changingHead))
				.isInstanceOf(CollectionException.class)
				.hasMessage("HOLDOUT_GIT_HEAD_CHANGED");
		assertThat(headReads).hasValue(4);
	}

	@Test
	void malformedTreeOutputFailsClosedWithoutDisclosingAPath() throws Exception {
		CloneState state = cleanClone("malformed-tree");
		AtomicInteger treeReads = new AtomicInteger();
		GitRunner malformedTree = (
				root, arguments, standardInput, maximumOutputBytes) -> {
			if (!arguments.isEmpty() && arguments.get(0).equals("ls-tree")
					&& treeReads.incrementAndGet() == 1) {
				return new CommandResult(
						0,
						"truncated tree record".getBytes(StandardCharsets.UTF_8),
						new byte[0]);
			}
			return collectorGit(
					root, arguments, standardInput, maximumOutputBytes);
		};

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutGitCollector
				.verifyCleanCheckout(state.root(), state.freeze(), malformedTree))
				.isInstanceOf(CollectionException.class)
				.hasMessage("HOLDOUT_GIT_TREE_INVALID")
				.hasMessageNotContaining(state.root().toString());
	}

	@Test
	void malformedBatchObjectFramingFailsClosed() throws Exception {
		CloneState state = cleanClone("malformed-batch");
		for (String corruption : List.of(
				"truncated-header",
				"wrong-object-id",
				"wrong-type",
				"wrong-size",
				"missing-delimiter",
				"truncated-content",
				"trailing-bytes")) {
			AtomicInteger batchReads = new AtomicInteger();
			GitRunner malformedBatch = (
					root, arguments, standardInput, maximumOutputBytes) -> {
				if (arguments.equals(List.of("cat-file", "--batch"))
						&& batchReads.incrementAndGet() == 1) {
					assertThat(standardInput).isNotEmpty();
					CommandResult valid = collectorGit(
							root, arguments, standardInput, maximumOutputBytes);
					return new CommandResult(
							0, corruptBatch(valid.stdout(), corruption), new byte[0]);
				}
				return collectorGit(
						root, arguments, standardInput, maximumOutputBytes);
			};

			assertThatThrownBy(() -> RelatedTopicReuseHoldoutGitCollector
					.verifyCleanCheckout(state.root(), state.freeze(), malformedBatch))
					.as(corruption)
					.isInstanceOf(CollectionException.class)
					.hasMessage("HOLDOUT_GIT_BLOB_INVALID");
			assertThat(batchReads).as(corruption).hasValue(1);
		}
	}

	@Test
	void freezeRecordAndRepositoryRootAreStrictlyValidated() {
		String evaluatorRevision = "a".repeat(40);
		String digest = "b".repeat(64);
		assertThatThrownBy(() -> new FreezeRecord(
				2,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorRevision,
				digest,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				digest))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("invalid external holdout freeze record");
		assertThatThrownBy(() -> new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				"different-inventory",
				evaluatorRevision,
				digest,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				digest))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorRevision,
				digest,
				"c".repeat(40),
				digest))
				.isInstanceOf(IllegalArgumentException.class);

		FreezeRecord valid = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorRevision,
				digest,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				digest);
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutGitCollector
				.verifyCleanCheckout(
						Path.of("relative-repository"), valid, processGitRunner()))
				.isInstanceOf(CollectionException.class)
				.hasMessage("HOLDOUT_GIT_REPOSITORY_INVALID");
	}

	@Test
	void processRunnerPinsCommandHardeningBoundsAndOneDeadline() throws Exception {
		assumeTrue(File.separatorChar != '\\');
		CloneState state = cleanClone("process-runner");
		Path hardened = executableScript(
				"hardened-git",
				"case \" $* \" in\n"
						+ "  *\" -c core.commitGraph=false \"*) printf 'hardened\\n' ;;\n"
						+ "  *) exit 23 ;;\n"
						+ "esac\n");
		var hardenedRunner = new RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner(
				hardened, Duration.ofSeconds(1));

		CommandResult command = hardenedRunner.run(
				state.root(), List.of("version"), new byte[0], 128);
		assertThat(command.exitCode()).isZero();
		assertThat(command.stdout()).containsExactly(
				"hardened\n".getBytes(StandardCharsets.US_ASCII));
		assertThat(command.stderr()).isEmpty();

		Path noisy = executableScript("noisy-git", "printf '123456789'\n");
		var noisyRunner = new RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner(
				noisy, Duration.ofSeconds(1));
		assertThatThrownBy(() -> noisyRunner.run(
				state.root(), List.of("version"), new byte[0], 4))
				.isInstanceOf(CollectionException.class)
				.hasMessage("HOLDOUT_GIT_COMMAND_OUTPUT_INVALID");

		Path sleeping = executableScript("sleeping-git", "/bin/sleep 5\n");
		var sleepingRunner = new RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner(
				sleeping, Duration.ofMillis(100));
		assertThatThrownBy(() -> sleepingRunner.run(
				state.root(), List.of("version"), new byte[128 * 1024], 16))
				.isInstanceOf(CollectionException.class)
				.hasMessage("HOLDOUT_GIT_COMMAND_TIMEOUT");

		Path contained = state.root().resolve("repository-controlled-git");
		writeExecutableScript(contained, "exit 0\n");
		var containedRunner = new RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner(
				contained, Duration.ofSeconds(1));
		assertThatThrownBy(() -> containedRunner.run(
				state.root(), List.of("version"), new byte[0], 16))
				.isInstanceOf(CollectionException.class)
				.hasMessage("HOLDOUT_GIT_COMMAND_INVALID");
		assertThatThrownBy(() -> hardenedRunner.run(
				state.root(), List.of("version"), new byte[0], Integer.MAX_VALUE))
				.isInstanceOf(CollectionException.class)
				.hasMessage("HOLDOUT_GIT_COMMAND_INVALID");
	}

	private CloneState cleanClone(String name) throws Exception {
		Path sourceRoot = Path.of(gitLine(
				Path.of("").toAbsolutePath(), "rev-parse", "--show-toplevel"));
		Path clone = temporaryDirectory.resolve(name);
		gitSuccess(
				temporaryDirectory,
				"clone", "--quiet", "--no-local", "--no-hardlinks", "--",
				sourceRoot.toString(), clone.toString());
		String evaluatorRevision = gitLine(
				clone, "rev-parse", "--verify", "HEAD^{commit}");
		List<SourceFile> evaluatorSources = committedSources(
				clone,
				evaluatorRevision,
				RelatedTopicReuseHoldoutGitCollector.evaluatorInventoryPaths());
		List<SourceFile> candidateSources = committedSources(
				clone,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				RelatedTopicReuseHoldoutGitCollector.candidateInventoryPaths());
		String evaluatorSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, evaluatorRevision, evaluatorSources);
		String candidateSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				candidateSources);
		FreezeRecord freeze = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorRevision,
				evaluatorSha256,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				candidateSha256);
		return new CloneState(
				clone.toRealPath(),
				evaluatorRevision,
				freeze,
				evaluatorSources,
				candidateSources);
	}

	private static List<SourceFile> committedSources(
			Path root, String revision, List<String> inventoryPaths) throws Exception {
		List<String> arguments = new ArrayList<>(List.of(
				"ls-tree", "-r", "-z", "--full-tree", "--long", revision, "--"));
		arguments.addAll(inventoryPaths.stream()
				.map(path -> ":(top,literal)" + path)
				.toList());
		byte[] tree = gitSuccess(root, arguments).stdout();
		List<TestTreeEntry> entries = new ArrayList<>();
		int start = 0;
		while (start < tree.length) {
			int end = start;
			while (end < tree.length && tree[end] != 0) {
				end++;
			}
			if (end == tree.length) {
				throw new AssertionError("test Git tree was not NUL terminated");
			}
			String record = new String(
					Arrays.copyOfRange(tree, start, end), StandardCharsets.UTF_8);
			int tab = record.indexOf('\t');
			String[] header = record.substring(0, tab).trim().split(" +");
			String path = record.substring(tab + 1);
			entries.add(new TestTreeEntry(
					Integer.parseInt(header[0]),
					path,
					header[2],
					Integer.parseInt(header[3])));
			start = end + 1;
		}
		ByteArrayOutputStream requests = new ByteArrayOutputStream();
		for (TestTreeEntry entry : entries) {
			requests.writeBytes(entry.objectId().getBytes(StandardCharsets.US_ASCII));
			requests.write('\n');
		}
		byte[] batch = gitSuccess(
				root, List.of("cat-file", "--batch"), requests.toByteArray()).stdout();
		List<SourceFile> sources = new ArrayList<>(entries.size());
		int offset = 0;
		for (TestTreeEntry entry : entries) {
			int headerEnd = indexOf(batch, (byte) '\n', offset);
			String header = new String(
					Arrays.copyOfRange(batch, offset, headerEnd), StandardCharsets.US_ASCII);
			assertThat(header).isEqualTo(
					entry.objectId() + " blob " + entry.bytes());
			int contentStart = headerEnd + 1;
			int contentEnd = Math.addExact(contentStart, entry.bytes());
			assertThat(batch[contentEnd]).isEqualTo((byte) '\n');
			sources.add(new SourceFile(
					entry.mode(),
					entry.path(),
					Arrays.copyOfRange(batch, contentStart, contentEnd)));
			offset = contentEnd + 1;
		}
		assertThat(offset).isEqualTo(batch.length);
		return List.copyOf(sources);
	}

	private static int indexOf(byte[] value, byte target, int start) {
		for (int index = start; index < value.length; index++) {
			if (value[index] == target) {
				return index;
			}
		}
		throw new AssertionError("test Git batch header was not terminated");
	}

	private static byte[] corruptBatch(byte[] valid, String corruption) {
		if (corruption.equals("truncated-header")) {
			return "truncated batch header\n".getBytes(StandardCharsets.US_ASCII);
		}
		byte[] changed = valid.clone();
		int headerEnd = indexOf(changed, (byte) '\n', 0);
		if (corruption.equals("wrong-object-id")) {
			changed[0] = changed[0] == '0' ? (byte) '1' : (byte) '0';
			return changed;
		}
		if (corruption.equals("wrong-type")) {
			changed[41] = 't';
			return changed;
		}
		if (corruption.equals("wrong-size")) {
			int lastSizeDigit = headerEnd - 1;
			changed[lastSizeDigit] = changed[lastSizeDigit] == '0'
					? (byte) '1'
					: (byte) '0';
			return changed;
		}
		String header = new String(
				Arrays.copyOfRange(changed, 0, headerEnd), StandardCharsets.US_ASCII);
		int bytes = Integer.parseInt(header.substring(header.lastIndexOf(' ') + 1));
		int delimiter = Math.addExact(headerEnd + 1, bytes);
		if (corruption.equals("missing-delimiter")) {
			return removeAt(changed, delimiter);
		}
		if (corruption.equals("truncated-content")) {
			return removeAt(changed, delimiter - 1);
		}
		if (corruption.equals("trailing-bytes")) {
			byte[] trailing = Arrays.copyOf(changed, changed.length + 1);
			trailing[trailing.length - 1] = 'x';
			return trailing;
		}
		throw new AssertionError("unknown test batch corruption: " + corruption);
	}

	private static byte[] removeAt(byte[] value, int removedIndex) {
		byte[] result = new byte[value.length - 1];
		System.arraycopy(value, 0, result, 0, removedIndex);
		System.arraycopy(
				value,
				removedIndex + 1,
				result,
				removedIndex,
				value.length - removedIndex - 1);
		return result;
	}

	private static void assertCollectionFailure(
			CloneState state, FreezeRecord freeze, String diagnostic) {
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutGitCollector
				.verifyCleanCheckout(state.root(), freeze, processGitRunner()))
				.isInstanceOf(CollectionException.class)
				.hasMessage(diagnostic)
				.hasMessageNotContaining(state.root().toString());
	}

	private static CommandResult collectorGit(
			Path root,
			List<String> arguments,
			byte[] standardInput,
			int maximumOutputBytes)
			throws IOException {
		List<String> command = new ArrayList<>();
		command.add("--no-replace-objects");
		command.addAll(arguments);
		ProcessOutput output = git(root, command, standardInput);
		if (output.stdout().length > maximumOutputBytes) {
			throw new IOException("test collector Git output exceeded its bound");
		}
		return new CommandResult(output.exitCode(), output.stdout(), output.stderr());
	}

	private static GitRunner processGitRunner() {
		return new RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner(GIT_EXECUTABLE);
	}

	private static String gitLine(Path root, String... arguments) throws Exception {
		String value = new String(
				gitSuccess(root, arguments).stdout(), StandardCharsets.UTF_8).strip();
		assertThat(value).doesNotContain("\n", "\r");
		return value;
	}

	private static ProcessOutput gitSuccess(Path root, String... arguments)
			throws Exception {
		return gitSuccess(root, List.of(arguments));
	}

	private static ProcessOutput gitSuccess(Path root, List<String> arguments)
			throws Exception {
		return gitSuccess(root, arguments, new byte[0]);
	}

	private static ProcessOutput gitSuccess(
			Path root, List<String> arguments, byte[] standardInput)
			throws Exception {
		ProcessOutput output = git(root, arguments, standardInput);
		assertThat(output.exitCode())
				.as(() -> "Git stderr: " + new String(
						output.stderr(), StandardCharsets.UTF_8))
				.isZero();
		assertThat(output.stderr()).isEmpty();
		return output;
	}

	private static ProcessOutput git(Path root, List<String> arguments)
			throws IOException {
		return git(root, arguments, new byte[0]);
	}

	private static ProcessOutput git(
			Path root, List<String> arguments, byte[] standardInput)
			throws IOException {
		Objects.requireNonNull(root, "root");
		List<String> command = new ArrayList<>();
		command.add(GIT_EXECUTABLE.toString());
		command.addAll(arguments);
		Process process = new ProcessBuilder(command)
				.directory(root.toFile())
				.redirectErrorStream(false)
				.start();
		AtomicReference<byte[]> stdout = new AtomicReference<>();
		AtomicReference<byte[]> stderr = new AtomicReference<>();
		AtomicReference<Throwable> readFailure = new AtomicReference<>();
		Thread out = read(process.getInputStream(), stdout, readFailure);
		Thread err = read(process.getErrorStream(), stderr, readFailure);
		try (var input = process.getOutputStream()) {
			input.write(standardInput);
		}
		boolean completed;
		try {
			completed = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("test Git command interrupted", exception);
		}
		if (!completed) {
			process.destroyForcibly();
			throw new IOException("test Git command timed out");
		}
		join(out);
		join(err);
		if (readFailure.get() != null) {
			throw new IOException("test Git output read failed", readFailure.get());
		}
		return new ProcessOutput(process.exitValue(), stdout.get(), stderr.get());
	}

	private static Thread read(
			InputStream input,
			AtomicReference<byte[]> target,
			AtomicReference<Throwable> failure) {
		return Thread.ofVirtual().start(() -> {
			try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				input.transferTo(output);
				target.set(output.toByteArray());
			}
			catch (Throwable exception) {
				failure.compareAndSet(null, exception);
			}
		});
	}

	private static void join(Thread thread) throws IOException {
		try {
			thread.join();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("test Git output read interrupted", exception);
		}
	}

	private Path executableScript(String name, String body) throws IOException {
		Path path = temporaryDirectory.resolve(name);
		writeExecutableScript(path, body);
		return path.toRealPath();
	}

	private static void writeExecutableScript(Path path, String body)
			throws IOException {
		Files.writeString(
				path,
				"#!/bin/sh\n" + body,
				StandardCharsets.US_ASCII,
				StandardOpenOption.CREATE_NEW);
		if (!path.toFile().setExecutable(true, false)) {
			throw new IOException("could not make the test Git executable runnable");
		}
	}

	private static Path locateGitExecutable() {
		String pathValue = System.getenv("PATH");
		if (pathValue != null) {
			for (String value : pathValue.split(Pattern.quote(File.pathSeparator), -1)) {
				try {
					Path directory = Path.of(value);
					if (value.isEmpty() || !directory.isAbsolute()) {
						continue;
					}
					for (String name : File.separatorChar == '\\'
							? List.of("git.exe", "git")
							: List.of("git")) {
						Path candidate = directory.resolve(name);
						if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
							return candidate.toRealPath();
						}
					}
				}
				catch (IOException | RuntimeException exception) {
					// Test discovery continues through the remaining absolute PATH entries.
				}
			}
		}
		throw new ExceptionInInitializerError("an absolute Git executable is required");
	}

	private record CloneState(
			Path root,
			String evaluatorRevision,
			FreezeRecord freeze,
			List<SourceFile> evaluatorSources,
			List<SourceFile> candidateSources) {
	}

	private record TestTreeEntry(
			int mode, String path, String objectId, int bytes) {
	}

	private record ProcessOutput(int exitCode, byte[] stdout, byte[] stderr) {

		private ProcessOutput {
			stdout = stdout.clone();
			stderr = stderr.clone();
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
}
