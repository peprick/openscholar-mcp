package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;

class RelatedTopicReuseHoldoutPinnedDnsSanHostnameVerifierTests {

	private static final String HOSTNAME = "ledger.example.test";
	private static final byte[] LEAF_DER =
			"synthetic-leaf-der".getBytes(StandardCharsets.US_ASCII);

	@Test
	void acceptsOnlyThePinnedLeafWithAnExactDnsSubjectAlternativeName()
			throws Exception {
		var verifier = verifierFor(LEAF_DER);

		assertThat(verifier.verify(
				HOSTNAME,
				verifiedSession(certificate(
						LEAF_DER, List.of(List.of(2, "LEDGER.EXAMPLE.TEST"))))))
				.isTrue();
	}

	@Test
	void rejectsAnUnpinnedLeafEvenWhenItsDnsSubjectAlternativeNameMatches()
			throws Exception {
		var verifier = verifierFor(LEAF_DER);

		assertThat(verifier.verify(
				HOSTNAME,
				verifiedSession(certificate(
						"other-leaf-der".getBytes(StandardCharsets.US_ASCII),
						List.of(List.of(2, HOSTNAME))))))
				.isFalse();
	}

	@Test
	void rejectsAChainPinBecauseOnlyTheFirstPeerCertificateIsTheLeaf()
			throws Exception {
		byte[] issuerDer = "synthetic-issuer-der".getBytes(StandardCharsets.US_ASCII);
		X509Certificate leaf = certificate(
				LEAF_DER, List.of(List.of(2, HOSTNAME)));
		X509Certificate issuer = certificate(issuerDer, List.of());

		assertThat(verifierFor(issuerDer).verify(
				HOSTNAME, verifiedSession(leaf, issuer))).isFalse();
		assertThat(verifierFor(LEAF_DER).verify(
				HOSTNAME, verifiedSession(leaf, issuer))).isTrue();
	}

	@Test
	void retainsStrictDnsSanSemanticsAfterThePinMatches() throws Exception {
		var verifier = verifierFor(LEAF_DER);

		assertThat(verifier.verify(
				HOSTNAME,
				verifiedSession(certificate(
						LEAF_DER, List.of(List.of(2, "other.example.test"))))))
				.isFalse();
		assertThat(verifier.verify(
				HOSTNAME,
				verifiedSession(certificate(
						LEAF_DER, List.of(List.of(2, "*.example.test"))))))
				.isFalse();
	}

	@Test
	void rejectsMissingOrNonCanonicalPinsWithoutEchoingTheirValues() {
		assertInvalidPin(null);
		assertInvalidPin("");
		assertInvalidPin("0".repeat(63));
		assertInvalidPin("0".repeat(65));
		assertInvalidPin("A".repeat(64));
		assertInvalidPin("g".repeat(64));
	}

	@Test
	void exposesOnlyThePropertiesConstructorRequiredByPgjdbc() {
		assertThat(RelatedTopicReuseHoldoutPinnedDnsSanHostnameVerifier.class
				.getConstructors())
				.singleElement()
				.satisfies(constructor -> assertThat(constructor.getParameterTypes())
						.containsExactly(Properties.class));
	}

	@Test
	void failsClosedWhenThePeerOrLeafEncodingCannotBeVerified() throws Exception {
		var verifier = verifierFor(LEAF_DER);
		SSLSession unverified = mock(SSLSession.class);
		when(unverified.isValid()).thenReturn(true);
		when(unverified.getPeerCertificates()).thenThrow(
				new SSLPeerUnverifiedException("sensitive peer details"));

		X509Certificate malformed = mock(X509Certificate.class);
		when(malformed.getEncoded()).thenThrow(
				new CertificateEncodingException("sensitive certificate details"));

		assertThat(verifier.verify(HOSTNAME, unverified)).isFalse();
		assertThat(verifier.verify(HOSTNAME, verifiedSession(malformed))).isFalse();
		assertThat(verifier.verify(HOSTNAME, null)).isFalse();
	}

	private static RelatedTopicReuseHoldoutPinnedDnsSanHostnameVerifier
			verifierFor(byte[] leafDer) {
		Properties properties = new Properties();
		PGProperty.SSL_FACTORY_ARG.set(properties, sha256(leafDer));
		return new RelatedTopicReuseHoldoutPinnedDnsSanHostnameVerifier(properties);
	}

	private static void assertInvalidPin(String pin) {
		Properties properties = new Properties();
		if (pin != null) {
			PGProperty.SSL_FACTORY_ARG.set(properties, pin);
		}
		assertThatThrownBy(() ->
				new RelatedTopicReuseHoldoutPinnedDnsSanHostnameVerifier(properties))
				.isExactlyInstanceOf(IllegalArgumentException.class)
				.hasMessage("HOLDOUT_LEDGER_TLS_LEAF_CERTIFICATE_PIN_INVALID")
				.hasNoCause()
				.satisfies(failure -> {
					if (pin != null && !pin.isEmpty()) {
						assertThat(failure.toString()).doesNotContain(pin);
					}
				});
	}

	private static X509Certificate certificate(
			byte[] encoded, List<List<?>> alternatives) throws Exception {
		X509Certificate certificate = mock(X509Certificate.class);
		when(certificate.getEncoded()).thenReturn(encoded.clone());
		when(certificate.getSubjectAlternativeNames()).thenReturn(alternatives);
		return certificate;
	}

	private static SSLSession verifiedSession(Certificate... certificates)
			throws Exception {
		SSLSession session = mock(SSLSession.class);
		when(session.isValid()).thenReturn(true);
		when(session.getPeerCertificates()).thenReturn(certificates);
		return session;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}
}
