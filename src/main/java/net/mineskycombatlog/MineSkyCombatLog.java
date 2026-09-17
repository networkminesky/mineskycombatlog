package net.mineskycombatlog;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.mineskycombatlog.api.MineSkyCombatLogAPI;
import net.mineskycombatlog.api.events.PlayerCombatEndEvent;
import net.mineskycombatlog.api.events.PlayerCombatTagEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MineSkyCombatLog extends JavaPlugin implements Listener, MineSkyCombatLogAPI {

    private static MineSkyCombatLog instance;

    private final Map<UUID, Long> combatMap = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> taskMap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastAttackerMap = new ConcurrentHashMap<>();
    private final Set<UUID> bypassTag = ConcurrentHashMap.newKeySet();
    private final List<String> blockedCommands = new CopyOnWriteArrayList<>();

    private int combatTimeSeconds;
    private int barSegments;
    private String barActiveColor;
    private String barInactiveColor;
    private String barSymbol;

    private boolean shuttingDown = false;

    public static MineSkyCombatLog getInstance() {
        return instance;
    }

    public static MineSkyCombatLogAPI getAPI() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        shuttingDown = false;
        saveDefaultConfig();
        loadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;

        for (ScheduledTask task : taskMap.values()) {
            if (task != null) {
                task.cancel();
            }
        }

        taskMap.clear();
        combatMap.clear();
        lastAttackerMap.clear();
    }

    private void loadConfiguration() {
        reloadConfig();
        combatTimeSeconds = getConfig().getInt("combat-time", 15);
        barSegments = getConfig().getInt("bar.total-segments", 18);
        barActiveColor = getConfig().getString("bar.active-color", "&d");
        barInactiveColor = getConfig().getString("bar.inactive-color", "&8");
        barSymbol = getConfig().getString("bar.symbol", "|");

        blockedCommands.clear();
        for (String cmd : getConfig().getStringList("blocked-commands")) {
            blockedCommands.add(cmd.toLowerCase());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("combatlog")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("combatlog.admin")) {
                    sender.sendMessage(ChatColor.RED + "Você não tem permissão para usar este comando.");
                    return true;
                }
                loadConfiguration();
                sender.sendMessage(ChatColor.GREEN + "Configuração do MineSkyCombatLog recarregada com sucesso!");
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isInCombat(Player player) {
        if (player == null) return false;
        return isInCombat(player.getUniqueId());
    }

    @Override
    public boolean isInCombat(UUID uuid) {
        if (uuid == null) return false;
        Long expireTime = combatMap.get(uuid);
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireTime) {
            combatMap.remove(uuid);
            lastAttackerMap.remove(uuid);
            return false;
        }
        return true;
    }

    @Override
    public int getRemainingSeconds(Player player) {
        if (player == null) return 0;
        return getRemainingSeconds(player.getUniqueId());
    }

    @Override
    public int getRemainingSeconds(UUID uuid) {
        long millis = getRemainingMillis(uuid);
        if (millis <= 0) return 0;
        return (int) Math.ceil(millis / 1000.0);
    }

    @Override
    public long getRemainingMillis(UUID uuid) {
        if (uuid == null) return 0L;
        Long expireTime = combatMap.get(uuid);
        if (expireTime == null) return 0L;

        long diff = expireTime - System.currentTimeMillis();
        return Math.max(0L, diff);
    }

    @Override
    public UUID getLastAttacker(Player player) {
        if (player == null) return null;
        return getLastAttacker(player.getUniqueId());
    }

    @Override
    public UUID getLastAttacker(UUID uuid) {
        if (!isInCombat(uuid)) return null;
        return lastAttackerMap.get(uuid);
    }

    @Override
    public void tagPlayer(Player player, UUID attackerUuid) {
        if (player == null || !player.isOnline()) return;

        PlayerCombatTagEvent event = new PlayerCombatTagEvent(player, attackerUuid);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        UUID uuid = player.getUniqueId();
        long expireTime = System.currentTimeMillis() + (combatTimeSeconds * 1000L);
        combatMap.put(uuid, expireTime);

        if (attackerUuid != null) {
            lastAttackerMap.put(uuid, attackerUuid);
        }

        disableFlight(player);

        long remainingMillis = combatTimeSeconds * 1000L;
        String progressBar = getProgressBar(remainingMillis);
        String actionBarMsg = getConfig().getString("messages.actionbar-format", "&fCombate: %bar% &7(%time%s)");
        actionBarMsg = actionBarMsg.replace("%bar%", progressBar)
                .replace("%time%", String.valueOf(combatTimeSeconds));
        player.sendActionBar(formatColor(actionBarMsg));

        if (!taskMap.containsKey(uuid)) {
            ScheduledTask task = player.getScheduler().runAtFixedRate(this, (scheduledTask) -> {
                if (!player.isOnline()) {
                    scheduledTask.cancel();
                    endCombatInternal(uuid, false, PlayerCombatEndEvent.Reason.QUIT);
                    return;
                }

                Long currentExpire = combatMap.get(uuid);
                if (currentExpire == null) {
                    scheduledTask.cancel();
                    endCombatInternal(uuid, false, PlayerCombatEndEvent.Reason.API);
                    return;
                }

                long remaining = currentExpire - System.currentTimeMillis();

                if (remaining <= 0) {
                    scheduledTask.cancel();
                    endCombatInternal(uuid, true, PlayerCombatEndEvent.Reason.EXPIRED);
                    return;
                }

                int remainingSeconds = (int) Math.ceil(remaining / 1000.0);
                String bar = getProgressBar(remaining);

                String msg = getConfig().getString("messages.actionbar-format", "&fCombate: %bar% &7(%time%s)");
                msg = msg.replace("%bar%", bar)
                        .replace("%time%", String.valueOf(remainingSeconds));

                player.sendActionBar(formatColor(msg));

            }, () -> {
                combatMap.remove(uuid);
                taskMap.remove(uuid);
                lastAttackerMap.remove(uuid);
            }, 20L, 20L);

            taskMap.put(uuid, task);
        }
    }

    @Override
    public void endCombat(Player player) {
        if (player != null) {
            endCombat(player.getUniqueId());
        }
    }

    @Override
    public void endCombat(UUID uuid) {
        endCombatInternal(uuid, false, PlayerCombatEndEvent.Reason.API);
    }

    private void endCombatInternal(UUID uuid, boolean notify, PlayerCombatEndEvent.Reason reason) {
        combatMap.remove(uuid);
        lastAttackerMap.remove(uuid);
        ScheduledTask task = taskMap.remove(uuid);
        if (task != null) {
            task.cancel();
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Bukkit.getPluginManager().callEvent(new PlayerCombatEndEvent(player, reason));

            player.getScheduler().run(this, (t) -> {
                if (notify) {
                    String actionBarLeft = getConfig().getString("messages.actionbar-left", "&a&l✔ &aVocê saiu de combate!");
                    player.sendActionBar(formatColor(actionBarLeft));
                } else {
                    player.sendActionBar("");
                }
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity victimEntity = event.getEntity();
        Entity damagerEntity = event.getDamager();

        if (bypassTag.contains(victimEntity.getUniqueId())) {
            return;
        }

        Player victimPlayer = null;
        Player attackerPlayer = null;

        if (victimEntity instanceof Player) {
            victimPlayer = (Player) victimEntity;
        }

        Entity actualDamager = damagerEntity;
        if (damagerEntity instanceof Projectile) {
            Projectile projectile = (Projectile) damagerEntity;
            if (projectile.getShooter() instanceof Entity) {
                actualDamager = (Entity) projectile.getShooter();
            }
        }

        if (actualDamager instanceof Player) {
            attackerPlayer = (Player) actualDamager;
        }

        if (victimPlayer != null && attackerPlayer != null && !victimPlayer.equals(attackerPlayer)) {
            tagPlayer(victimPlayer, attackerPlayer.getUniqueId());
            tagPlayer(attackerPlayer, victimPlayer.getUniqueId());
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (isInCombat(player)) {
            event.setCancelled(true);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isInCombat(player)) {
            String command = event.getMessage().substring(1).split(" ")[0].toLowerCase();
            if (blockedCommands.contains(command)) {
                event.setCancelled(true);
                String msg = getConfig().getString("messages.command-blocked", "&c&lCOMBATE &8» &7Este comando está bloqueado durante o combate!");
                player.sendMessage(formatColor(msg));
            }
        }
    }

    @EventHandler
    public void onElytra(EntityToggleGlideEvent e) {
        if(isInCombat(e.getEntity().getUniqueId())) {
            e.setCancelled(true);

            String msg = getConfig().getString("messages.elytra-blocked", "&c&lCOMBATE &8» &7O uso de elytras esta desativado durante o combate!");
            e.getEntity().sendMessage(formatColor(msg));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (shuttingDown || Bukkit.isStopping()) {
            endCombatInternal(uuid, false, PlayerCombatEndEvent.Reason.QUIT);
            return;
        }

        if (isInCombat(player)) {
            endCombatInternal(uuid, false, PlayerCombatEndEvent.Reason.QUIT);

            UUID attackerUuid = lastAttackerMap.remove(uuid);

            bypassTag.add(uuid);
            try {
                if (attackerUuid != null) {
                    Entity attackerEntity = Bukkit.getEntity(attackerUuid);
                    if (attackerEntity != null && !attackerEntity.isDead()) {
                        EntityDamageByEntityEvent damageEvent = new EntityDamageByEntityEvent(
                                attackerEntity,
                                player,
                                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                                99999.0
                        );
                        player.setLastDamageCause(damageEvent);

                        if (attackerEntity instanceof Player) {
                            player.setKiller((Player) attackerEntity);
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                bypassTag.remove(uuid);
            }

            player.setHealth(0.0);

            String quitMsg = getConfig().getString("messages.player-quit-combat", "&c&lCOMBATE &8» &e%player% &7deslogou em combate!");
            quitMsg = formatColor(quitMsg.replace("%player%", player.getName()));
            Bukkit.broadcastMessage(quitMsg);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity deadEntity = event.getEntity();
        UUID deadUuid = deadEntity.getUniqueId();

        if (deadEntity instanceof Player) {
            endCombatInternal(deadUuid, false, PlayerCombatEndEvent.Reason.DEATH);
        }

        List<UUID> playersInCombatWithDead = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : lastAttackerMap.entrySet()) {
            if (entry.getValue().equals(deadUuid)) {
                playersInCombatWithDead.add(entry.getKey());
            }
        }

        for (UUID playerId : playersInCombatWithDead) {
            endCombatInternal(playerId, true, PlayerCombatEndEvent.Reason.EXPIRED);
        }
    }

    private void disableFlight(Player player) {
        player.getScheduler().run(this, (task) -> {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            if (player.isFlying() || player.getAllowFlight()) {
                player.setFlying(false);
                player.setAllowFlight(false);
//                String msg = getConfig().getString("messages.fly-disabled", "&c&lCOMBATE &8» &7Seu voo foi desativado por entrar em combate!");
//                player.sendMessage(formatColor(msg));
            }
        }, null);
    }

    private String getProgressBar(long remainingMillis) {
        double maxTime = combatTimeSeconds * 1000.0;
        double percentage = (double) remainingMillis / maxTime;
        percentage = Math.max(0.0, Math.min(1.0, percentage));

        int activeCount = (int) Math.round(percentage * barSegments);
        int inactiveCount = barSegments - activeCount;

        StringBuilder bar = new StringBuilder();
        bar.append("&f[");
        bar.append(barActiveColor);
        for (int i = 0; i < activeCount; i++) {
            bar.append(barSymbol);
        }
        bar.append(barInactiveColor);
        for (int i = 0; i < inactiveCount; i++) {
            bar.append(barSymbol);
        }
        bar.append("&f]");

        return bar.toString();
    }

    private String formatColor(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}