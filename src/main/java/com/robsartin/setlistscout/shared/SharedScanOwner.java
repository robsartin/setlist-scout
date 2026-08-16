package com.robsartin.setlistscout.shared;

import java.util.UUID;

/**
 * The one definition of "this owner string is a shared scan, not a person" (#163).
 * <p>
 * A shared scan is a scan context that happens not to be a user: it owns artists, settings, jobs
 * and shows exactly as a person does, because every owner-scoped query in this app keys on an
 * opaque string and never requires it to be an email. This class is what keeps the two kinds
 * distinguishable.
 * <p>
 * Lives in {@code shared} (an OPEN module) because both {@code scan} and {@code expansion} need
 * the predicate to apply their guards, and neither should depend on the other.
 * <p>
 * The {@code shared:} prefix cannot collide with a real address: an email always contains
 * {@code @} and never begins with this prefix. Login is also unreachable for these keys --
 * {@code SecurityConfig} authorises against the configured {@code allowedEmails}, which a
 * generated key can never match.
 */
public final class SharedScanOwner {

    /** Prefix marking an owner string as a shared scan rather than a person. */
    public static final String PREFIX = "shared:";

    private SharedScanOwner() {
    }

    /**
     * @return true if {@code owner} identifies a shared scan. Null-safe: a null owner (no
     * authenticated principal) is not a shared scan.
     */
    public static boolean isSharedScanKey(String owner) {
        return owner != null && owner.startsWith(PREFIX);
    }

    /**
     * A fresh, opaque owner key. Random rather than derived from the participants' addresses so
     * the key stays stable if a participant is ever swapped, and so it carries no personal data.
     * Ordering is irrelevant here, so a plain random UUID is used rather than the UUIDv7 generator
     * the app uses for correlation ids.
     */
    public static String newKey() {
        return PREFIX + UUID.randomUUID();
    }
}
