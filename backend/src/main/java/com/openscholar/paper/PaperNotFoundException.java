package com.openscholar.paper;

import java.util.UUID;

public class PaperNotFoundException extends RuntimeException {

	public PaperNotFoundException(UUID paperId) {
		super("Paper not found: " + paperId);
	}
}
