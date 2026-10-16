package dev.btc.core.performance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.btc.core.config.BTCCoreConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chunk-load budget and the per-world cap (task 2.A.4-ter).
 *
 * The defect: the budget used to be charged on EVERY tick of EVERY projectile, so the 11th tick of
 * any projectile hit the budget and the projectile was removed. The budget is now charged only when
 * a chunk load is actually asked for (the NMS call site is proven in game, not here).
 */
class ProjectilePoolTest {

    private static final String WORLD = "pool-test-world";

    private boolean savedEnabled;
    private int savedPerTick;
    private int savedPerProjectile;

    @BeforeEach
    void setUp() {
        savedEnabled = BTCCoreConfig.projectilePoolingEnabled;
        savedPerTick = BTCCoreConfig.projectileMaxLoadsPerTick;
        savedPerProjectile = BTCCoreConfig.projectileMaxLoadsPerProjectile;
        BTCCoreConfig.projectilePoolingEnabled = true;
        BTCCoreConfig.projectileMaxLoadsPerTick = 10;
        BTCCoreConfig.projectileMaxLoadsPerProjectile = 10;
        ProjectilePool.onTickStart();
    }

    @AfterEach
    void tearDown() {
        BTCCoreConfig.projectilePoolingEnabled = savedEnabled;
        BTCCoreConfig.projectileMaxLoadsPerTick = savedPerTick;
        BTCCoreConfig.projectileMaxLoadsPerProjectile = savedPerProjectile;
        ProjectilePool.clearWorld(WORLD);
    }

    @Test
    @DisplayName("a projectile gets exactly max-loads-per-projectile loads, then is refused")
    void perProjectileBudgetIsExact() {
        BTCCoreConfig.projectileMaxLoadsPerProjectile = 3;

        for (int load = 0; load < 3; load++) {
            ProjectilePool.onTickStart();
            assertTrue(ProjectilePool.tryLoadChunk(7), "load " + load);
        }
        ProjectilePool.onTickStart();
        assertFalse(ProjectilePool.tryLoadChunk(7));
        assertTrue(ProjectilePool.tryLoadChunk(8), "another projectile keeps its own budget");
    }

    @Test
    @DisplayName("the per-tick budget is shared, and resets at the next tick")
    void perTickBudgetIsSharedAndResets() {
        BTCCoreConfig.projectileMaxLoadsPerTick = 2;

        assertTrue(ProjectilePool.tryLoadChunk(1));
        assertTrue(ProjectilePool.tryLoadChunk(2));
        assertFalse(ProjectilePool.tryLoadChunk(3));

        ProjectilePool.onTickStart();
        assertTrue(ProjectilePool.tryLoadChunk(3));
    }

    @Test
    @DisplayName("a refusal by the per-projectile budget does not eat the per-tick budget")
    void refusedProjectileDoesNotChargeTheTick() {
        BTCCoreConfig.projectileMaxLoadsPerProjectile = 1;
        BTCCoreConfig.projectileMaxLoadsPerTick = 2;

        assertTrue(ProjectilePool.tryLoadChunk(1));
        assertFalse(ProjectilePool.tryLoadChunk(1));
        assertFalse(ProjectilePool.tryLoadChunk(1));
        assertTrue(ProjectilePool.tryLoadChunk(2), "one tick load is still free");
    }

    @Test
    @DisplayName("pooling disabled grants every load")
    void disabledGrantsEverything() {
        BTCCoreConfig.projectilePoolingEnabled = false;
        BTCCoreConfig.projectileMaxLoadsPerProjectile = 0;
        BTCCoreConfig.projectileMaxLoadsPerTick = 0;

        assertTrue(ProjectilePool.tryLoadChunk(1));
    }

    @Test
    @DisplayName("the world cap refuses the 201st projectile and frees a slot on removal")
    void worldCapRefusesThenFrees() {
        for (int id = 0; id < 200; id++) {
            assertTrue(ProjectilePool.admit(id, WORLD), "projectile " + id);
        }
        assertFalse(ProjectilePool.admit(200, WORLD));

        ProjectilePool.release(0);
        assertTrue(ProjectilePool.admit(201, WORLD));
    }

    @Test
    @DisplayName("releasing twice, or releasing a projectile never counted, frees nothing more")
    void releaseIsOneShot() {
        for (int id = 0; id < 200; id++) {
            ProjectilePool.admit(id, WORLD);
        }

        // A hit and a removal used to both release the same projectile: the counter drifted down
        // and the cap stopped holding.
        ProjectilePool.release(0);
        ProjectilePool.release(0);
        ProjectilePool.release(9999);

        assertTrue(ProjectilePool.admit(300, WORLD));
        assertFalse(ProjectilePool.admit(301, WORLD));
    }
}
