package com.openscholar.api.privacy;

import java.io.IOException;

import com.openscholar.privacy.PrivacyUseCase;
import jakarta.servlet.http.HttpServletResponse;
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
	void exportPersonalData(HttpServletResponse response) throws IOException {
		privacy.exportPersonalData(contentLength -> {
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setContentLengthLong(contentLength);
			response.setHeader(
					HttpHeaders.CACHE_CONTROL,
					CacheControl.noStore().noTransform().getHeaderValue());
			response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
					"attachment; filename=\"openscholar-personal-data.json\"");
			response.setHeader("X-Content-Type-Options", "nosniff");
			return response.getOutputStream();
		});
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
