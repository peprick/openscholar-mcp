package com.openscholar.search.internal.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SearchResultRepository extends JpaRepository<SearchResultEntity, UUID> {

	List<SearchResultEntity> findAllBySearchIdOrderByResultRank(UUID searchId);
}
