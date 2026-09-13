package net.mineskycombatlog.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PlayerCombatEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Reason reason;

    public enum Reason {
        EXPIRED,
        DEATH,
        QUIT,
        API
    }

    public PlayerCombatEndEvent(Player player, Reason reason) {
        this.player = player;
        this.reason = reason;
    }

    public Player getPlayer() { return player; }
    public Reason getReason() { return reason; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}