package com.openscholar.paper.internal.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.openscholar.paper.PaperIdentifierType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaperExternalIdRepository extends JpaRepository<PaperExternalIdEntity, UUID> {

	Optional<PaperExternalIdEntity> findByIdTypeAndNamespaceAndNormalizedValue(
			PaperIdentifierType idType, String namespace, String normalizedValue);

	@Query(value = """
			SELECT external_id.paper_id
			FROM paper_external_id external_id
			WHERE external_id.id_type = :idType
			  AND external_id.namespace = ''
			  AND external_id.normalized_value = :normalizedValue
			  AND (
			      EXISTS (
			          SELECT 1
			          FROM search_result result
			          JOIN search_snapshot snapshot ON snapshot.id = result.search_id
			          WHERE result.paper_id = external_id.paper_id
			            AND snapshot.owner_id = :ownerId
			      )
			      OR EXISTS (
			          SELECT 1
			          FROM collection_paper saved
			          JOIN library_collection collection ON collection.id = saved.collection_id
			          WHERE saved.paper_id = external_id.paper_id
			            AND collection.owner_id = :ownerId
			      )
			  )
			""", nativeQuery = true)
	Optional<UUID> findVisiblePaperId(
			@Param("idType") String idType,
			@Param("normalizedValue") String normalizedValue,
			@Param("ownerId") UUID ownerId);

	List<PaperExternalIdEntity> findByPaper_IdIn(Collection<UUID> paperIds);
}
