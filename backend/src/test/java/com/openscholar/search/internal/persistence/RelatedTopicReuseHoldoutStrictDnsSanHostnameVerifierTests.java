package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.Test;

class RelatedTopicReuseHoldoutStrictDnsSanHostnameVerifierTests {

	private static final String HOSTNAME = "ledger.example.test";

	private final RelatedTopicReuseHoldoutStrictDnsSanHostnameVerifier verifier =
			new RelatedTopicReuseHoldoutStrictDnsSanHostnameVerifier();

	@Test
	void acceptsOnlyAnExactDnsSubjectAlternativeName() throws Exception {
		SSLSession session = verifiedSession(List.of(
				List.of(7, "192.0.2.10"),
				List.of(2, "LEDGER.EXAMPLE.TEST")));

		assertThat(verifier.verify(HOSTNAME, session)).isTrue();
	}

	@Test
	void rejectsCnOnlyCertificateWhenSubjectAlternativeNamesAreNull() throws Exception {
		X509Certificate certificate = mock(X509Certificate.class);
		when(certificate.getSubjectX500Principal())
				.thenReturn(new X500Principal("CN=" + HOSTNAME));
		when(certificate.getSubjectAlternativeNames()).thenReturn(null);

		assertThat(verifier.verify(HOSTNAME, verifiedSession(certificate))).isFalse();
	}

	@Test
	void rejectsWildcardDnsSubjectAlternativeName() throws Exception {
		SSLSession session = verifiedSession(List.of(List.of(2, "*.example.test")));

		assertThat(verifier.verify(HOSTNAME, session)).isFalse();
	}

	@Test
	void rejectsWrongDnsSubjectAlternativeName() throws Exception {
		SSLSession session = verifiedSession(List.of(List.of(2, "other.example.test")));

		assertThat(verifier.verify(HOSTNAME, session)).isFalse();
	}

	@Test
	void rejectsMatchingTextInANonDnsSubjectAlternativeName() throws Exception {
		SSLSession session = verifiedSession(List.of(
				List.of(6, HOSTNAME),
				List.of(7, HOSTNAME)));

		assertThat(verifier.verify(HOSTNAME, session)).isFalse();
	}

	@Test
	void rejectsMalformedSubjectAlternativeNameEntries() throws Exception {
		SSLSession session = verifiedSession(List.of(
				List.of(2),
				List.of(2, HOSTNAME)));

		assertThat(verifier.verify(HOSTNAME, session)).isFalse();
	}

	@Test
	void rejectsUnverifiedSessionWithoutPropagatingItsDiagnostic() throws Exception {
		SSLSession session = mock(SSLSession.class);
		when(session.isValid()).thenReturn(true);
		when(session.getPeerCertificates()).thenThrow(
				new SSLPeerUnverifiedException("sensitive peer certificate details"));

		assertThat(verifier.verify(HOSTNAME, session)).isFalse();
	}

	@Test
	void rejectsCertificateParsingFailureWithoutPropagatingItsDiagnostic() throws Exception {
		X509Certificate certificate = mock(X509Certificate.class);
		when(certificate.getSubjectAlternativeNames()).thenThrow(
				new CertificateParsingException("sensitive certificate details"));

		assertThat(verifier.verify(HOSTNAME, verifiedSession(certificate))).isFalse();
	}

	@Test
	void rejectsNullAndInvalidSessions() {
		SSLSession invalidSession = mock(SSLSession.class);

		assertThat(verifier.verify(HOSTNAME, null)).isFalse();
		assertThat(verifier.verify(HOSTNAME, invalidSession)).isFalse();
	}

	private static SSLSession verifiedSession(Collection<List<?>> alternatives)
			throws Exception {
		X509Certificate certificate = mock(X509Certificate.class);
		when(certificate.getSubjectAlternativeNames()).thenReturn(alternatives);
		return verifiedSession(certificate);
	}

	private static SSLSession verifiedSession(X509Certificate certificate) throws Exception {
		SSLSession session = mock(SSLSession.class);
		when(session.isValid()).thenReturn(true);
		when(session.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
		return session;
	}
}
