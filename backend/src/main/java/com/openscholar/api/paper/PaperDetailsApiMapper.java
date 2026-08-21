package com.openscholar.api.paper;

import java.util.Locale;

import com.openscholar.access.PaperAccessView;
import com.openscholar.paper.PaperDetailsView;
import com.openscholar.paper.PaperView;

final class PaperDetailsApiMapper {

	private PaperDetailsApiMapper() {
	}

	static PaperDetailsResponse toResponse(PaperDetailsView details, PaperAccessView access) {
		PaperView paper = details.paper();
		return new PaperDetailsResponse(
				paper.id(),
				paper.title(),
				paper.abstractText(),
				paper.authors().stream()
						.map(author -> new PaperDetailsResponse.Author(
								author.displayName(),
								author.orcid(),
								author.openAlexId(),
								author.position(),
								author.corresponding()))
						.toList(),
				paper.publicationDate(),
				paper.publicationYear(),
				paper.documentType(),
				paper.language(),
				paper.venueName(),
				paper.publisher(),
				paper.institution(),
				paper.volume(),
				paper.issue(),
				paper.pages(),
				paper.articleNumber(),
				paper.edition(),
				paper.isbn(),
				paper.issn(),
				paper.degree(),
				paper.citationCount(),
				paper.citationCountAsOf(),
				paper.identifiers().stream()
						.map(identifier -> new PaperDetailsResponse.Identifier(
								identifier.type(), identifier.namespace(), identifier.value()))
						.toList(),
				details.metadataCompleteness(),
				details.metadataUpdatedAt(),
				details.provenance().stream()
						.map(record -> new PaperDetailsResponse.Provenance(
								record.provider().toUpperCase(Locale.ROOT),
								record.providerRecordId(),
								record.sourceUrl(),
								record.providerUpdatedAt(),
								record.retrievedAt(),
								record.reportedOpenAccess(),
								record.id().equals(details.authorshipProviderRecordId())))
						.toList(),
				new PaperDetailsResponse.AccessSummary(
						access.status(),
						access.disposition(),
						access.checkedAt(),
						access.freshUntil(),
						access.locations().isEmpty() ? null : access.locations().getFirst().id(),
						access.locations().size(),
						access.warnings()));
	}
}
