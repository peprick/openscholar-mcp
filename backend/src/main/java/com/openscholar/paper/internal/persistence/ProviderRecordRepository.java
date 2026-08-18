package com.openscholar.paper.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProviderRecordRepository extends JpaRepository<ProviderRecordEntity, UUID> {

	Optional<ProviderRecordEntity> findByProviderAndProviderRecordId(
			String provider, String providerRecordId);

	@Query("""
			select pr
			from ProviderRecordEntity pr
			where pr.paper.id = :paperId
			order by pr.retrievedAt desc, pr.provider, pr.providerRecordId
			""")
	List<ProviderRecordEntity> findForPaperId(@Param("paperId") UUID paperId);
}
