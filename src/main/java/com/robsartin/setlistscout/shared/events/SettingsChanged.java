package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when user settings are changed.
 * <p>
 * Published by: {@code settings} module.
 * Consumed by: {@code expansion} module (inert until PR3b).
 */
public record SettingsChanged(
	String owner
) {
}
