package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SharedScanRepository extends JpaRepository<SharedScan, Long> {

    Optional<SharedScan> findByOwnerKey(String ownerKey);

    /**
     * Every shared scan the given address participates in, oldest first ({@code created_at} then
     * {@code id} as the tiebreaker -- #187). Both parameters take the SAME address -- the
     * derived-query grammar has no single-parameter "a or b" form. Case-insensitive on both sides,
     * because the address arrives from the OIDC token and its casing must not decide access.
     * <p>
     * The explicit order is load-bearing, not cosmetic: with no {@code ORDER BY} at all, which
     * pairing a caller with more than one sees first is whatever incidental scan order Postgres
     * happens to return -- unstable between requests, and the reason {@code SharedScanController}
     * used to render an arbitrary pairing and leave any other unreachable.
     */
    List<SharedScan> findByOwnerAIgnoreCaseOrOwnerBIgnoreCaseOrderByCreatedAtAscIdAsc(String ownerA, String ownerB);

    /**
     * Whether a pairing between these two addresses already exists, checked in both directions --
     * the derived-query grammar has no single "unordered pair" form, so the caller passes
     * {@code (a, b, b, a)}. The app supports exactly one shared scan per pair ({@link SharedScan}'s
     * class doc), so {@code (ownerA, ownerB)} and {@code (ownerB, ownerA)} are the same pairing.
     * Case-insensitive, like every other lookup keyed on a participant address.
     */
    boolean existsByOwnerAIgnoreCaseAndOwnerBIgnoreCaseOrOwnerAIgnoreCaseAndOwnerBIgnoreCase(
            String ownerA, String ownerB, String otherOwnerA, String otherOwnerB);
}
