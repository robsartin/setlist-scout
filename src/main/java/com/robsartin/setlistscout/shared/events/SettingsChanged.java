package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when user settings are changed.
 * <p>
 * Published by: {@code settings} module.
 * Consumed by: {@code scan} module ({@code ScanJobListener.onSettingsChanged}, re-dues every
 * {@code scan_job} for the owner). Expansion isn't location-sensitive, so it does not listen.
 */
public record SettingsChanged(
	String owner
) {
}
