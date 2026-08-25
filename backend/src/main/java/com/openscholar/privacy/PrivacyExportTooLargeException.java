package com.openscholar.privacy;

public final class PrivacyExportTooLargeException extends RuntimeException {

	public PrivacyExportTooLargeException() {
		super("The personal-data export exceeds the supported record or byte limit");
	}
}
