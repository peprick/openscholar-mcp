package com.openscholar.paper.internal.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderRecordRepository extends JpaRepository<ProviderRecordEntity, UUID> {

	Optional<ProviderRecordEntity> findByProviderAndProviderRecordId(
			String provider, String providerRecordId);
}
