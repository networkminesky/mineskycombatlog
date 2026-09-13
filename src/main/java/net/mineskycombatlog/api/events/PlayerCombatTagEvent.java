package net.mineskycombatlog.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class PlayerCombatTagEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID attackerUuid;
    private boolean cancelled = false;

    public PlayerCombatTagEvent(Player player, UUID attackerUuid) {
        this.player = player;
        this.attackerUuid = attackerUuid;
    }

    public Player getPlayer() { return player; }
    public UUID getAttackerUuid() { return attackerUuid; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}