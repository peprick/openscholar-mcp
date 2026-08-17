package com.openscholar.paper.internal.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AuthorRepository extends JpaRepository<AuthorEntity, UUID> {

	Optional<AuthorEntity> findByOpenAlexId(String openAlexId);

	Optional<AuthorEntity> findByOrcid(String orcid);
}
