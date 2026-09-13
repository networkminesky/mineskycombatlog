package net.mineskycombatlog.api;

import org.bukkit.entity.Player;

import java.util.UUID;

public interface MineSkyCombatLogAPI {
    boolean isInCombat(Player player);
    boolean isInCombat(UUID uuid);

    int getRemainingSeconds(Player player);
    int getRemainingSeconds(UUID uuid);

    long getRemainingMillis(UUID uuid);

    UUID getLastAttacker(Player player);
    UUID getLastAttacker(UUID uuid);

    void tagPlayer(Player player, UUID attackerUuid);

    void endCombat(Player player);
    void endCombat(UUID uuid);
}