package com.openscholar.paper;

import java.util.Objects;

public record PaperIdentifier(PaperIdentifierType type, String namespace, String value) {

	public PaperIdentifier {
		type = Objects.requireNonNull(type, "type");
		namespace = namespace == null ? "" : namespace;
		value = Objects.requireNonNull(value, "value");
	}
}
