-- #163: a shared scan is a scan context shared by two users. It holds identity only --
-- WHO is sharing. Location/radius/window deliberately live in search_settings under this
-- row's owner_key, so SettingsService, the settings-edit flow, and the existing
-- SettingsChanged -> re-due-every-scan-job behaviour all apply to shared scans unchanged.
CREATE TABLE IF NOT EXISTS shared_scan (
    id         bigserial PRIMARY KEY,
    owner_key  varchar(255) NOT NULL UNIQUE,
    owner_a    varchar(255) NOT NULL,
    owner_b    varchar(255) NOT NULL,
    label      varchar(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);

-- Lookup is "which shared scans is this signed-in user part of", on every page load.
CREATE INDEX IF NOT EXISTS idx_shared_scan_owner_a ON shared_scan (owner_a);
CREATE INDEX IF NOT EXISTS idx_shared_scan_owner_b ON shared_scan (owner_b);
