package com.openscholar.api;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import com.openscholar.access.AccessUnavailableException;
import com.openscholar.access.AccessRefreshTooSoonException;
import com.openscholar.citation.UnsupportedCitationFormatException;
import com.openscholar.library.CollectionNotFoundException;
import com.openscholar.library.OfflineCollectionPackTooLargeException;
import com.openscholar.library.SavedPaperNotFoundException;
import com.openscholar.jobs.ResearchRefreshJobNotFoundException;
import com.openscholar.jobs.ResearchRefreshJobNotRetryableException;
import com.openscholar.paper.InvalidPaperIdentifierException;
import com.openscholar.paper.PaperIdentifierNotFoundException;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.privacy.PrivacyExportBusyException;
import com.openscholar.privacy.PrivacyExportTooLargeException;
import com.openscholar.search.SearchCoordinationInterruptedException;
import com.openscholar.search.SearchCoordinationTimeoutException;
import com.openscholar.search.SearchDeadlineExceededException;
import com.openscholar.search.SearchExecutionInterruptedException;
import com.openscholar.search.SearchNotFoundException;
import com.openscholar.search.SearchPageExhaustedException;
import com.openscholar.search.SearchUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
		ProblemDetail problem = problem(
				HttpStatus.BAD_REQUEST,
				"VALIDATION_FAILED",
				"Request validation failed",
				"One or more request fields are invalid.");
		List<ValidationViolation> violations = Stream.concat(
				exception.getBindingResult().getFieldErrors().stream()
						.map(error -> new ValidationViolation(error.getField(), error.getDefaultMessage())),
				exception.getBindingResult().getGlobalErrors().stream()
						.map(error -> new ValidationViolation(error.getObjectName(), error.getDefaultMessage())))
				.toList();
		problem.setProperty("violations", violations);
		return problem;
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
		ProblemDetail problem = problem(
				HttpStatus.BAD_REQUEST,
				"VALIDATION_FAILED",
				"Request validation failed",
				"One or more request values are invalid.");
		problem.setProperty("violations", exception.getConstraintViolations().stream()
				.map(violation -> new ValidationViolation(
						violation.getPropertyPath().toString(), violation.getMessage()))
				.toList());
		return problem;
	}

	@ExceptionHandler({
		IllegalArgumentException.class,
		HttpMessageNotReadableException.class,
		MethodArgumentTypeMismatchException.class
	})
	ProblemDetail handleInvalidRequest(Exception exception) {
		return problem(
				HttpStatus.BAD_REQUEST,
				"INVALID_REQUEST",
				"Invalid request",
				"The request body or parameter values could not be accepted.");
	}

	@ExceptionHandler(UnsupportedCitationFormatException.class)
	ProblemDetail handleUnsupportedCitationFormat(UnsupportedCitationFormatException exception) {
		return problem(
				HttpStatus.BAD_REQUEST,
				"UNSUPPORTED_CITATION_FORMAT",
				"Unsupported citation format",
				"Citation format must be one of: bibtex, csl-json.");
	}

	@ExceptionHandler(SearchNotFoundException.class)
	ProblemDetail handleNotFound(SearchNotFoundException exception) {
		return problem(
				HttpStatus.NOT_FOUND,
				"SEARCH_NOT_FOUND",
				"Search not found",
				exception.getMessage());
	}

	@ExceptionHandler(SearchPageExhaustedException.class)
	ProblemDetail handleSearchPageExhausted(SearchPageExhaustedException exception) {
		return problem(
				HttpStatus.CONFLICT,
				"SEARCH_PAGE_EXHAUSTED",
				"Search page exhausted",
				exception.getMessage());
	}

	@ExceptionHandler(PaperNotFoundException.class)
	ProblemDetail handlePaperNotFound(PaperNotFoundException exception) {
		return problem(
				HttpStatus.NOT_FOUND,
				"PAPER_NOT_FOUND",
				"Paper not found",
				exception.getMessage());
	}

	@ExceptionHandler(InvalidPaperIdentifierException.class)
	ResponseEntity<ProblemDetail> handleInvalidPaperIdentifier(InvalidPaperIdentifierException exception) {
		ProblemDetail problem = problem(
				HttpStatus.BAD_REQUEST,
				"INVALID_PAPER_IDENTIFIER",
				"Invalid paper identifier",
				exception.getMessage());
		return ResponseEntity.badRequest()
				.cacheControl(CacheControl.noStore())
				.body(problem);
	}

	@ExceptionHandler(PaperIdentifierNotFoundException.class)
	ResponseEntity<ProblemDetail> handlePaperIdentifierNotFound(PaperIdentifierNotFoundException exception) {
		ProblemDetail problem = problem(
				HttpStatus.NOT_FOUND,
				"PAPER_IDENTIFIER_NOT_FOUND",
				"Paper identifier not found",
				exception.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.cacheControl(CacheControl.noStore())
				.body(problem);
	}

	@ExceptionHandler(CollectionNotFoundException.class)
	ResponseEntity<ProblemDetail> handleCollectionNotFound(CollectionNotFoundException exception) {
		ProblemDetail problem = problem(
				HttpStatus.NOT_FOUND,
				"COLLECTION_NOT_FOUND",
				"Collection not found",
				exception.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.cacheControl(CacheControl.noStore())
			.body(problem);
	}

	@ExceptionHandler(OfflineCollectionPackTooLargeException.class)
	ResponseEntity<ProblemDetail> handleOfflineCollectionPackTooLarge(
			OfflineCollectionPackTooLargeException exception) {
		ProblemDetail problem = problem(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"OFFLINE_PACK_TOO_LARGE",
				"Offline metadata pack too large",
				exception.getMessage());
		return ResponseEntity.unprocessableEntity()
			.cacheControl(CacheControl.noStore())
			.body(problem);
	}

	@ExceptionHandler(PrivacyExportTooLargeException.class)
	ResponseEntity<ProblemDetail> handlePrivacyExportTooLarge(
			PrivacyExportTooLargeException exception) {
		ProblemDetail problem = problem(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"PRIVACY_EXPORT_TOO_LARGE",
				"Personal-data export too large",
				exception.getMessage());
		return ResponseEntity.unprocessableEntity()
				.cacheControl(CacheControl.noStore())
				.body(problem);
	}

	@ExceptionHandler(PrivacyExportBusyException.class)
	ResponseEntity<ProblemDetail> handlePrivacyExportBusy(
			PrivacyExportBusyException exception) {
		ProblemDetail problem = problem(
				HttpStatus.TOO_MANY_REQUESTS,
				"PRIVACY_EXPORT_BUSY",
				"Personal-data export busy",
				exception.getMessage());
		problem.setProperty("retryable", true);
		long retryAfterSeconds = Math.max(1,
				exception.retryAfter().getSeconds()
						+ (exception.retryAfter().getNano() == 0 ? 0 : 1));
		problem.setProperty("retryAfterSeconds", retryAfterSeconds);
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
				.body(problem);
	}

	@ExceptionHandler(SavedPaperNotFoundException.class)
	ProblemDetail handleSavedPaperNotFound(SavedPaperNotFoundException exception) {
		return problem(
				HttpStatus.NOT_FOUND,
				"SAVED_PAPER_NOT_FOUND",
				"Saved paper not found",
				exception.getMessage());
	}

	@ExceptionHandler(ResearchRefreshJobNotFoundException.class)
	ProblemDetail handleRefreshJobNotFound(ResearchRefreshJobNotFoundException exception) {
		return problem(
				HttpStatus.NOT_FOUND,
				"REFRESH_JOB_NOT_FOUND",
				"Refresh job not found",
				exception.getMessage());
	}

	@ExceptionHandler(ResearchRefreshJobNotRetryableException.class)
	ProblemDetail handleRefreshJobNotRetryable(ResearchRefreshJobNotRetryableException exception) {
		return problem(
				HttpStatus.CONFLICT,
				"REFRESH_JOB_NOT_RETRYABLE",
				"Refresh job cannot be retried",
				exception.getMessage());
	}

	@ExceptionHandler(SearchUnavailableException.class)
	ResponseEntity<ProblemDetail> handleUnavailable(SearchUnavailableException exception) {
		ProblemDetail problem = problem(
				HttpStatus.SERVICE_UNAVAILABLE,
				"SEARCH_PROVIDER_UNAVAILABLE",
				"Research provider unavailable",
				"The research provider could not complete this search.");
		problem.setProperty("retryable", exception.retryable());
		HttpHeaders headers = new HttpHeaders();
		if (exception.retryAfter() != null && !exception.retryAfter().isNegative()) {
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()));
		}
		return new ResponseEntity<>(problem, headers, HttpStatus.SERVICE_UNAVAILABLE);
	}

	@ExceptionHandler(SearchCoordinationTimeoutException.class)
	ProblemDetail handleSearchCoordinationTimeout(SearchCoordinationTimeoutException exception) {
		ProblemDetail problem = problem(
				HttpStatus.SERVICE_UNAVAILABLE,
				"SEARCH_COORDINATION_TIMEOUT",
				"Search coordination timed out",
				"Search coordination did not become available within the configured wait limit.");
		problem.setProperty("retryable", true);
		return problem;
	}

	@ExceptionHandler(SearchCoordinationInterruptedException.class)
	ProblemDetail handleSearchCoordinationInterrupted(SearchCoordinationInterruptedException exception) {
		ProblemDetail problem = problem(
				HttpStatus.SERVICE_UNAVAILABLE,
				"SEARCH_COORDINATION_INTERRUPTED",
				"Search coordination interrupted",
				"Search coordination was interrupted before it became available.");
		problem.setProperty("retryable", true);
		return problem;
	}

	@ExceptionHandler(SearchDeadlineExceededException.class)
	ProblemDetail handleSearchDeadlineExceeded(SearchDeadlineExceededException exception) {
		ProblemDetail problem = problem(
				HttpStatus.GATEWAY_TIMEOUT,
				"SEARCH_DEADLINE_EXCEEDED",
				"Search deadline exceeded",
				"The search did not complete within the configured execution deadline.");
		problem.setProperty("retryable", true);
		return problem;
	}

	@ExceptionHandler(SearchExecutionInterruptedException.class)
	ProblemDetail handleSearchExecutionInterrupted(SearchExecutionInterruptedException exception) {
		ProblemDetail problem = problem(
				HttpStatus.SERVICE_UNAVAILABLE,
				"SEARCH_EXECUTION_INTERRUPTED",
				"Search execution interrupted",
				"The search execution was interrupted before it could complete.");
		problem.setProperty("retryable", true);
		return problem;
	}

	@ExceptionHandler(AccessUnavailableException.class)
	ResponseEntity<ProblemDetail> handleAccessUnavailable(AccessUnavailableException exception) {
		ProblemDetail problem = problem(
				HttpStatus.SERVICE_UNAVAILABLE,
				"ACCESS_PROVIDERS_UNAVAILABLE",
				"Access providers unavailable",
				"No access provider could complete the verification request.");
		problem.setProperty("retryable", exception.retryable());
		HttpHeaders headers = new HttpHeaders();
		if (exception.retryAfter() != null && !exception.retryAfter().isNegative()) {
			long retryAfterSeconds = Math.max(1, (exception.retryAfter().toMillis() + 999) / 1_000);
			problem.setProperty("retryAfterSeconds", retryAfterSeconds);
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		}
		return new ResponseEntity<>(problem, headers, HttpStatus.SERVICE_UNAVAILABLE);
	}

	@ExceptionHandler(AccessRefreshTooSoonException.class)
	ResponseEntity<ProblemDetail> handleAccessRefreshTooSoon(AccessRefreshTooSoonException exception) {
		ProblemDetail problem = problem(
				HttpStatus.TOO_MANY_REQUESTS,
				"ACCESS_REFRESH_RATE_LIMITED",
				"Access refresh rate limited",
				"This paper was force-refreshed too recently.");
		problem.setProperty("retryable", true);
		long retryAfterSeconds = Math.max(1, (exception.retryAfter().toMillis() + 999) / 1_000);
		problem.setProperty("retryAfterSeconds", retryAfterSeconds);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		return new ResponseEntity<>(problem, headers, HttpStatus.TOO_MANY_REQUESTS);
	}

	private static ProblemDetail problem(
			HttpStatus status, String code, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("urn:openscholar:problem:" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(title);
		problem.setProperty("code", code);
		return problem;
	}

	private record ValidationViolation(String field, String message) {
	}
}
