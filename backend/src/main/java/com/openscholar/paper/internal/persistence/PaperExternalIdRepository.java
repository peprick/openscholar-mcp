package com.openscholar.paper.internal.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaperExternalIdRepository extends JpaRepository<PaperExternalIdEntity, UUID> {
}
