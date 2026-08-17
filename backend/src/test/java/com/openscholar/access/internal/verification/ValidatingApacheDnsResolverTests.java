package com.openscholar.access.internal.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;

class ValidatingApacheDnsResolverTests {

	@Test
	void suppliesOnlyPublicAnswersToTheConnectionLayer() throws Exception {
		InetAddress publicAddress = address("93.184.216.34");
		ValidatingApacheDnsResolver resolver = new ValidatingApacheDnsResolver(host -> List.of(publicAddress));

		assertThat(resolver.resolve("papers.example")).containsExactly(publicAddress);
	}

	@Test
	void refusesMixedOrPrivateAnswersAtConnectionTime() {
		ValidatingApacheDnsResolver resolver = new ValidatingApacheDnsResolver(
				host -> List.of(address("93.184.216.34"), address("10.0.0.1")));

		assertThatThrownBy(() -> resolver.resolve("papers.example"))
				.isInstanceOf(UnknownHostException.class)
				.hasMessage("Host did not resolve exclusively to public addresses");
	}

	private static InetAddress address(String value) {
		try {
			return InetAddress.getByName(value);
		}
		catch (UnknownHostException exception) {
			throw new IllegalArgumentException("Invalid test address", exception);
		}
	}
}
