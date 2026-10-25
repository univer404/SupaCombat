package com.supacombat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SupaCombat extends JavaPlugin implements Listener {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Integer> combatTimers = new HashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();
    private final Set<UUID> loggedOutPlayers = new HashSet<>();
    private final Map<String, Long> rewardCooldowns = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask timerTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        timerTask = Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 20L, 20L);
        getLogger().info("SupaCombat enabled.");
    }

    @Override
    public void onDisable() {
        if (timerTask != null) timerTask.cancel();
        saveData();
        combatTimers.clear();
        lastHit.clear();
    }

    private void loadData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        rewardCooldowns.clear();
        for (String key : data.getConfigurationSection("reward-cooldowns") == null
                ? Set.<String>of() : data.getConfigurationSection("reward-cooldowns").getKeys(false)) {
            rewardCooldowns.put(key, data.getLong("reward-cooldowns." + key));
        }
    }

    private void saveData() {
        if (data == null || dataFile == null) return;
        data.set("reward-cooldowns", null);
        rewardCooldowns.forEach((key, value) -> data.set("reward-cooldowns." + key, value));
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save data.yml: " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!getConfig().getBoolean("combat.tag-on-player-damage", true)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player player) {
            attacker = player;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            attacker = player;
        }
        if (attacker == null || attacker.equals(victim)) return;
        int duration = Math.max(1, getConfig().getInt("combat.duration-seconds", 21));
        combatTimers.put(attacker.getUniqueId(), duration);
        combatTimers.put(victim.getUniqueId(), duration);
        lastHit.put(attacker.getUniqueId(), victim.getUniqueId());
        lastHit.put(victim.getUniqueId(), attacker.getUniqueId());
    }

    private void tickCombat() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Integer remaining = combatTimers.get(uuid);
            if (remaining == null) continue;
            int next = remaining - 1;
            if (next <= 0) {
                combatTimers.remove(uuid);
                lastHit.remove(uuid);
                continue;
            }
            combatTimers.put(uuid, next);
            if (getConfig().getBoolean("combat.actionbar.enabled", true)) {
                String text = getConfig().getString("combat.actionbar.text", "<#A8A8A8>Combat: <#37BFF9>{time}")
                        .replace("{time}", String.valueOf(next));
                player.sendActionBar(parse(text));
            }
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isInCombat(player) || player.hasPermission("supacombat.bypass")) return;
        String commandLine = event.getMessage().substring(1).trim().toLowerCase(Locale.ROOT);
        String[] parts = commandLine.split("\\s+");
        String root = parts.length == 0 ? "" : parts[0];
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        boolean blocked = false;
        for (String configured : getConfig().getStringList("blocked-commands")) {
            String normalized = configured.toLowerCase(Locale.ROOT).trim();
            if (normalized.equals("team home")) {
                if (parts.length >= 2 && root.equals("team") && parts[1].equals("home")) blocked = true;
            } else if (!normalized.contains(" ") && normalized.equals(root)) {
                blocked = true;
            }
            if (blocked) break;
        }
        if (!blocked) return;
        event.setCancelled(true);
        send(player, "messages.in-combat", "<red>You cannot do this in combat.");
        player.sendActionBar(parse(getConfig().getString("messages.in-combat", "<red>You cannot do this in combat.")));
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!getConfig().getBoolean("combat-logging.enabled", true) || !isInCombat(player)) {
            clearCombat(playerId);
            return;
        }
        UUID killerId = lastHit.get(playerId);
        Player killer = killerId == null ? null : Bukkit.getPlayer(killerId);
        loggedOutPlayers.add(playerId);
        clearCombat(playerId);
        if (killer != null && killer.isOnline() && !killer.equals(player)) {
            String message = getConfig().getString("messages.logout-broadcast", "<#FB0000>☠ {player} was slain by {killer}")
                    .replace("{player}", player.getName()).replace("{killer}", killer.getName());
            Bukkit.broadcast(parse(message));
            if (getConfig().getBoolean("combat-logging.increment-kills-statistic", true)) {
                killer.incrementStatistic(Statistic.PLAYER_KILLS);
            }
            rewardKiller(killer, player, true);
        }
        // Quit events fire while the player is leaving; explicitly damage them to death after the event.
        if (!player.isDead()) {
            player.setHealth(0.0);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        event.deathMessage(null);
        UUID victimId = victim.getUniqueId();
        UUID killerId = victim.getKiller() == null ? null : victim.getKiller().getUniqueId();
        if (killerId == null) killerId = lastHit.get(victimId);
        Player killer = killerId == null ? null : Bukkit.getPlayer(killerId);
        clearCombat(victimId);
        if (killer == null || killer.equals(victim)) return;
        String message = getConfig().getString("messages.kill-broadcast", "<#FB0000>☠ {victim} was slain by {killer}")
                .replace("{victim}", victim.getName()).replace("{killer}", killer.getName());
        Bukkit.broadcast(parse(message));
        clearCombat(killer.getUniqueId());
        if (getConfig().getBoolean("combat-logging.reward-on-normal-kill", true)) {
            rewardKiller(killer, victim, false);
        }
    }

    private void rewardKiller(Player killer, Player victim, boolean combatLogout) {
        long cooldownMillis = Math.max(0, getConfig().getLong("combat-logging.reward-cooldown-hours", 24)) * 3_600_000L;
        String key = killer.getUniqueId() + "_" + victim.getUniqueId();
        long now = Instant.now().toEpochMilli();
        Long previous = rewardCooldowns.get(key);
        if (previous != null && now - previous < cooldownMillis) return;
        rewardCooldowns.put(key, now);
        String rewardMessage = getConfig().getString("messages.shard-reward", "<#A303F8>+★10 <#A2A9B5>for killing {victim}")
                .replace("{victim}", victim.getName());
        send(killer, rewardMessage);
        killer.sendActionBar(parse(rewardMessage));
        String command = getConfig().getString("combat-logging.punish-killer-command", "giveshards give {killer} 10 -s")
                .replace("{killer}", killer.getName()).replace("{victim}", victim.getName()).trim();
        if (!command.isEmpty()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
        saveData();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        clearCombat(event.getPlayer().getUniqueId());
        loggedOutPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player && loggedOutPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private boolean isInCombat(Player player) {
        return combatTimers.containsKey(player.getUniqueId());
    }

    private void clearCombat(UUID uuid) {
        combatTimers.remove(uuid);
        lastHit.remove(uuid);
    }

    private Component parse(String text) {
        return miniMessage.deserialize(text == null ? "" : text);
    }

    private void send(CommandSender sender, String path, String fallback) {
        sender.sendMessage(parse(getConfig().getString(path, fallback)));
    }

    private void send(CommandSender sender, String path) {
        send(sender, path, "");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("supacombat")) return false;
        if (!sender.hasPermission("supacombat.admin")) {
            send(sender, "messages.no-permission", "<red>You don't have permission to use this.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            send(sender, "messages.reloaded", "<green>SupaCombat configuration reloaded.");
            return true;
        }
        sender.sendMessage(parse("<yellow>Usage: /supacombat reload"));
        return true;
    }
}