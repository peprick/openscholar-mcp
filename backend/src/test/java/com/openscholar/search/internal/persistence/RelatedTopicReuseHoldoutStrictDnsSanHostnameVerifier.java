package com.openscholar.search.internal.persistence;

import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Fixed hostname verifier that accepts only an exact DNS subjectAlternativeName.
 *
 * <p>The verifier deliberately does not inspect the certificate subject, so a
 * common-name match cannot be used as a fallback. Wildcard DNS names are never
 * matches. Invalid sessions and malformed certificate metadata fail closed
 * without exposing certificate details through exceptions or diagnostics.</p>
 */
public final class RelatedTopicReuseHoldoutStrictDnsSanHostnameVerifier
		implements HostnameVerifier {

	private static final int DNS_SUBJECT_ALTERNATIVE_NAME = 2;
	private static final Pattern DNS_HOST = Pattern.compile(
			"(?=.{1,253}\\z)(?=.*[a-z])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
					+ "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");

	@Override
	public boolean verify(String hostname, SSLSession session) {
		if (!isDnsHostname(hostname) || session == null) {
			return false;
		}

		try {
			if (!session.isValid()) {
				return false;
			}
			Certificate[] peerCertificates = session.getPeerCertificates();
			if (peerCertificates == null || peerCertificates.length == 0
					|| !(peerCertificates[0] instanceof X509Certificate leafCertificate)) {
				return false;
			}

			Collection<List<?>> alternatives = leafCertificate.getSubjectAlternativeNames();
			if (alternatives == null || alternatives.isEmpty()) {
				return false;
			}

			boolean exactDnsMatch = false;
			for (List<?> alternative : alternatives) {
				if (alternative == null || alternative.size() != 2
						|| !(alternative.get(0) instanceof Integer type)) {
					return false;
				}
				if (type != DNS_SUBJECT_ALTERNATIVE_NAME) {
					continue;
				}
				if (!(alternative.get(1) instanceof String dnsName)
						|| !isDnsHostname(dnsName)) {
					return false;
				}
				if (hostname.equalsIgnoreCase(dnsName)) {
					exactDnsMatch = true;
				}
			}
			return exactDnsMatch;
		}
		catch (SSLPeerUnverifiedException | CertificateParsingException
				| RuntimeException ignored) {
			return false;
		}
	}

	private static boolean isDnsHostname(String value) {
		if (value == null || value.indexOf('*') >= 0) {
			return false;
		}
		return DNS_HOST.matcher(value.toLowerCase(Locale.ROOT)).matches();
	}
}
