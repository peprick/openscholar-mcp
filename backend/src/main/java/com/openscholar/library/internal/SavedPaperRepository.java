package com.openscholar.library.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SavedPaperRepository extends JpaRepository<SavedPaperEntity, UUID> {

	Optional<SavedPaperEntity> findByCollection_IdAndPaperId(UUID collectionId, UUID paperId);

	Page<SavedPaperEntity> findByCollection_Id(UUID collectionId, Pageable pageable);

	long countByCollection_Id(UUID collectionId);

	@Query("""
			select saved.collection.id as collectionId, count(saved) as paperCount
			from SavedPaperEntity saved
			where saved.collection.id in :collectionIds
			group by saved.collection.id
			""")
	List<CollectionPaperCount> countForCollections(@Param("collectionIds") Collection<UUID> collectionIds);

	@Query(value = """
			select saved.*
			from collection_paper saved
			join library_collection collection on collection.id = saved.collection_id
			join paper paper on paper.id = saved.paper_id
			where collection.owner_id = :ownerId
			  and (cast(:collectionId as uuid) is null or saved.collection_id = :collectionId)
			  and (:readingStatus = '' or saved.reading_status = :readingStatus)
			  and (:tag = '' or exists (
			      select 1 from collection_paper_tag saved_tag
			      where saved_tag.collection_paper_id = saved.id and saved_tag.tag = :tag))
			  and (:query = ''
			      or lower(collection.name) like concat('%', :query, '%') escape '\\'
			      or lower(paper.title) like concat('%', :query, '%') escape '\\'
			      or lower(coalesce(paper.abstract_text, '')) like concat('%', :query, '%') escape '\\'
			      or lower(coalesce(paper.venue_name, '')) like concat('%', :query, '%') escape '\\'
			      or exists (
			          select 1 from paper_author credited_author
			          where credited_author.paper_id = paper.id
			            and lower(credited_author.credited_name)
			                like concat('%', :query, '%') escape '\\'))
			order by saved.saved_at desc, saved.id
			""", countQuery = """
			select count(*)
			from collection_paper saved
			join library_collection collection on collection.id = saved.collection_id
			join paper paper on paper.id = saved.paper_id
			where collection.owner_id = :ownerId
			  and (cast(:collectionId as uuid) is null or saved.collection_id = :collectionId)
			  and (:readingStatus = '' or saved.reading_status = :readingStatus)
			  and (:tag = '' or exists (
			      select 1 from collection_paper_tag saved_tag
			      where saved_tag.collection_paper_id = saved.id and saved_tag.tag = :tag))
			  and (:query = ''
			      or lower(collection.name) like concat('%', :query, '%') escape '\\'
			      or lower(paper.title) like concat('%', :query, '%') escape '\\'
			      or lower(coalesce(paper.abstract_text, '')) like concat('%', :query, '%') escape '\\'
			      or lower(coalesce(paper.venue_name, '')) like concat('%', :query, '%') escape '\\'
			      or exists (
			          select 1 from paper_author credited_author
			          where credited_author.paper_id = paper.id
			            and lower(credited_author.credited_name)
			                like concat('%', :query, '%') escape '\\'))
			""", nativeQuery = true)
	Page<SavedPaperEntity> search(@Param("ownerId") UUID ownerId, @Param("query") String query,
			@Param("collectionId") UUID collectionId, @Param("readingStatus") String readingStatus,
			@Param("tag") String tag, Pageable pageable);

	interface CollectionPaperCount {

		UUID getCollectionId();

		long getPaperCount();

	}

}
