/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import eu.cloudnetservice.driver.inject.InjectionLayer;
import eu.cloudnetservice.driver.service.ServiceId;
import eu.cloudnetservice.wrapper.holder.ServiceInfoHolder;
import org.jetbrains.annotations.Nullable;

/**
 * The only class that names CloudNet types. {@link CloudNetServiceIdentity} loads it solely after
 * proving the wrapper is on the classpath, so a server run outside CloudNet never links it (same
 * isolation as {@code FloodgatePlatformSource}).
 */
final class CloudNetWrapperSource {

    private CloudNetWrapperSource() {
    }

    /** Asks the wrapper what it already knows about this service; {@code null} when it cannot say. */
    static @Nullable CloudNetServiceIdentity.Resolved read() {
        final ServiceId id = InjectionLayer.ext().instance(ServiceInfoHolder.class)
            .serviceInfo().serviceId();
        return CloudNetServiceIdentity.of(id.taskName(), id.nameSplitter(), id.taskServiceId());
    }
}
