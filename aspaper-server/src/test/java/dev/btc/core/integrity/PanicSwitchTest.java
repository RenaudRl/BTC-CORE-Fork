package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.CheckActivationPolicy;
import dev.btc.core.api.integrity.IntegrityAPI.CheckDefinition;
import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationModel;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the one behaviour that makes the panic switch worth having.
 *
 * <p>Not "does the flag flip" — that is trivial. The claim under test is that panic suspends
 * <em>acting</em> while leaving detection intact, because a switch that also blinds the engine is just
 * a slower way of turning the anticheat off.
 */
class PanicSwitchTest {

    private CheckRegistry registry;
    private Plugin owner;
    private CheckId checkId;

    @BeforeEach
    void setUp() {
        registry = new CheckRegistry();
        owner = Mockito.mock(Plugin.class);
        checkId = new CheckId("test", "speed");

        registry.register(owner, new CheckDefinition(
            checkId, CheckGroup.MOVEMENT,
            CheckActivationPolicy.always(),
            new ViolationModel(1.0, 10.0, 20.0, false)));
    }

    @AfterEach
    void tearDown() {
        // A leaked panic would silently disarm every later test in this class.
        PanicSwitch.release("test");
    }

    @Test
    @DisplayName("without panic, a level past the threshold warrants a response")
    void armedByDefault() {
        assertFalse(PanicSwitch.engaged());
        assertEquals(CheckRegistry.Response.SETBACK, registry.responseFor(checkId, 12.0));
        assertEquals(CheckRegistry.Response.RESTRICT, registry.responseFor(checkId, 25.0));
    }

    @Test
    @DisplayName("panic suspends every response, whatever the level")
    void panicSuspendsResponses() {
        PanicSwitch.engage("staff", "false positives on the new launch mechanic");

        assertEquals(CheckRegistry.Response.NONE, registry.responseFor(checkId, 12.0));
        assertEquals(CheckRegistry.Response.NONE, registry.responseFor(checkId, 9999.0),
            "no level may get past panic; otherwise the switch is not a switch");
    }

    @Test
    @DisplayName("panic does not unregister the check: detection survives it")
    void detectionSurvivesPanic() {
        PanicSwitch.engage("staff", "incident");

        // The distinction the whole class exists for: the check is still there, still known, still
        // accumulating. Only the response stopped. If this ever fails, panic has become "disable".
        assertTrue(registry.registered().contains(checkId),
            "panic must not remove the check; the record of the incident depends on it still running");
    }

    @Test
    @DisplayName("releasing re-arms the responses")
    void releaseRearms() {
        PanicSwitch.engage("staff", "incident");
        PanicSwitch.release("staff");

        assertFalse(PanicSwitch.engaged());
        assertEquals(CheckRegistry.Response.SETBACK, registry.responseFor(checkId, 12.0));
        assertTrue(PanicSwitch.describe().isEmpty());
    }

    @Test
    @DisplayName("an engaged panic says who and why")
    void describesItself() {
        PanicSwitch.engage("Renaud", "launch mechanic");
        String description = PanicSwitch.describe().orElseThrow();

        // Staff arriving mid-incident need to know why nothing is reacting without reading the console.
        assertTrue(description.contains("Renaud"), description);
        assertTrue(description.contains("launch mechanic"), description);
    }
}
