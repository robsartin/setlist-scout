package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SharedScanRepository extends JpaRepository<SharedScan, Long> {

    Optional<SharedScan> findByOwnerKey(String ownerKey);

    /**
     * Every shared scan the given address participates in. Both parameters take the SAME address --
     * the derived-query grammar has no single-parameter "a or b" form. Case-insensitive on both
     * sides, because the address arrives from the OIDC token and its casing must not decide access.
     */
    List<SharedScan> findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(String ownerA, String ownerB);

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
