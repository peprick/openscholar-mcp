package com.openscholar.library.internal;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface LibraryCollectionRepository extends JpaRepository<LibraryCollectionEntity, UUID> {

	Page<LibraryCollectionEntity> findByOwnerId(UUID ownerId, Pageable pageable);

	Optional<LibraryCollectionEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<LibraryCollectionEntity> findLockedByIdAndOwnerId(UUID id, UUID ownerId);

}
