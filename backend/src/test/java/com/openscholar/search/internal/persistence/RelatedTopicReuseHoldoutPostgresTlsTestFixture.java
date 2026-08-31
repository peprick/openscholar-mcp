package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import org.postgresql.ds.PGSimpleDataSource;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.EndpointRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.PreflightException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.RuntimeFiles;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.VerifiedRuntimeConnectionSource;

/** Explicit reflection-only access to the TLS factory's private test seam. */
final class RelatedTopicReuseHoldoutPostgresTlsTestFixture {

	private static final Class<?> CONNECTION_OPENER = nested("ConnectionOpener");
	private static final Class<?> FILE_ACCESS_INSPECTOR =
			nested("FileAccessInspector");
	private static final Class<?> VALIDATED_CONFIGURATION =
			nested("ValidatedConfiguration");

	private RelatedTopicReuseHoldoutPostgresTlsTestFixture() {
	}

	static VerifiedRuntimeConnectionSource preflight(
			EndpointRecord endpoint,
			RuntimeFiles files,
			TestConnectionOpener opener) throws PreflightException {
		return preflight(endpoint, files, opener, secureFileAccess(files));
	}

	static VerifiedRuntimeConnectionSource preflight(
			EndpointRecord endpoint,
			RuntimeFiles files,
			TestConnectionOpener opener,
			TestFileAccessInspector fileAccess) throws PreflightException {
		Object reflectedOpener = Proxy.newProxyInstance(
				CONNECTION_OPENER.getClassLoader(),
				new Class<?>[] {CONNECTION_OPENER},
				(proxy, method, arguments) -> switch (method.getName()) {
					case "open" -> opener.open((char[]) arguments[1]);
					case "toString" -> "ReflectionOnlyConnectionOpener";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == arguments[0];
					default -> throw new AssertionError(
							"unexpected connection-opener method: " + method);
				});
		Object reflectedFileAccess = reflectedFileAccess(fileAccess);
		try {
			Method method = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.class
					.getDeclaredMethod(
							"preflightSource",
							EndpointRecord.class,
							RuntimeFiles.class,
							CONNECTION_OPENER,
							FILE_ACCESS_INSPECTOR);
			method.setAccessible(true);
			return (VerifiedRuntimeConnectionSource) method.invoke(
					null, endpoint, files, reflectedOpener, reflectedFileAccess);
		}
		catch (NoSuchMethodException | IllegalAccessException exception) {
			throw new AssertionError("TLS preflight test seam is unavailable", exception);
		}
		catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof PreflightException preflightException) {
				throw preflightException;
			}
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new AssertionError("unexpected TLS preflight failure", cause);
		}
	}

	static PGSimpleDataSource configuredDataSource(
			EndpointRecord endpoint, RuntimeFiles files) {
		return configuredDataSource(endpoint, files, secureFileAccess(files));
	}

	static PGSimpleDataSource configuredDataSource(
			EndpointRecord endpoint,
			RuntimeFiles files,
			TestFileAccessInspector fileAccess) {
		Object configuration = validate(endpoint, files, fileAccess);
		try {
			Method method = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.class
					.getDeclaredMethod("configuredDataSource", VALIDATED_CONFIGURATION);
			method.setAccessible(true);
			return (PGSimpleDataSource) method.invoke(null, configuration);
		}
		catch (NoSuchMethodException | IllegalAccessException exception) {
			throw new AssertionError("TLS data-source test seam is unavailable", exception);
		}
		catch (InvocationTargetException exception) {
			throw unexpected(exception.getCause());
		}
		finally {
			destroy(configuration);
		}
	}

	static String validatedConfigurationText(EndpointRecord endpoint, RuntimeFiles files) {
		return validatedConfigurationText(endpoint, files, secureFileAccess(files));
	}

	static String validatedConfigurationText(
			EndpointRecord endpoint,
			RuntimeFiles files,
			TestFileAccessInspector fileAccess) {
		Object configuration = validate(endpoint, files, fileAccess);
		try {
			return configuration.toString();
		}
		finally {
			destroy(configuration);
		}
	}

	static TestFileAccessInspector secureFileAccess(RuntimeFiles files) {
		Path caCertificate = files.caCertificate();
		Path caDirectory = caCertificate.getParent();
		Path passwordFile = files.runtimePassword();
		Path passwordDirectory = passwordFile.getParent();
		return new TestFileAccessInspector() {
			@Override
			public String owner(Path path) {
				if (path.equals(caCertificate) || path.equals(caDirectory)) {
					return files.expectedCaOwner();
				}
				if (path.equals(passwordFile) || path.equals(passwordDirectory)) {
					return files.expectedOwner();
				}
				return "synthetic-unrelated-owner";
			}

			@Override
			public boolean readable(Path path) {
				return true;
			}

			@Override
			public boolean writable(Path path) {
				return false;
			}

			@Override
			public Set<PosixFilePermission> permissions(Path path) throws IOException {
				if (path.equals(caCertificate)
						|| path.equals(caDirectory)
						|| path.equals(passwordFile)
						|| path.equals(passwordDirectory)) {
					return Set.copyOf(Files.getPosixFilePermissions(
							path, LinkOption.NOFOLLOW_LINKS));
				}
				return Set.of(
						PosixFilePermission.OWNER_READ,
						PosixFilePermission.OWNER_EXECUTE,
						PosixFilePermission.GROUP_READ,
						PosixFilePermission.GROUP_EXECUTE,
						PosixFilePermission.OTHERS_READ,
						PosixFilePermission.OTHERS_EXECUTE);
			}

			@Override
			public boolean hasVisibleAcl(Path path) {
				return false;
			}
		};
	}

	static TestFileAccessInspector reportingWritable(
			TestFileAccessInspector delegate, Path writablePath) {
		return new DelegatingFileAccessInspector(delegate) {
			@Override
			public boolean writable(Path path) throws IOException {
				return path.equals(writablePath) || super.writable(path);
			}
		};
	}

	static TestFileAccessInspector reportingVisibleAcl(
			TestFileAccessInspector delegate, Path aclPath) {
		return new DelegatingFileAccessInspector(delegate) {
			@Override
			public boolean hasVisibleAcl(Path path) throws IOException {
				return path.equals(aclPath) || super.hasVisibleAcl(path);
			}
		};
	}

	static TestFileAccessInspector reportingGroupOrOtherWritable(
			TestFileAccessInspector delegate, Path writablePath) {
		return new DelegatingFileAccessInspector(delegate) {
			@Override
			public Set<PosixFilePermission> permissions(Path path) throws IOException {
				if (path.equals(writablePath)) {
					return Set.of(PosixFilePermission.GROUP_WRITE);
				}
				return super.permissions(path);
			}
		};
	}

	private static Object validate(
			EndpointRecord endpoint,
			RuntimeFiles files,
			TestFileAccessInspector fileAccess) {
		try {
			Method method = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.class
					.getDeclaredMethod(
							"validate",
							EndpointRecord.class,
							RuntimeFiles.class,
							FILE_ACCESS_INSPECTOR);
			method.setAccessible(true);
			return method.invoke(null, endpoint, files, reflectedFileAccess(fileAccess));
		}
		catch (NoSuchMethodException | IllegalAccessException exception) {
			throw new AssertionError("TLS configuration test seam is unavailable", exception);
		}
		catch (InvocationTargetException exception) {
			throw unexpected(exception.getCause());
		}
	}

	private static Object reflectedFileAccess(TestFileAccessInspector fileAccess) {
		return Proxy.newProxyInstance(
				FILE_ACCESS_INSPECTOR.getClassLoader(),
				new Class<?>[] {FILE_ACCESS_INSPECTOR},
				(proxy, method, arguments) -> switch (method.getName()) {
					case "owner" -> fileAccess.owner((Path) arguments[0]);
					case "readable" -> fileAccess.readable((Path) arguments[0]);
					case "writable" -> fileAccess.writable((Path) arguments[0]);
					case "permissions" -> fileAccess.permissions((Path) arguments[0]);
					case "hasVisibleAcl" -> fileAccess.hasVisibleAcl((Path) arguments[0]);
					case "toString" -> "ReflectionOnlyFileAccessInspector";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == arguments[0];
					default -> throw new AssertionError(
							"unexpected file-access method: " + method);
				});
	}

	private static Class<?> nested(String simpleName) {
		try {
			return Class.forName(
					RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.class.getName()
							+ '$' + simpleName);
		}
		catch (ClassNotFoundException exception) {
			throw new AssertionError("TLS factory nested type is unavailable", exception);
		}
	}

	private static void destroy(Object configuration) {
		try {
			Method method = VALIDATED_CONFIGURATION.getDeclaredMethod(
					"destroyPasswordBinding");
			method.setAccessible(true);
			method.invoke(configuration);
		}
		catch (NoSuchMethodException | IllegalAccessException exception) {
			throw new AssertionError("TLS configuration cleanup seam is unavailable", exception);
		}
		catch (InvocationTargetException exception) {
			throw unexpected(exception.getCause());
		}
	}

	private static AssertionError unexpected(Throwable cause) {
		if (cause instanceof Error error) {
			throw error;
		}
		return new AssertionError("unexpected reflected TLS factory failure", cause);
	}

	@FunctionalInterface
	interface TestConnectionOpener {

		Connection open(char[] password) throws SQLException;
	}

	interface TestFileAccessInspector {

		String owner(Path path) throws IOException;

		boolean readable(Path path) throws IOException;

		boolean writable(Path path) throws IOException;

		Set<PosixFilePermission> permissions(Path path) throws IOException;

		boolean hasVisibleAcl(Path path) throws IOException;
	}

	private static class DelegatingFileAccessInspector
			implements TestFileAccessInspector {

		private final TestFileAccessInspector delegate;

		private DelegatingFileAccessInspector(TestFileAccessInspector delegate) {
			this.delegate = delegate;
		}

		@Override
		public String owner(Path path) throws IOException {
			return delegate.owner(path);
		}

		@Override
		public boolean readable(Path path) throws IOException {
			return delegate.readable(path);
		}

		@Override
		public boolean writable(Path path) throws IOException {
			return delegate.writable(path);
		}

		@Override
		public Set<PosixFilePermission> permissions(Path path) throws IOException {
			return delegate.permissions(path);
		}

		@Override
		public boolean hasVisibleAcl(Path path) throws IOException {
			return delegate.hasVisibleAcl(path);
		}
	}
}
