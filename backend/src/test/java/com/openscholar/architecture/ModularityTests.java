package com.openscholar.architecture;

import com.openscholar.OpenScholarBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	void verifiesModuleBoundaries() {
		ApplicationModules.of(OpenScholarBackendApplication.class).verify();
	}
}
