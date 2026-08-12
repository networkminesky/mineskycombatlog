package net.mineskycombatlog;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MineSkyCombatLog extends JavaPlugin implements Listener {

    private final Map<UUID, Long> combatMap = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> taskMap = new ConcurrentHashMap<>();
    private final List<String> blockedCommands = new CopyOnWriteArrayList<>();

    private int combatTimeSeconds;
    private int barSegments;
    private String barActiveColor;
    private String barInactiveColor;
    private String barSymbol;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity victimEntity = event.getEntity();
        Entity damagerEntity = event.getDamager();

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
            tagPlayer(victimPlayer);
            tagPlayer(attackerPlayer);
            return;
        }

        if (victimPlayer != null && isHostile(actualDamager)) {
            tagPlayer(victimPlayer);
            return;
        }

        if (attackerPlayer != null && isHostile(victimEntity)) {
            tagPlayer(attackerPlayer);
            return;
        }
    }


    private boolean isHostile(Entity entity) {
        if (entity == null) return false;
        return entity instanceof org.bukkit.entity.Enemy;
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
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (isInCombat(player)) {
            player.setHealth(0.0);

            combatMap.remove(uuid);
            ScheduledTask task = taskMap.remove(uuid);
            if (task != null) {
                task.cancel();
            }

            String quitMsg = getConfig().getString("messages.player-quit-combat", "&c&lCOMBATE &8» &e%player% &7deslogou em combate!");
            quitMsg = formatColor(quitMsg.replace("%player%", player.getName()));
            Bukkit.broadcastMessage(quitMsg);
        }
    }

    private void tagPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        boolean wasInCombat = isInCombat(player);
        long expireTime = System.currentTimeMillis() + (combatTimeSeconds * 1000L);
        combatMap.put(uuid, expireTime);

        if (!wasInCombat) {
            String msg = getConfig().getString("messages.entered-combat", "&c&lCOMBATE &8» &7Você entrou em combate!");
            msg = msg.replace("%time%", String.valueOf(combatTimeSeconds));
            player.sendMessage(formatColor(msg));
        }

        if (!taskMap.containsKey(uuid)) {
            ScheduledTask task = player.getScheduler().runAtFixedRate(this, (scheduledTask) -> {
                if (!player.isOnline()) {
                    scheduledTask.cancel();
                    combatMap.remove(uuid);
                    taskMap.remove(uuid);
                    return;
                }

                Long currentExpire = combatMap.get(uuid);
                if (currentExpire == null) {
                    scheduledTask.cancel();
                    taskMap.remove(uuid);
                    return;
                }

                long remainingMillis = currentExpire - System.currentTimeMillis();

                if (remainingMillis <= 0) {
                    scheduledTask.cancel();
                    combatMap.remove(uuid);
                    taskMap.remove(uuid);

                    String msgLeft = getConfig().getString("messages.left-combat", "&a&lCOMBATE &8» &7Você saiu de combate.");
                    player.sendMessage(formatColor(msgLeft));
                    player.sendActionBar("");
                    return;
                }

                int remainingSeconds = (int) Math.ceil(remainingMillis / 1000.0);
                String progressBar = getProgressBar(remainingMillis);

                String actionBarMsg = getConfig().getString("messages.actionbar-format", "&fCombate: %bar% &7(%time%s)");
                actionBarMsg = actionBarMsg.replace("%bar%", progressBar)
                        .replace("%time%", String.valueOf(remainingSeconds));

                player.sendActionBar(formatColor(actionBarMsg));

            }, () -> {
                combatMap.remove(uuid);
                taskMap.remove(uuid);
            }, 1L, 20L);

            taskMap.put(uuid, task);
        }
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

    private boolean isInCombat(Player player) {
        Long expireTime = combatMap.get(player.getUniqueId());
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireTime) {
            combatMap.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private String formatColor(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}