package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.EndpointRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.PreflightException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.RuntimeFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Explicit-only live timeout proof for the disposable PostgreSQL TLS harness.
 * The class name deliberately falls outside normal Surefire discovery.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RelatedTopicReuseHoldoutPostgresTlsTimeoutLiveIntegrationIT {

	private static final int TLS_PROXY_PORT = 5432;
	private static final int TCP_STALL_PORT = 5433;
	private static final Duration TEN_SECOND_LOWER_BOUND = Duration.ofSeconds(7);
	private static final Duration TEN_SECOND_UPPER_BOUND = Duration.ofSeconds(18);
	private static final Duration FIFTEEN_SECOND_LOWER_BOUND = Duration.ofSeconds(12);
	private static final Duration FIFTEEN_SECOND_UPPER_BOUND = Duration.ofSeconds(24);

	@Test
	@Timeout(30)
	void saturatedTcpAcceptQueueBoundsConnectionAcquisition() throws Exception {
		String host = required("HOLDOUT_TLS_HOST");
		String runnerAddress = required("HOLDOUT_TIMEOUT_RUNNER_ADDRESS");
		assertHostResolvesTo(host, runnerAddress);
		try (SaturatedAcceptQueue stall = new SaturatedAcceptQueue(TCP_STALL_PORT)) {
			stall.saturate(host);
			assertThat(stall.queuedConnections()).isPositive();
			assertThatThrownBy(() -> assertTcpConnects(host, TCP_STALL_PORT, 1_000))
					.isExactlyInstanceOf(SocketTimeoutException.class);

			TimedFailure failure = timeFailure(() ->
					RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.preflight(
							tlsEndpoint(host, TCP_STALL_PORT, runnerAddress),
							runtimeFiles()));

			assertSafeConnectionFailure(failure);
			assertBounded(
					failure.elapsed(),
					TEN_SECOND_LOWER_BOUND,
					TEN_SECOND_UPPER_BOUND);
		}
	}

	@Test
	@Timeout(30)
	void directTlsHandshakeStallIsBoundedWithoutRetry() throws Exception {
		try (TransparentTcpProxy proxy = TransparentTcpProxy.start(
				TLS_PROXY_PORT,
				required("HOLDOUT_TLS_TARGET_ADDRESS"),
				requiredPort("HOLDOUT_TLS_PORT"))) {
			proxy.withholdDownstream();

			TimedFailure failure = timeFailure(() ->
					RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.preflight(
							tlsEndpoint(), runtimeFiles()));

			assertSafeConnectionFailure(failure);
			assertBounded(
					failure.elapsed(),
					TEN_SECOND_LOWER_BOUND,
					TEN_SECOND_UPPER_BOUND);
			assertThat(proxy.awaitWithheldBytes()).isTrue();
			assertThat(proxy.acceptedConnections()).isOne();
			assertThat(proxy.upstreamBytes()).isPositive();
			assertThat(proxy.withheldDownstreamBytes()).isPositive();
			assertThat(proxy.acceptFailure()).isNull();
		}
	}

	@Test
	@Timeout(35)
	void verifiedPhaseBReadStallHitsSocketDeadlineWithoutReconnect()
			throws Exception {
		try (TransparentTcpProxy proxy = TransparentTcpProxy.start(
				TLS_PROXY_PORT,
				required("HOLDOUT_TLS_TARGET_ADDRESS"),
				requiredPort("HOLDOUT_TLS_PORT"))) {
			var ledger = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.preflight(
					tlsEndpoint(), runtimeFiles());
			try (Connection connection = openBoundPhaseBConnection(ledger)) {
				assertThat(connection.getNetworkTimeout())
						.isEqualTo(RelatedTopicReuseHoldoutPostgresTlsConnectionFactory
								.NETWORK_TIMEOUT_MILLIS);
				assertSingleInteger(connection, "SELECT 1", 1);
				assertThat(proxy.acceptedConnections()).isEqualTo(2);
				proxy.withholdDownstream();

				TimedFailure failure = timeFailure(() ->
						assertSingleInteger(connection, "SELECT 424242", 424242));

				assertThat(failure.failure())
						.isInstanceOf(SQLException.class)
						.satisfies(throwable -> assertThat(
								((SQLException) throwable).getSQLState())
										.isEqualTo("08006"));
				assertThat(rootCause(failure.failure()))
						.isExactlyInstanceOf(SocketTimeoutException.class);
				assertBounded(
						failure.elapsed(),
						FIFTEEN_SECOND_LOWER_BOUND,
						FIFTEEN_SECOND_UPPER_BOUND);
				assertThat(proxy.awaitWithheldBytes()).isTrue();
				assertThat(proxy.acceptedConnections()).isEqualTo(2);
				assertThat(proxy.withheldDownstreamBytes()).isPositive();
			}
			assertThat(proxy.acceptFailure()).isNull();
		}
	}

	@Test
	@Timeout(30)
	void serverStatementTimeoutCancelsLongQueryBeforeSocketDeadline()
			throws Exception {
		try (TransparentTcpProxy proxy = TransparentTcpProxy.start(
				TLS_PROXY_PORT,
				required("HOLDOUT_TLS_TARGET_ADDRESS"),
				requiredPort("HOLDOUT_TLS_PORT"))) {
			var ledger = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.preflight(
					tlsEndpoint(), runtimeFiles());
			try (Connection connection = openBoundPhaseBConnection(ledger);
					Statement statement = connection.createStatement()) {
				try (ResultSet result = statement.executeQuery("SHOW statement_timeout")) {
					assertThat(result.next()).isTrue();
					assertThat(result.getString(1)).isEqualTo("10s");
					assertThat(result.next()).isFalse();
				}

				TimedFailure failure = timeFailure(() ->
						statement.executeQuery("SELECT pg_sleep(30)"));

				assertThat(failure.failure())
						.isInstanceOf(SQLException.class)
						.satisfies(throwable -> assertThat(
								((SQLException) throwable).getSQLState())
										.isEqualTo("57014"));
				assertThat(rootCause(failure.failure()))
						.isNotInstanceOf(SocketTimeoutException.class);
				assertBounded(
						failure.elapsed(),
						TEN_SECOND_LOWER_BOUND,
						TEN_SECOND_UPPER_BOUND);
				assertSingleInteger(connection, "SELECT 1", 1);
			}
			assertThat(proxy.acceptedConnections()).isEqualTo(2);
			assertThat(proxy.withheldDownstreamBytes()).isZero();
			assertThat(proxy.acceptFailure()).isNull();
		}
	}

	private static EndpointRecord tlsEndpoint() throws IOException {
		return tlsEndpoint(
				required("HOLDOUT_TLS_HOST"),
				requiredPort("HOLDOUT_TLS_PORT"),
				required("HOLDOUT_TLS_SERVER_ADDRESS"));
	}

	private static EndpointRecord tlsEndpoint(
			String host, int port, String serverAddress) throws IOException {
		Path caCertificate = path("HOLDOUT_CA_CERTIFICATE");
		return new EndpointRecord(
				RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.ENDPOINT_SCHEMA_VERSION,
				host,
				port,
				serverAddress,
				Files.readString(path("HOLDOUT_SERVER_VERSION_FILE")).strip(),
				required("HOLDOUT_TLS_PROTOCOL"),
				required("HOLDOUT_TLS_CIPHER"),
				requiredInteger("HOLDOUT_TLS_BITS"),
				sha256(caCertificate),
				leafCertificateSha256());
	}

	private static RuntimeFiles runtimeFiles() {
		return new RuntimeFiles(
				path("HOLDOUT_REPOSITORY_ROOT"),
				path("HOLDOUT_CA_CERTIFICATE"),
				path("HOLDOUT_RUNTIME_PASSWORD_FILE"),
				required("HOLDOUT_EXPECTED_CA_OWNER"),
				required("HOLDOUT_EXPECTED_OWNER"));
	}

	private static void assertSafeConnectionFailure(TimedFailure timed) {
		assertThat(timed.failure())
				.isExactlyInstanceOf(PreflightException.class)
				.hasMessage("HOLDOUT_LEDGER_TLS_CONNECTION_FAILED")
				.hasNoCause();
	}

	private static void assertBounded(
			Duration observed, Duration lower, Duration upper) {
		assertThat(observed)
				.as("monotonic elapsed time")
				.isBetween(lower, upper);
	}

	private static TimedFailure timeFailure(CheckedOperation operation) {
		long started = System.nanoTime();
		Throwable failure = null;
		try {
			operation.run();
		}
		catch (Throwable throwable) {
			failure = throwable;
		}
		if (failure == null) {
			throw new AssertionError("the bounded operation unexpectedly succeeded");
		}
		return new TimedFailure(
				failure, Duration.ofNanos(System.nanoTime() - started));
	}

	private static void assertSingleInteger(
			Connection connection, String sql, int expected) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			assertThat(result.getInt(1)).isEqualTo(expected);
			assertThat(result.next()).isFalse();
		}
	}

	private static void assertTcpConnects(String host, int port, int timeoutMillis)
			throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMillis);
		}
	}

	private static void assertHostResolvesTo(String host, String expectedAddress)
			throws IOException {
		assertThat(InetAddress.getAllByName(host))
				.extracting(InetAddress::getHostAddress)
				.containsExactly(expectedAddress);
	}

	private static Connection openBoundPhaseBConnection(
			RelatedTopicReuseHoldoutPostgresFirstRunLedger ledger) throws SQLException {
		try {
			Field sourceField = RelatedTopicReuseHoldoutPostgresFirstRunLedger.class
					.getDeclaredField("connectionSource");
			sourceField.setAccessible(true);
			Object source = sourceField.get(ledger);
			Class<?> sourceType = Class.forName(
					RelatedTopicReuseHoldoutPostgresFirstRunLedger.class.getName()
							+ "$ClaimConnectionSource");
			Method open = sourceType.getDeclaredMethod("open");
			open.setAccessible(true);
			return (Connection) open.invoke(source);
		}
		catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof SQLException sqlException) {
				throw sqlException;
			}
			throw new AssertionError("unexpected Phase-B connection failure", cause);
		}
		catch (ReflectiveOperationException | RuntimeException exception) {
			throw new AssertionError(
					"the ledger's bounded Phase-B test seam is unavailable", exception);
		}
	}

	private static Throwable rootCause(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

	private static Path path(String name) {
		return Path.of(required(name));
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required by the timeout harness");
		}
		return value;
	}

	private static int requiredPort(String name) {
		int value = requiredInteger(name);
		if (value < 1 || value > 65_535) {
			throw new IllegalStateException(name + " must be a valid TCP port");
		}
		return value;
	}

	private static int requiredInteger(String name) {
		try {
			return Integer.parseInt(required(name));
		}
		catch (NumberFormatException exception) {
			throw new IllegalStateException(name + " must be an integer", exception);
		}
	}

	private static String sha256(Path path) throws IOException {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String leafCertificateSha256() throws IOException {
		String digest = Files.readString(
				path("HOLDOUT_LEAF_CERTIFICATE_SHA256_FILE")).strip();
		if (!digest.matches("[0-9a-f]{64}")) {
			throw new IllegalStateException("leaf certificate digest is invalid");
		}
		return digest;
	}

	@FunctionalInterface
	private interface CheckedOperation {

		void run() throws Exception;
	}

	private record TimedFailure(Throwable failure, Duration elapsed) {
	}

	private static final class SaturatedAcceptQueue implements AutoCloseable {

		private static final int MAXIMUM_FILL_ATTEMPTS = 1_024;

		private final ServerSocket listener;
		private final Set<Socket> queued = ConcurrentHashMap.newKeySet();

		private SaturatedAcceptQueue(int port) throws IOException {
			listener = new ServerSocket();
			listener.setReuseAddress(true);
			listener.bind(new InetSocketAddress("0.0.0.0", port), 1);
		}

		private void saturate(String host) throws IOException {
			for (int attempt = 0; attempt < MAXIMUM_FILL_ATTEMPTS; attempt++) {
				Socket socket = new Socket();
				try {
					socket.connect(
							new InetSocketAddress(host, listener.getLocalPort()), 100);
					queued.add(socket);
				}
				catch (SocketTimeoutException expected) {
					socket.close();
					return;
				}
				catch (IOException failure) {
					socket.close();
					throw failure;
				}
			}
			throw new IOException("could not saturate the TCP accept queue");
		}

		private int queuedConnections() {
			return queued.size();
		}

		@Override
		public void close() throws IOException {
			IOException failure = null;
			for (Socket socket : queued) {
				try {
					socket.close();
				}
				catch (IOException exception) {
					failure = exception;
				}
			}
			try {
				listener.close();
			}
			catch (IOException exception) {
				failure = exception;
			}
			if (failure != null) {
				throw failure;
			}
		}
	}

	private static final class TransparentTcpProxy implements AutoCloseable {

		private final ServerSocket listener;
		private final InetSocketAddress target;
		private final ExecutorService executor;
		private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
		private final AtomicBoolean closed = new AtomicBoolean();
		private final AtomicBoolean withholdDownstream = new AtomicBoolean();
		private final AtomicInteger acceptedConnections = new AtomicInteger();
		private final AtomicLong upstreamBytes = new AtomicLong();
		private final AtomicLong withheldDownstreamBytes = new AtomicLong();
		private final AtomicReference<Throwable> acceptFailure = new AtomicReference<>();
		private final CountDownLatch withheldBytes = new CountDownLatch(1);

		private TransparentTcpProxy(
				int listenPort, String targetAddress, int targetPort) throws IOException {
			AtomicInteger threadNumber = new AtomicInteger();
			executor = Executors.newCachedThreadPool(runnable -> {
				Thread thread = new Thread(
						runnable,
						"holdout-timeout-proxy-" + threadNumber.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			});
			target = new InetSocketAddress(
					InetAddress.getByName(targetAddress), targetPort);
			listener = new ServerSocket();
			listener.setReuseAddress(true);
			listener.bind(new InetSocketAddress("0.0.0.0", listenPort), 16);
			executor.execute(this::acceptLoop);
		}

		private static TransparentTcpProxy start(
				int listenPort, String targetAddress, int targetPort) throws IOException {
			return new TransparentTcpProxy(listenPort, targetAddress, targetPort);
		}

		private void acceptLoop() {
			while (!closed.get()) {
				Socket client = null;
				Socket upstream = null;
				try {
					client = listener.accept();
					client.setTcpNoDelay(true);
					upstream = new Socket();
					upstream.connect(target, 5_000);
					upstream.setTcpNoDelay(true);
					sockets.add(client);
					sockets.add(upstream);
					acceptedConnections.incrementAndGet();
					Socket acceptedClient = client;
					Socket acceptedUpstream = upstream;
					executor.execute(() -> pump(
							acceptedClient, acceptedUpstream, false));
					executor.execute(() -> pump(
							acceptedUpstream, acceptedClient, true));
				}
				catch (IOException exception) {
					closeQuietly(client);
					closeQuietly(upstream);
					if (!closed.get()) {
						acceptFailure.compareAndSet(null, exception);
					}
				}
			}
		}

		private void pump(Socket source, Socket destination, boolean downstream) {
			byte[] buffer = new byte[8_192];
			try (InputStream input = source.getInputStream();
					OutputStream output = destination.getOutputStream()) {
				int read;
				while ((read = input.read(buffer)) >= 0) {
					if (read == 0) {
						continue;
					}
					if (downstream && withholdDownstream.get()) {
						withheldDownstreamBytes.addAndGet(read);
						withheldBytes.countDown();
						continue;
					}
					output.write(buffer, 0, read);
					output.flush();
					if (!downstream) {
						upstreamBytes.addAndGet(read);
					}
				}
			}
			catch (IOException ignored) {
				// Socket closure and timeout are expected fault-fixture termination paths.
			}
			finally {
				closeQuietly(source);
				closeQuietly(destination);
			}
		}

		private void withholdDownstream() {
			withholdDownstream.set(true);
		}

		private boolean awaitWithheldBytes() throws InterruptedException {
			return withheldBytes.await(2, TimeUnit.SECONDS);
		}

		private int acceptedConnections() {
			return acceptedConnections.get();
		}

		private long upstreamBytes() {
			return upstreamBytes.get();
		}

		private long withheldDownstreamBytes() {
			return withheldDownstreamBytes.get();
		}

		private Throwable acceptFailure() {
			return acceptFailure.get();
		}

		@Override
		public void close() throws IOException {
			closed.set(true);
			listener.close();
			for (Socket socket : sockets) {
				closeQuietly(socket);
			}
			executor.shutdownNow();
			try {
				if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
					throw new IOException("timeout proxy threads did not terminate");
				}
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted while stopping timeout proxy", exception);
			}
		}

		private static void closeQuietly(Socket socket) {
			if (socket == null) {
				return;
			}
			try {
				socket.close();
			}
			catch (SocketException ignored) {
				// Already closed.
			}
			catch (IOException ignored) {
				// Best-effort fixture cleanup; outer close still shuts down the executor.
			}
		}
	}
}
