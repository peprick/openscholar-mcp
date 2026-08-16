package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifierType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PaperRepositoryTests {

	@Autowired
	private PaperRepository paperRepository;

	@Autowired
	private PaperExternalIdRepository externalIdRepository;

	@Test
	void savesAndReadsCanonicalPaper() {
		Instant now = Instant.parse("2026-08-16T12:00:00Z");
		PaperEntity paper = PaperEntity.create("  A Study of Research Agents  ", DocumentType.ARTICLE, now);

		paperRepository.saveAndFlush(paper);

		PaperEntity restored = paperRepository.findById(paper.id()).orElseThrow();
		assertThat(restored.title()).isEqualTo("A Study of Research Agents");
	}

	@Test
	void rejectsDuplicateNormalizedExternalIdentifier() {
		Instant now = Instant.parse("2026-08-16T12:00:00Z");
		PaperEntity first = paperRepository.save(
				PaperEntity.create("First paper", DocumentType.ARTICLE, now));
		PaperEntity second = paperRepository.save(
				PaperEntity.create("Second paper", DocumentType.ARTICLE, now));

		externalIdRepository.saveAndFlush(PaperExternalIdEntity.create(
				first, PaperIdentifierType.DOI, "https://doi.org/10.1000/example", now));

		assertThatThrownBy(() -> externalIdRepository.saveAndFlush(PaperExternalIdEntity.create(
				second, PaperIdentifierType.DOI, "doi:10.1000/EXAMPLE", now)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsSameRepositoryLocalIdInDifferentNamespaces() {
		Instant now = Instant.parse("2026-08-16T12:00:00Z");
		PaperEntity first = paperRepository.save(
				PaperEntity.create("First thesis", DocumentType.THESIS, now));
		PaperEntity second = paperRepository.save(
				PaperEntity.create("Second thesis", DocumentType.THESIS, now));

		externalIdRepository.save(PaperExternalIdEntity.create(
				first, PaperIdentifierType.REPOSITORY, "university-a", "12345", now));
		externalIdRepository.save(PaperExternalIdEntity.create(
				second, PaperIdentifierType.REPOSITORY, "university-b", "12345", now));

		externalIdRepository.flush();
		assertThat(externalIdRepository.count()).isEqualTo(2);
	}
}
