package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    /**
     * Add one venue, idempotently. {@code ON CONFLICT (owner, normalized_name) DO NOTHING} against
     * the unique index {@code venue_owner_normalized_name} -- names that index by exact column
     * list, not just any conflict, so this suppresses only that one constraint (per CLAUDE.md, do
     * not assume {@code ON CONFLICT} covers others). Mirrors {@code catalog.ArtistImportRepository
     * #insertIfAbsent}: a DB-level upsert, never an {@code existsBy} pre-check, since a read-then-write
     * races and a resulting constraint violation would poison a listener's whole transaction.
     *
     * @return 1 if this call inserted the venue, 0 if it already existed for this owner
     */
    @Modifying
    @Query(value = """
            INSERT INTO venue (owner, name, normalized_name, calendar_url, created_at)
            VALUES (:owner, :name, :normalizedName, :calendarUrl, :createdAt)
            ON CONFLICT (owner, normalized_name) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("owner") String owner,
                        @Param("name") String name,
                        @Param("normalizedName") String normalizedName,
                        @Param("calendarUrl") String calendarUrl,
                        @Param("createdAt") Instant createdAt);

    List<Venue> findByOwnerOrderByNameAsc(String owner);

    Optional<Venue> findByIdAndOwner(Long id, String owner);
}
