package com.openscholar.access.internal.verification;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

@FunctionalInterface
interface DnsResolver {

	List<InetAddress> resolve(String host) throws UnknownHostException;

	static DnsResolver system() {
		return host -> List.copyOf(Arrays.asList(InetAddress.getAllByName(host)));
	}
}
