package com.openscholar.access.internal.verification;

import java.net.InetAddress;

final class PublicAddressPolicy {

	private PublicAddressPolicy() {
	}

	static boolean isPublic(InetAddress address) {
		if (address == null
				|| address.isAnyLocalAddress()
				|| address.isLoopbackAddress()
				|| address.isLinkLocalAddress()
				|| address.isSiteLocalAddress()
				|| address.isMulticastAddress()) {
			return false;
		}

		byte[] bytes = address.getAddress();
		return switch (bytes.length) {
			case 4 -> isPublicIpv4(bytes);
			case 16 -> isPublicIpv6(bytes);
			default -> false;
		};
	}

	private static boolean isPublicIpv4(byte[] bytes) {
		int first = unsigned(bytes[0]);
		int second = unsigned(bytes[1]);
		int third = unsigned(bytes[2]);

		if (first == 0 || first == 10 || first == 127 || first >= 224) {
			return false;
		}
		if (first == 100 && second >= 64 && second <= 127) {
			return false;
		}
		if (first == 169 && second == 254) {
			return false;
		}
		if (first == 172 && second >= 16 && second <= 31) {
			return false;
		}
		if (first == 192 && (second == 0 || second == 168)) {
			return false;
		}
		if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) {
			return false;
		}
		return !(first == 203 && second == 0 && third == 113);
	}

	private static boolean isPublicIpv6(byte[] bytes) {
		if (matchesPrefix(bytes, new byte[] {
				0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}, 96)
				|| matchesPrefix(bytes, new byte[] {0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0x00, 0x01}, 48)
				|| matchesPrefix(bytes, new byte[] {0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}, 64)
				|| matchesPrefix(bytes, new byte[] {0x20, 0x01, 0x00, 0x00}, 32)
				|| matchesPrefix(bytes, new byte[] {0x20, 0x01, 0x00, 0x02, 0x00, 0x00}, 48)
				|| matchesPrefix(bytes, new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8}, 32)
				|| matchesPrefix(bytes, new byte[] {(byte) 0xfc}, 7)) {
			return false;
		}

		return true;
	}

	private static boolean matchesPrefix(byte[] address, byte[] prefix, int prefixBits) {
		int wholeBytes = prefixBits / 8;
		int remainingBits = prefixBits % 8;
		for (int index = 0; index < wholeBytes; index++) {
			if (address[index] != prefix[index]) {
				return false;
			}
		}
		if (remainingBits == 0) {
			return true;
		}
		int mask = 0xff << (8 - remainingBits);
		return (unsigned(address[wholeBytes]) & mask) == (unsigned(prefix[wholeBytes]) & mask);
	}

	private static int unsigned(byte value) {
		return Byte.toUnsignedInt(value);
	}
}
