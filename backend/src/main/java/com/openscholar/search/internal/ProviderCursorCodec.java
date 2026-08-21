package com.openscholar.search.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.openscholar.provider.ProviderId;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class ProviderCursorCodec {

	static final String PREFIX = "oscur1.";
	private static final int VERSION = 1;
	private static final int MAX_CURSOR_LENGTH = 4096;

	private final ObjectMapper objectMapper;

	ProviderCursorCodec(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
	}

	Map<ProviderId, String> decode(String cursor, Collection<ProviderId> enabledProviders) {
		List<ProviderId> providers = orderedProviders(enabledProviders);
		if (cursor == null || cursor.isBlank() || cursor.equals("*")) {
			Map<ProviderId, String> initial = new LinkedHashMap<>();
			providers.forEach(provider -> initial.put(provider, "*"));
			return Map.copyOf(initial);
		}
		if (cursor.length() > MAX_CURSOR_LENGTH) {
			throw invalidCursor();
		}
		if (!cursor.startsWith(PREFIX)) {
			ProviderId legacyProvider = providers.contains(ProviderId.OPENALEX)
					? ProviderId.OPENALEX
					: providers.getFirst();
			return Map.of(legacyProvider, cursor);
		}

		try {
			byte[] json = Base64.getUrlDecoder().decode(cursor.substring(PREFIX.length()));
			CursorEnvelope envelope = objectMapper.readValue(json, CursorEnvelope.class);
			if (envelope == null || envelope.version() != VERSION || envelope.cursors() == null) {
				throw invalidCursor();
			}
			Map<ProviderId, String> decoded = new LinkedHashMap<>();
			for (ProviderId provider : providers) {
				String value = envelope.cursors().get(provider.name());
				if (value != null && !value.isBlank()) {
					decoded.put(provider, value);
				}
			}
			if (decoded.isEmpty()) {
				throw invalidCursor();
			}
			return Map.copyOf(decoded);
		}
		catch (JacksonException | IllegalArgumentException exception) {
			if (exception instanceof IllegalArgumentException illegalArgumentException
					&& "Search cursor is invalid".equals(illegalArgumentException.getMessage())) {
				throw illegalArgumentException;
			}
			throw invalidCursor();
		}
	}

	String encode(Map<ProviderId, String> providerCursors, Collection<ProviderId> enabledProviders) {
		Objects.requireNonNull(providerCursors, "providerCursors");
		List<ProviderId> providers = orderedProviders(enabledProviders);
		Map<String, String> active = new TreeMap<>();
		for (ProviderId provider : providers) {
			String value = providerCursors.get(provider);
			if (value != null && !value.isBlank()) {
				active.put(provider.name(), value);
			}
		}
		if (active.isEmpty()) {
			return null;
		}
		if (providers.size() == 1) {
			return active.values().iterator().next();
		}
		try {
			String encoded = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
					objectMapper.writeValueAsString(new CursorEnvelope(VERSION, active))
							.getBytes(StandardCharsets.UTF_8));
			if (encoded.length() > MAX_CURSOR_LENGTH) {
				throw new IllegalStateException("Combined provider cursor exceeds the public cursor limit");
			}
			return encoded;
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Could not encode the provider cursor", exception);
		}
	}

	private static List<ProviderId> orderedProviders(Collection<ProviderId> enabledProviders) {
		Objects.requireNonNull(enabledProviders, "enabledProviders");
		List<ProviderId> providers = enabledProviders.stream()
				.filter(Objects::nonNull)
				.distinct()
				.sorted(Comparator.comparing(ProviderId::name))
				.toList();
		if (providers.isEmpty()) {
			throw new IllegalStateException("At least one research provider must be enabled");
		}
		return providers;
	}

	private static IllegalArgumentException invalidCursor() {
		return new IllegalArgumentException("Search cursor is invalid");
	}

	private record CursorEnvelope(int version, Map<String, String> cursors) {
	}
}
