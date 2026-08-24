package com.openscholar.paper.internal.persistence;

import java.util.List;
import java.util.UUID;

import com.openscholar.paper.PaperIdentifierLookupUseCase;
import com.openscholar.paper.PaperIdentifierNotFoundException;
import com.openscholar.paper.PaperIdentifierResolutionView;
import com.openscholar.paper.ResolvablePaperIdentifierType;
import com.openscholar.paper.internal.persistence.PaperIdentifierReferenceParser.ParsedIdentifier;
import com.openscholar.security.CurrentUserIdProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaperIdentifierLookupService implements PaperIdentifierLookupUseCase {

	private final PaperExternalIdRepository externalIds;
	private final CurrentUserIdProvider currentUser;

	PaperIdentifierLookupService(
			PaperExternalIdRepository externalIds, CurrentUserIdProvider currentUser) {
		this.externalIds = externalIds;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public PaperIdentifierResolutionView resolve(String identifier) {
		ParsedIdentifier parsed = PaperIdentifierReferenceParser.parse(identifier);
		UUID ownerId = currentUser.currentUserId();
		List<UUID> matchingPaperIds = parsed.lookupValues().stream()
			.map(normalizedValue -> externalIds.findVisiblePaperId(
					parsed.type().name(), normalizedValue, ownerId))
			.flatMap(java.util.Optional::stream)
			.distinct()
			.toList();
		if (matchingPaperIds.size() != 1) {
			throw new PaperIdentifierNotFoundException();
		}
		UUID paperId = matchingPaperIds.getFirst();
		return new PaperIdentifierResolutionView(
				paperId,
				ResolvablePaperIdentifierType.fromCatalogType(parsed.type()),
				parsed.normalizedValue());
	}
}
