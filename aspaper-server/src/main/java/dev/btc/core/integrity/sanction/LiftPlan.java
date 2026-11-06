package dev.btc.core.integrity.sanction;

import java.util.List;
import java.util.UUID;

/**
 * What {@code /unban} and {@code /unmute} may lift for a target (AD-090).
 *
 * <p>Enforcement reads sanctions across every linked account, so the standing list for a target can
 * hold a sanction issued against <em>another</em> account of the same person. Lifting "the first one
 * found" could revoke that other account's sanction while announcing it for the target. By default only
 * the sanctions recorded against the target itself are lifted; the others are reported, not touched,
 * and lifting them takes the explicit {@code --linked} choice.
 *
 * @param toLift the sanctions to revoke
 * @param stillBlocking sanctions of the same type on a linked account that this lift leaves in force
 */
public record LiftPlan(List<Sanction> toLift, List<Sanction> stillBlocking) {

    /**
     * @param active everything in force for the target and its aliases
     * @param includeLinked whether the moderator asked to lift the linked accounts' sanctions too
     */
    public static LiftPlan of(UUID target, SanctionType type, List<Sanction> active, boolean includeLinked) {
        List<Sanction> ofType = active.stream().filter(sanction -> sanction.type() == type).toList();
        if (includeLinked) {
            return new LiftPlan(ofType, List.of());
        }
        List<Sanction> own = ofType.stream().filter(sanction -> isAgainst(sanction, target)).toList();
        List<Sanction> linked = ofType.stream().filter(sanction -> !isAgainst(sanction, target)).toList();
        return new LiftPlan(own, linked);
    }

    private static boolean isAgainst(Sanction sanction, UUID target) {
        return sanction.playerUuid().filter(target::equals).isPresent();
    }
}
