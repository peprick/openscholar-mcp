package com.openscholar.paper.internal.persistence;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperDetailsUseCase;
import com.openscholar.paper.PaperDetailsView;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperProviderRecordView;
import com.openscholar.paper.PaperView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
class PaperDetailsService implements PaperDetailsUseCase {

	private final PaperCatalog paperCatalog;
	private final PaperRepository paperRepository;
	private final ProviderRecordRepository providerRecordRepository;
	private final PaperAuthorRepository paperAuthorRepository;

	PaperDetailsService(
			PaperCatalog paperCatalog,
			PaperRepository paperRepository,
			ProviderRecordRepository providerRecordRepository,
			PaperAuthorRepository paperAuthorRepository) {
		this.paperCatalog = paperCatalog;
		this.paperRepository = paperRepository;
		this.providerRecordRepository = providerRecordRepository;
		this.paperAuthorRepository = paperAuthorRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public PaperDetailsView get(UUID paperId) {
		Objects.requireNonNull(paperId, "paperId");
		PaperView paper = paperCatalog.findById(paperId)
				.orElseThrow(() -> new PaperNotFoundException(paperId));
		PaperEntity metadata = paperRepository.findById(paperId)
				.orElseThrow(() -> new PaperNotFoundException(paperId));
		List<PaperProviderRecordView> provenance = providerRecordRepository.findForPaperId(paperId)
				.stream()
				.map(PaperDetailsService::toView)
				.toList();
		UUID authorshipProviderRecordId = paperAuthorRepository.findForPaperIds(List.of(paperId))
				.stream()
				.findFirst()
				.map(PaperAuthorEntity::providerRecordId)
				.orElse(null);
		return new PaperDetailsView(
				paper,
				metadata.metadataQuality(),
				metadata.metadataUpdatedAt(),
				provenance,
				authorshipProviderRecordId);
	}

	private static PaperProviderRecordView toView(ProviderRecordEntity record) {
		return new PaperProviderRecordView(
				record.id(),
				record.provider(),
				record.providerRecordId(),
				publicSourceUri(record.sourceUrl()),
				record.providerUpdatedAt(),
				record.retrievedAt(),
				record.reportedOpenAccess());
	}

	private static URI publicSourceUri(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI source = URI.create(value);
			String scheme = source.getScheme() == null
					? ""
					: source.getScheme().toLowerCase(Locale.ROOT);
			if (!(scheme.equals("http") || scheme.equals("https"))
					|| source.getHost() == null
					|| source.getUserInfo() != null) {
				return null;
			}
			return UriComponentsBuilder.fromUri(source)
					.replaceQuery(null)
					.fragment(null)
					.build(true)
					.toUri();
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
