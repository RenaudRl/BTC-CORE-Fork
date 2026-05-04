package dev.btc.core.security;

import dev.btc.core.config.AnticheatConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * BTCCore Native Async Anticheat Validator
 * This class offloads basic combat (Reach) and movement (Velocity) 
 * validation to a dedicated thread pool to preserve main thread performance.
 */
public class AsyncPacketValidator {

    private static ExecutorService validatorPool;
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Double> violationBuffers = new java.util.concurrent.ConcurrentHashMap<>();

    public static void init() {
        if (AnticheatConfig.asyncPacketValidationEnabled && validatorPool == null) {
            validatorPool = Executors.newFixedThreadPool(AnticheatConfig.asyncValidationThreads, r -> {
                Thread thread = new Thread(r, "BTCCore-AsyncValidator");
                thread.setDaemon(true);
                return thread;
            });
            Bukkit.getLogger().info("[BTCCore] Async Packet Validator initialized with " + AnticheatConfig.asyncValidationThreads + " threads.");
        }
    }

    public static boolean hasReachViolationBuffer(java.util.UUID uuid) {
        Double buffer = violationBuffers.get(uuid);
        return buffer != null && buffer >= AnticheatConfig.reachViolationBufferLimit;
    }

    public static void validateReach(ServerPlayer player, Entity target, double originX, double originY, double originZ, int latency) {
        if (!AnticheatConfig.asyncPacketValidationEnabled || validatorPool == null) return;
        if (!AnticheatConfig.reachCheckEnabled || player.getBukkitEntity().hasPermission("btccore.anticheat.bypass")) return; // By-pass for OP

        long targetTimeMs = System.currentTimeMillis() - latency;
        PlayerSimulationCache.GhostState ghostState = PlayerSimulationCache.getInterpolatedState(target.getUUID(), targetTimeMs);

        // Snapshot target AABB concurrently (Fall back to current if cache missing)
        final AABB targetBox = (ghostState != null) ? ghostState.boundingBox : target.getBoundingBox();
        final java.util.UUID playerUUID = player.getUUID();

        validatorPool.submit(() -> {
            try {
                double maxReach = player.getAbilities().instabuild ? AnticheatConfig.reachMaxDistanceCreative : AnticheatConfig.reachMaxDistanceSurvival;
                
                // Distance to hitbox logic
                Vec3 eyePos = new Vec3(originX, originY + player.getEyeHeight(), originZ);
                
                double distance = 0.0;
                if (AnticheatConfig.reachStrictHitboxMath) {
                    // Calculate closest point on AABB to eye position
                    double closestX = Math.clamp(eyePos.x, targetBox.minX, targetBox.maxX);
                    double closestY = Math.clamp(eyePos.y, targetBox.minY, targetBox.maxY);
                    double closestZ = Math.clamp(eyePos.z, targetBox.minZ, targetBox.maxZ);
                    distance = eyePos.distanceToSqr(closestX, closestY, closestZ);
                } else {
                    distance = eyePos.distanceToSqr(targetBox.getCenter());
                }

                if (distance > (maxReach * maxReach) + 0.1) { // 0.1 bonus buffer for raytrace inaccuracy
                    double vScore = violationBuffers.getOrDefault(playerUUID, 0.0) + 1.0;
                    violationBuffers.put(playerUUID, vScore);

                    handleViolation(player, "Reach", "Distance: " + String.format("%.2f", Math.sqrt(distance)) + " > " + maxReach + " (Buffer: " + vScore + ")", AnticheatConfig.reachViolationAction, player.getX(), player.getY(), player.getZ());
                } else if (AnticheatConfig.reachRaytraceEnabled) {
                    // Raytrace Line-of-Sight (Against the ghost AABB)
                    Vec3 end = eyePos.add(player.getLookAngle().scale(maxReach + 1.0));
                    if (targetBox.clip(eyePos, end).isEmpty()) {
                         double vScore = violationBuffers.getOrDefault(playerUUID, 0.0) + 0.5; // Raytrace is less certain
                         violationBuffers.put(playerUUID, vScore);
                         handleViolation(player, "Raytrace", "Line of sight blocked or missed AABB (Buffer: " + vScore + ")", AnticheatConfig.reachViolationAction, player.getX(), player.getY(), player.getZ());
                    } else {
                        violationBuffers.computeIfPresent(playerUUID, (k, v) -> Math.max(0.0, v - 0.2));
                    }
                } else {
                    // Decay buffer on valid hits
                    violationBuffers.computeIfPresent(playerUUID, (k, v) -> Math.max(0.0, v - 0.1));
                }
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, "[BTCCore] Error in Async Reach Validation", e);
            }
        });
    }

    public static void clearPlayer(java.util.UUID uuid) {
        violationBuffers.remove(uuid);
    }

    public static void validateVelocity(ServerPlayer player, double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        if (!AnticheatConfig.asyncPacketValidationEnabled || validatorPool == null) return;
        if (!AnticheatConfig.velocityCheckEnabled || player.getBukkitEntity().hasPermission("btccore.anticheat.bypass")) return;

        validatorPool.submit(() -> {
            try {
                double deltaX = Math.abs(toX - fromX);
                double deltaY = Math.abs(toY - fromY);
                double deltaZ = Math.abs(toZ - fromZ);

                // Ignore if in vehicle or elytra
                if (player.isPassenger() || player.isFallFlying()) return;

                if (deltaX > AnticheatConfig.velocityMaxDeltaXZ || deltaZ > AnticheatConfig.velocityMaxDeltaXZ || deltaY > AnticheatConfig.velocityMaxDeltaY) {
                    // Small heuristic to ignore heavy drops
                    if (deltaY < -3.0) return; 

                    handleViolation(player, "Velocity/Speed", String.format("Delta XZ: %.2f, %.2f | Y: %.2f", deltaX, deltaZ, deltaY), AnticheatConfig.velocityViolationAction, fromX, fromY, fromZ);
                }
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, "[BTCCore] Error in Async Velocity Validation", e);
            }
        });
    }

    private static void handleViolation(ServerPlayer player, String type, String details, int actionLevel, double fallbackX, double fallbackY, double fallbackZ) {
        if (!dev.btc.core.config.BTCCoreConfig.sentinelEnabled) return;

        if (actionLevel >= 1) {
            String log = ChatColor.DARK_RED + "[Sentinel] " + ChatColor.GOLD + player.getScoreboardName() + ChatColor.RED + " failed " + type + " check! " + ChatColor.GRAY + details;
            
            // Console warning
            Bukkit.getLogger().warning(ChatColor.stripColor(log));
            
            // Staff alerts
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                if (SentinelCommand.shouldReceiveAlerts(online)) {
                    online.sendMessage(log);
                }
            }

            if (dev.btc.core.config.BTCCoreConfig.sentinelMysqlLogging) {
                NativeAnticheatDB.reportViolation(player.getUUID().toString(), player.getScoreboardName(), type, details);
            }
        }
        if (actionLevel >= 2) {
            // Setback synchronisÃ© sur le thread principal de Folia
            player.level().getServer().execute(() -> {
                player.connection.teleport(fallbackX, fallbackY, fallbackZ, player.getYRot(), player.getXRot());
            });
        }
    }
}

