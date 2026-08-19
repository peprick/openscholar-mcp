package com.openscholar.library.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "library_collection")
class LibraryCollectionEntity {

	@Id
	private UUID id;

	@Column(name = "owner_id", nullable = false)
	private UUID ownerId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(length = 1000)
	private String description;

	@Version
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected LibraryCollectionEntity() {
	}

	private LibraryCollectionEntity(UUID id, UUID ownerId, String name, String description, Instant now) {
		this.id = id;
		this.ownerId = ownerId;
		this.name = cleanName(name);
		this.description = cleanDescription(description);
		this.createdAt = now;
		this.updatedAt = now;
	}

	static LibraryCollectionEntity create(UUID ownerId, String name, String description, Instant now) {
		return new LibraryCollectionEntity(UUID.randomUUID(), Objects.requireNonNull(ownerId, "ownerId"), name,
				description, Objects.requireNonNull(now, "now"));
	}

	void update(String name, String description, Instant now) {
		this.name = cleanName(name);
		this.description = cleanDescription(description);
		this.updatedAt = Objects.requireNonNull(now, "now");
	}

	private static String cleanName(String value) {
		String clean = value == null ? "" : value.strip();
		if (clean.isEmpty() || clean.length() > 120) {
			throw new IllegalArgumentException("Collection name must contain between 1 and 120 characters");
		}
		return clean;
	}

	private static String cleanDescription(String value) {
		if (value == null) {
			return null;
		}
		String clean = value.strip();
		if (clean.length() > 1000) {
			throw new IllegalArgumentException("Collection description must not exceed 1000 characters");
		}
		return clean.isEmpty() ? null : clean;
	}

	UUID id() {
		return id;
	}

	UUID ownerId() {
		return ownerId;
	}

	String name() {
		return name;
	}

	String description() {
		return description;
	}

	Instant createdAt() {
		return createdAt;
	}

	Instant updatedAt() {
		return updatedAt;
	}

}
