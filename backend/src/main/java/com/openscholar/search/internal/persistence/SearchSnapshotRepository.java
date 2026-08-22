package com.openscholar.search.internal.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SearchSnapshotRepository extends JpaRepository<SearchSnapshotEntity, UUID> {

	Optional<SearchSnapshotEntity> findFirstByOwnerIdAndFingerprintAndStatusOrderBySearchedAtDesc(
			UUID ownerId, String fingerprint, String status);

	Optional<SearchSnapshotEntity> findByIdAndOwnerIdAndStatus(UUID id, UUID ownerId, String status);

	Optional<SearchSnapshotEntity> findByIdAndStatus(UUID id, String status);
}
