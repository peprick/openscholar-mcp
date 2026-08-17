package com.openscholar.access.internal.verification;

import java.net.URI;

record OutboundLinkCandidate(URI uri, ProviderLinkType kind, LinkProvenance provenance) {

	static OutboundLinkCandidate providerPdf(URI uri) {
		return new OutboundLinkCandidate(uri, ProviderLinkType.PDF, LinkProvenance.PROVIDER);
	}

	static OutboundLinkCandidate providerLandingPage(URI uri) {
		return new OutboundLinkCandidate(uri, ProviderLinkType.LANDING_PAGE, LinkProvenance.PROVIDER);
	}
}

enum LinkProvenance {
	PROVIDER,
	USER_INPUT,
	UNKNOWN
}
