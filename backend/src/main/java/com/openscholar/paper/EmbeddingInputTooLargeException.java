package com.openscholar.paper;

import java.util.UUID;

public class EmbeddingInputTooLargeException extends RuntimeException {

	public EmbeddingInputTooLargeException(UUID paperId, int actualBytes, int maximumBytes) {
		super("Rendered embedding input for paper " + paperId + " is " + actualBytes
				+ " bytes; maximum is " + maximumBytes);
	}
}
