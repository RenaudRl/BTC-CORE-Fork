package dev.btc.core.integrity;

import dev.btc.core.performance.DABManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mob DAB switched off gets its AI back on load; a NoAI mob somebody else chose is never touched. */
class DabMarkTest {

    /** A mob reduced to what DABManager reads and writes: its AI flag and its persistent data keys. */
    private static final class FakeMob {
        final AtomicBoolean ai;
        final Set<NamespacedKey> marks = new HashSet<>();
        final LivingEntity entity;

        FakeMob(boolean hasAi) {
            this.ai = new AtomicBoolean(hasAi);
            PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "has" -> marks.contains((NamespacedKey) args[0]);
                    case "set" -> marks.add((NamespacedKey) args[0]);
                    case "remove" -> marks.remove((NamespacedKey) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            this.entity = (LivingEntity) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {LivingEntity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAI" -> {
                        ai.set((Boolean) args[0]);
                        yield null;
                    }
                    case "hasAI" -> ai.get();
                    case "getPersistentDataContainer" -> pdc;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
    }

    @Test
    @DisplayName("a mob switched off by DAB carries the mark, and loses it with its AI restored")
    void markedMobIsRestored() {
        FakeMob mob = new FakeMob(true);

        DABManager.disableAi(mob.entity);
        assertFalse(mob.ai.get());
        assertTrue(DABManager.isMarked(mob.entity));

        assertTrue(DABManager.restoreIfMarked(mob.entity));
        assertTrue(mob.ai.get());
        assertFalse(DABManager.isMarked(mob.entity));
    }

    @Test
    @DisplayName("a NoAI mob without the mark (admin, NPC, model) is left exactly as it is")
    void unmarkedNoAiMobIsNeverTouched() {
        FakeMob mob = new FakeMob(false);

        assertFalse(DABManager.restoreIfMarked(mob.entity));

        assertFalse(mob.ai.get(), "AI must stay off");
        assertTrue(mob.marks.isEmpty());
    }

    @Test
    @DisplayName("a mob with AI is not marked by a restore pass")
    void mobWithAiIsUntouched() {
        FakeMob mob = new FakeMob(true);

        assertFalse(DABManager.restoreIfMarked(mob.entity));

        assertTrue(mob.ai.get());
    }
}
