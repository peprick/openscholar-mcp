package com.openscholar.search.internal.persistence;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import javax.sql.DataSource;

/** Explicit reflection-only bypass for plaintext synthetic ledger mechanics. */
final class RelatedTopicReuseHoldoutLedgerTestFixture {

	private RelatedTopicReuseHoldoutLedgerTestFixture() {
	}

	static RelatedTopicReuseHoldoutPostgresFirstRunLedger create(DataSource dataSource) {
		try {
			Constructor<RelatedTopicReuseHoldoutPostgresFirstRunLedger> constructor =
					RelatedTopicReuseHoldoutPostgresFirstRunLedger.class
							.getDeclaredConstructor(DataSource.class);
			constructor.setAccessible(true);
			return constructor.newInstance(dataSource);
		}
		catch (NoSuchMethodException
				| InstantiationException
				| IllegalAccessException
				| InvocationTargetException exception) {
			throw new AssertionError("plaintext ledger test fixture is unavailable", exception);
		}
	}
}
