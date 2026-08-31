package com.openscholar.search.internal.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.postgresql.PGProperty;

/**
 * Fixed verifier for an exact leaf-certificate pin and exact DNS SAN.
 *
 * <p>pgJDBC supplies the complete connection properties to this constructor.
 * The expected pin is the lowercase SHA-256 digest of the leaf certificate's
 * DER encoding and is carried in the otherwise unused {@code sslFactoryArg}
 * property. The normal LibPQ factory still performs certificate-path validation
 * before pgJDBC invokes this verifier.</p>
 */
public final class RelatedTopicReuseHoldoutPinnedDnsSanHostnameVerifier
		implements HostnameVerifier {

	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	private final byte[] expectedLeafCertificateSha256;
	private final HostnameVerifier dnsSanVerifier =
			new RelatedTopicReuseHoldoutStrictDnsSanHostnameVerifier();

	/**
	 * Creates a verifier from the closed pgJDBC property set.
	 *
	 * @param properties the connection properties supplied by pgJDBC
	 */
	public RelatedTopicReuseHoldoutPinnedDnsSanHostnameVerifier(
			Properties properties) {
		String encodedPin = properties == null
				? null
				: PGProperty.SSL_FACTORY_ARG.getOrDefault(properties);
		if (encodedPin == null || !SHA256.matcher(encodedPin).matches()) {
			throw new IllegalArgumentException(
					"HOLDOUT_LEDGER_TLS_LEAF_CERTIFICATE_PIN_INVALID");
		}
		this.expectedLeafCertificateSha256 = HexFormat.of().parseHex(encodedPin);
	}

	@Override
	public boolean verify(String hostname, SSLSession session) {
		if (session == null) {
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

			byte[] observedDigest = sha256Digest().digest(leafCertificate.getEncoded());
			if (!MessageDigest.isEqual(
					expectedLeafCertificateSha256, observedDigest)) {
				return false;
			}
			return dnsSanVerifier.verify(hostname, session);
		}
		catch (SSLPeerUnverifiedException | CertificateEncodingException
				| RuntimeException ignored) {
			return false;
		}
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable");
		}
	}
}
