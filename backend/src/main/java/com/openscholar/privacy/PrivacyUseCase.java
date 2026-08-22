package com.openscholar.privacy;

public interface PrivacyUseCase {

	PrivacyExport exportPersonalData();

	void deletePersonalData();
}
