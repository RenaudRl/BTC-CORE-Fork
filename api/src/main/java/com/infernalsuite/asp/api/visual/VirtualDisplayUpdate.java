package com.infernalsuite.asp.api.visual;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;

/**
 * Sparse update for an existing packet-only display. Null properties retain
 * their current client-side value.
 *
 * @param location new absolute position
 * @param transformation new transformation
 * @param text new text payload
 * @param item new item payload
 * @param brightness new fixed brightness
 * @param interpolationDuration new interpolation duration
 * @param textOpacity new text opacity
 * @since 26.2
 */
public record VirtualDisplayUpdate(
    @Nullable Location location,
    @Nullable Transformation transformation,
    @Nullable Component text,
    @Nullable ItemStack item,
    @Nullable Display.Brightness brightness,
    @Nullable Integer interpolationDuration,
    @Nullable Integer textOpacity
) {

    public VirtualDisplayUpdate {
        location = location == null ? null : location.clone();
        item = item == null ? null : item.clone();
        if (interpolationDuration != null && interpolationDuration < 0) {
            throw new IllegalArgumentException("interpolationDuration must be positive");
        }
        if (textOpacity != null && (textOpacity < 0 || textOpacity > 255)) {
            throw new IllegalArgumentException("textOpacity must be between 0 and 255");
        }
    }

    /**
     * Creates a transformation and position update.
     *
     * @param location new position
     * @param transformation new transformation
     * @param interpolationDuration interpolation duration in ticks
     * @return the sparse update
     * @since 26.2
     */
    public static VirtualDisplayUpdate transform(
        Location location,
        Transformation transformation,
        int interpolationDuration
    ) {
        return new VirtualDisplayUpdate(
            location, transformation, null, null, null, interpolationDuration, null
        );
    }
}
