package com.openscholar.access.internal.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;

class PublicAddressPolicyTests {

	@Test
	void acceptsOrdinaryPublicIpv4AndIpv6Addresses() {
		assertThat(List.of("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111"))
				.allSatisfy(value -> assertThat(PublicAddressPolicy.isPublic(address(value))).as(value).isTrue());
	}

	@Test
	void rejectsAdditionalSpecialPurposeRanges() {
		assertThat(List.of(
				"192.0.0.1",
				"240.0.0.1",
				"64:ff9b::c000:0201",
				"64:ff9b:1::1",
				"100::1",
				"2001::1"))
				.allSatisfy(value -> assertThat(PublicAddressPolicy.isPublic(address(value))).as(value).isFalse());
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
