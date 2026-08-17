package com.openscholar.paper.internal.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaperAuthorRepository extends JpaRepository<PaperAuthorEntity, UUID> {

	@Query("""
			select pa
			from PaperAuthorEntity pa
			join fetch pa.author
			join fetch pa.providerRecord
			where pa.providerRecord.id = :providerRecordId
			order by pa.position
			""")
	List<PaperAuthorEntity> findByProviderRecordId(@Param("providerRecordId") UUID providerRecordId);

	@Query("""
			select pa
			from PaperAuthorEntity pa
			join fetch pa.author
			join fetch pa.providerRecord
			where pa.paper.id in :paperIds
			order by pa.paper.id, pa.providerRecord.retrievedAt desc, pa.providerRecord.id, pa.position
			""")
	List<PaperAuthorEntity> findForPaperIds(@Param("paperIds") Collection<UUID> paperIds);

	void deleteByProviderRecord_Id(UUID providerRecordId);
}
