package com.openscholar.privacy;

import java.io.IOException;

public interface PrivacyUseCase {

	void exportPersonalData(PrivacyExportTarget target) throws IOException;

	void deletePersonalData();
}
