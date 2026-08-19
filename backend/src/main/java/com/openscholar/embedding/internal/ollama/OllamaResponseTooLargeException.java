package com.openscholar.embedding.internal.ollama;

import java.io.IOException;

final class OllamaResponseTooLargeException extends IOException {

	OllamaResponseTooLargeException(long maximumBytes) {
		super("Ollama response exceeded the " + maximumBytes + " byte limit");
	}
}
