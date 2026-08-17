package com.openscholar.access.internal.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaperAccessResolutionRepository extends JpaRepository<PaperAccessResolutionEntity, UUID> {
}
