package com.openscholar.api.privacy;

import com.openscholar.privacy.PrivacyExport;
import com.openscholar.privacy.PrivacyUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/privacy")
class PrivacyController {

	private final PrivacyUseCase privacy;

	PrivacyController(PrivacyUseCase privacy) {
		this.privacy = privacy;
	}

	@GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<PrivacyExport> exportPersonalData() {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"openscholar-personal-data.json\"")
				.body(privacy.exportPersonalData());
	}

	@DeleteMapping("/account")
	ResponseEntity<Void> deletePersonalData(@Valid @RequestBody DeletePersonalDataRequest request) {
		privacy.deletePersonalData();
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	record DeletePersonalDataRequest(
			@NotNull
			@Pattern(regexp = "DELETE_MY_DATA", message = "must equal DELETE_MY_DATA") String confirmation) {
	}
}
