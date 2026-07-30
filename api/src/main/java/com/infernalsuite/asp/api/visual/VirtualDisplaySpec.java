package com.infernalsuite.asp.api.visual;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable initial state of a packet-only display.
 *
 * <p>Only the payload matching {@link #type()} is used. A text display uses
 * {@link #text()}, an item display uses {@link #item()}, and a block display
 * uses {@link #block()}.
 *
 * @param type display variant
 * @param location initial world position
 * @param transformation display transformation
 * @param billboard billboard constraint
 * @param text text payload
 * @param item item payload
 * @param block block payload
 * @param brightness optional fixed brightness
 * @param interpolationDuration client-side transformation interpolation in ticks
 * @param viewRange client-side view range multiplier
 * @param shadowRadius shadow radius
 * @param shadowStrength shadow opacity
 * @param textOpacity text opacity, from {@code 0} to {@code 255}
 * @param lineWidth maximum text line width
 * @param backgroundColor ARGB text background color
 * @param lifetimeTicks automatic lifetime; {@code 0} keeps it until explicitly destroyed
 * @since 26.2
 */
public record VirtualDisplaySpec(
    VirtualDisplayType type,
    Location location,
    Transformation transformation,
    Display.Billboard billboard,
    @Nullable Component text,
    @Nullable ItemStack item,
    @Nullable BlockData block,
    @Nullable Display.Brightness brightness,
    int interpolationDuration,
    float viewRange,
    float shadowRadius,
    float shadowStrength,
    int textOpacity,
    int lineWidth,
    int backgroundColor,
    long lifetimeTicks
) {

    public VirtualDisplaySpec {
        if (type == null || location == null || location.getWorld() == null
            || transformation == null || billboard == null) {
            throw new IllegalArgumentException("type, location, world, transformation and billboard are required");
        }
        if (interpolationDuration < 0 || lifetimeTicks < 0) {
            throw new IllegalArgumentException("durations must be positive");
        }
        if (viewRange < 0.0F || shadowRadius < 0.0F || shadowStrength < 0.0F) {
            throw new IllegalArgumentException("view and shadow values must be positive");
        }
        if (textOpacity < 0 || textOpacity > 255) {
            throw new IllegalArgumentException("textOpacity must be between 0 and 255");
        }
        location = location.clone();
        item = item == null ? null : item.clone();
        block = block == null ? null : block.clone();
    }

    /**
     * Creates a builder with safe defaults.
     *
     * @param type display variant
     * @param location initial location
     * @return a new builder
     * @since 26.2
     */
    public static Builder builder(VirtualDisplayType type, Location location) {
        return new Builder(type, location);
    }

    /**
     * Mutable construction helper for an immutable {@link VirtualDisplaySpec}.
     *
     * @since 26.2
     */
    public static final class Builder {
        private final VirtualDisplayType type;
        private final Location location;
        private Transformation transformation = new Transformation(
            new org.joml.Vector3f(),
            new org.joml.Quaternionf(),
            new org.joml.Vector3f(1.0F),
            new org.joml.Quaternionf()
        );
        private Display.Billboard billboard = Display.Billboard.CENTER;
        private Component text = Component.empty();
        private ItemStack item;
        private BlockData block;
        private Display.Brightness brightness;
        private int interpolationDuration;
        private float viewRange = 1.0F;
        private float shadowRadius;
        private float shadowStrength = 1.0F;
        private int textOpacity = 255;
        private int lineWidth = Integer.MAX_VALUE;
        private int backgroundColor;
        private long lifetimeTicks;

        private Builder(VirtualDisplayType type, Location location) {
            this.type = type;
            this.location = location;
        }

        public Builder transformation(Transformation value) {
            this.transformation = value;
            return this;
        }

        public Builder billboard(Display.Billboard value) {
            this.billboard = value;
            return this;
        }

        public Builder text(Component value) {
            this.text = value;
            return this;
        }

        public Builder item(ItemStack value) {
            this.item = value;
            return this;
        }

        public Builder block(BlockData value) {
            this.block = value;
            return this;
        }

        public Builder brightness(Display.Brightness value) {
            this.brightness = value;
            return this;
        }

        public Builder interpolationDuration(int value) {
            this.interpolationDuration = value;
            return this;
        }

        public Builder viewRange(float value) {
            this.viewRange = value;
            return this;
        }

        public Builder shadow(float radius, float strength) {
            this.shadowRadius = radius;
            this.shadowStrength = strength;
            return this;
        }

        public Builder textStyle(int opacity, int width, int background) {
            this.textOpacity = opacity;
            this.lineWidth = width;
            this.backgroundColor = background;
            return this;
        }

        public Builder lifetimeTicks(long value) {
            this.lifetimeTicks = value;
            return this;
        }

        public VirtualDisplaySpec build() {
            return new VirtualDisplaySpec(
                type, location, transformation, billboard, text, item, block, brightness,
                interpolationDuration, viewRange, shadowRadius, shadowStrength,
                textOpacity, lineWidth, backgroundColor, lifetimeTicks
            );
        }
    }
}
