package com.openscholar.access.internal.verification;

import java.net.URI;

/**
 * Verifies an outbound URL obtained from a configured research-access provider.
 * User-supplied URLs must not be passed to this boundary.
 */
public interface ProviderLinkVerifier {

	LinkVerificationResult verify(URI uri, ProviderLinkType type);
}
