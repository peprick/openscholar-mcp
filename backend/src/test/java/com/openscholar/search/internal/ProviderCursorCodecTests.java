package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;

import com.openscholar.provider.ProviderId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderCursorCodecTests {

	private final ProviderCursorCodec codec = new ProviderCursorCodec(new ObjectMapper());

	@Test
	void roundTripsProviderSpecificCursorsInAVersionedOpaqueEnvelope() {
		String europePmcCursor = "epmc:/next?cursor=a+b/c==&sort=♥";
		String cursor = codec.encode(
				Map.of(
						ProviderId.OPENALEX, "oa-next",
						ProviderId.CORE, "core-next",
						ProviderId.EUROPE_PMC, europePmcCursor),
				List.of(ProviderId.EUROPE_PMC, ProviderId.OPENALEX, ProviderId.CORE));

		assertThat(cursor).startsWith(ProviderCursorCodec.PREFIX);
		assertThat(codec.decode(cursor, List.of(
				ProviderId.CORE, ProviderId.EUROPE_PMC, ProviderId.OPENALEX)))
				.containsExactlyInAnyOrderEntriesOf(Map.of(
						ProviderId.CORE, "core-next",
						ProviderId.EUROPE_PMC, europePmcCursor,
						ProviderId.OPENALEX, "oa-next"));
	}

	@Test
	void preservesRawCursorCompatibilityForASingleProvider() {
		String encoded = codec.encode(
				Map.of(ProviderId.OPENALEX, "legacy-openalex-cursor"),
				List.of(ProviderId.OPENALEX));

		assertThat(encoded).isEqualTo("legacy-openalex-cursor");
		assertThat(codec.decode(encoded, List.of(ProviderId.OPENALEX)))
				.containsExactlyEntriesOf(Map.of(ProviderId.OPENALEX, "legacy-openalex-cursor"));
	}

	@Test
	void routesLegacyRawMultiProviderCursorsToOpenAlexOnly() {
		assertThat(codec.decode("legacy-cursor", List.of(ProviderId.CORE, ProviderId.OPENALEX)))
				.containsExactlyEntriesOf(Map.of(ProviderId.OPENALEX, "legacy-cursor"));
	}

	@Test
	void rejectsMalformedOrInactiveVersionedCursors() {
		String coreOnly = codec.encode(
				Map.of(ProviderId.CORE, "core-next"),
				List.of(ProviderId.CORE, ProviderId.OPENALEX));

		assertThatIllegalArgumentException()
				.isThrownBy(() -> codec.decode(ProviderCursorCodec.PREFIX + "not-base64", List.of(ProviderId.OPENALEX)))
				.withMessage("Search cursor is invalid");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> codec.decode(coreOnly, List.of(ProviderId.OPENALEX)))
				.withMessage("Search cursor is invalid");
	}
}
