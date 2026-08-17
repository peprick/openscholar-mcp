package com.openscholar.access.internal.verification;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

final class ValidatingApacheDnsResolver implements org.apache.hc.client5.http.DnsResolver {

	private final DnsResolver delegate;

	ValidatingApacheDnsResolver(DnsResolver delegate) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		List<InetAddress> addresses;
		try {
			addresses = delegate.resolve(host);
		}
		catch (RuntimeException exception) {
			throw rejectedHost();
		}
		if (addresses == null || addresses.isEmpty() || !addresses.stream().allMatch(PublicAddressPolicy::isPublic)) {
			throw rejectedHost();
		}
		return addresses.toArray(InetAddress[]::new);
	}

	@Override
	public String resolveCanonicalHostname(String host) throws UnknownHostException {
		resolve(host);
		return host;
	}

	private static UnknownHostException rejectedHost() {
		return new UnknownHostException("Host did not resolve exclusively to public addresses");
	}
}
