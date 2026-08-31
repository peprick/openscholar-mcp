package com.openscholar.search.internal.persistence;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import org.postgresql.ds.PGSimpleDataSource;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.EndpointRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.PreflightException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.RuntimeFiles;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.VerifiedRuntimeConnectionSource;

/** Explicit reflection-only access to the TLS factory's private test seam. */
final class RelatedTopicReuseHoldoutPostgresTlsTestFixture {

	private static final Class<?> CONNECTION_OPENER = nested("ConnectionOpener");
	private static final Class<?> VALIDATED_CONFIGURATION =
			nested("ValidatedConfiguration");

	private RelatedTopicReuseHoldoutPostgresTlsTestFixture() {
	}

	static VerifiedRuntimeConnectionSource preflight(
			EndpointRecord endpoint,
			RuntimeFiles files,
			TestConnectionOpener opener) throws PreflightException {
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
		try {
			Method method = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.class
					.getDeclaredMethod(
							"preflightSource",
							EndpointRecord.class,
							RuntimeFiles.class,
							CONNECTION_OPENER);
			method.setAccessible(true);
			return (VerifiedRuntimeConnectionSource) method.invoke(
					null, endpoint, files, reflectedOpener);
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
		Object configuration = validate(endpoint, files);
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
		Object configuration = validate(endpoint, files);
		try {
			return configuration.toString();
		}
		finally {
			destroy(configuration);
		}
	}

	private static Object validate(EndpointRecord endpoint, RuntimeFiles files) {
		try {
			Method method = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.class
					.getDeclaredMethod("validate", EndpointRecord.class, RuntimeFiles.class);
			method.setAccessible(true);
			return method.invoke(null, endpoint, files);
		}
		catch (NoSuchMethodException | IllegalAccessException exception) {
			throw new AssertionError("TLS configuration test seam is unavailable", exception);
		}
		catch (InvocationTargetException exception) {
			throw unexpected(exception.getCause());
		}
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
}
