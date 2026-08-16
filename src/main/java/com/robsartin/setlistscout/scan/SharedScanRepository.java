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
}
