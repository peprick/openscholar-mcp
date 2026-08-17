package com.openscholar.paper.internal.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.openscholar.paper.PaperIdentifierType;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaperExternalIdRepository extends JpaRepository<PaperExternalIdEntity, UUID> {

	Optional<PaperExternalIdEntity> findByIdTypeAndNamespaceAndNormalizedValue(
			PaperIdentifierType idType, String namespace, String normalizedValue);

	List<PaperExternalIdEntity> findByPaper_IdIn(Collection<UUID> paperIds);
}
