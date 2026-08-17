package com.openscholar.access.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaperVersionRepository extends JpaRepository<PaperVersionEntity, UUID> {

	List<PaperVersionEntity> findAllByPaperIdAndActiveTrue(UUID paperId);

	List<PaperVersionEntity> findAllByPaperIdAndSourceAndActiveTrue(UUID paperId, String source);

	Optional<PaperVersionEntity> findByPaperIdAndSourceAndSourceLocationKey(
			UUID paperId, String source, String sourceLocationKey);
}
