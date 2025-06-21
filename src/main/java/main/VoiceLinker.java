package main;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;

public class VoiceLinker extends JavaPlugin implements Listener, CommandExecutor {

    // Configuration variables
    private String apiBaseUrl;
    private double movementThreshold;
    private int updateInterval;
    private int apiTimeout;
    private int retryAttempts;
    private boolean debugEnabled;
    private boolean logPositionUpdates;
    private boolean logApiCalls;
    private boolean asyncApiCalls;
    private boolean cachePositions;
    private int maxConcurrentRequests;

    // Message templates
    private String messagePrefix;
    private String linkSuccessMsg;
    private String unlinkSuccessMsg;
    private String welcomeLinkedMsg;
    private String linkErrorMsg;
    private String connectionErrorMsg;
    private String notLinkedMsg;
    private String playerOnlyMsg;
    private String linkUsageMsg;
    private String commandUsageMsg;

    private HttpClient httpClient;
    private final Map<UUID, Location> lastPositions = new ConcurrentHashMap<>();
    private final Map<UUID, String> linkedPlayers = new ConcurrentHashMap<>();
    private Semaphore requestSemaphore;

    private final Gson gson = new Gson();
    private Path linkedDataPath;

    @Override
    public void onEnable() {
        // Ensure data folder exists
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Initialize linked data path
        linkedDataPath = new File(getDataFolder(), "linked_players.json").toPath();

        // Save default config if it doesn't exist
        saveDefaultConfig();
        loadConfiguration();
        loadLinkedPlayers();

        // Initialize HTTP client with timeout
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(apiTimeout))
                .build();

        // Initialize semaphore for controlling concurrent requests
        this.requestSemaphore = new Semaphore(maxConcurrentRequests);

        // Register events and commands
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("linkdiscord").setExecutor(this);
        getCommand("unlinkdiscord").setExecutor(this);
        getCommand("voicelinker").setExecutor(this);

        // Start periodic position update task
        long updateTicks = updateInterval * 20L; // Convert seconds to ticks
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::updateAllPlayerPositions, 0L, updateTicks);

        getLogger().info("§aPlugin by §bAns Studio §a- Thank you for using our product!");
        getLogger().info("API Base URL: " + apiBaseUrl);
        getLogger().info("Movement Threshold: " + movementThreshold + " blocks");
        getLogger().info("Update Interval: " + updateInterval + " seconds");
        getLogger().info("Max Concurrent Requests: " + maxConcurrentRequests);

        if (debugEnabled) {
            getLogger().info("Debug mode enabled - Detailed logging active");
        }
    }

    @Override
    public void onDisable() {
        saveLinkedPlayers();
        if (httpClient != null) {
            // Note: HttpClient doesn't have a close() method in newer versions
            // The client will be garbage collected automatically
        }
        getLogger().info(messagePrefix + " Plugin has been disabled!");
    }

    private void loadLinkedPlayers() {
        if (!Files.exists(linkedDataPath)) {
            getLogger().info("No linked_players.json file found. Starting with empty linked players list.");
            return;
        }

        try {
            String json = Files.readString(linkedDataPath);
            Type type = new TypeToken<Map<UUID, String>>() {}.getType();
            Map<UUID, String> loaded = gson.fromJson(json, type);
            if (loaded != null) {
                linkedPlayers.clear();
                linkedPlayers.putAll(loaded);

                getLogger().info("=== LOADED LINKED PLAYERS DATA ===");
                getLogger().info("Total linked players: " + linkedPlayers.size());

                if (debugEnabled && !linkedPlayers.isEmpty()) {
                    getLogger().info("Linked Players Details:");
                    for (Map.Entry<UUID, String> entry : linkedPlayers.entrySet()) {
                        String playerName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                        getLogger().info("  - Player: " + (playerName != null ? playerName : "Unknown") +
                                " (UUID: " + entry.getKey() + ") -> Discord ID: " + entry.getValue());
                    }
                }
            } else {
                getLogger().info("Linked players file is empty or invalid.");
            }
        } catch (IOException e) {
            getLogger().warning("Failed to load linked_players.json: " + e.getMessage());
        } catch (Exception e) {
            getLogger().warning("Error parsing linked_players.json: " + e.getMessage());
        }
    }

    private void saveLinkedPlayers() {
        try {
            String json = gson.toJson(linkedPlayers);
            Files.writeString(linkedDataPath, json);
        } catch (IOException e) {
            getLogger().warning("Failed to save linked_players.json: " + e.getMessage());
        }
    }

    private void loadConfiguration() {
        reloadConfig();

        // Load API configuration
        apiBaseUrl = getConfig().getString("api.base_url", "http://localhost:3000");
        apiTimeout = getConfig().getInt("api.timeout", 10);
        retryAttempts = getConfig().getInt("api.retry_attempts", 3);

        // Load position settings
        movementThreshold = getConfig().getDouble("position.movement_threshold", 2.0);
        updateInterval = getConfig().getInt("position.update_interval", 2);

        // Load debug settings
        debugEnabled = getConfig().getBoolean("debug.enabled", false);
        logPositionUpdates = getConfig().getBoolean("debug.log_position_updates", false);
        logApiCalls = getConfig().getBoolean("debug.log_api_calls", false);

        // Load performance settings
        asyncApiCalls = getConfig().getBoolean("performance.async_api_calls", true);
        cachePositions = getConfig().getBoolean("performance.cache_positions", true);
        maxConcurrentRequests = getConfig().getInt("performance.max_concurrent_requests", 10);

        // Load messages
        messagePrefix = getConfig().getString("messages.prefix", "§b[Ans VoiceLinker]§r");
        linkSuccessMsg = getConfig().getString("messages.link_success", "§a Successfully linked with Discord ID: {discord_id}");
        unlinkSuccessMsg = getConfig().getString("messages.unlink_success", "§a Successfully unlinked from Discord!");
        welcomeLinkedMsg = getConfig().getString("messages.welcome_linked", "§a Welcome! Your Discord account is now linked.");
        linkErrorMsg = getConfig().getString("messages.link_error", "§c Error linking with Discord: {error}");
        connectionErrorMsg = getConfig().getString("messages.connection_error", "§c Could not connect to the Discord bot!");
        notLinkedMsg = getConfig().getString("messages.not_linked", "§c You haven't linked your Discord account yet!");
        playerOnlyMsg = getConfig().getString("messages.player_only", "§c This command can only be used by players!");
        linkUsageMsg = getConfig().getString("messages.link_usage", "§e Use /linkdiscord <Discord_ID> to link your Discord account!");
        commandUsageMsg = getConfig().getString("messages.command_usage", "§c Usage: /linkdiscord <Discord_ID>");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lastPositions.put(player.getUniqueId(), player.getLocation());

        // Check if player has linked Discord account
        if (linkedPlayers.containsKey(player.getUniqueId())) {
            player.sendMessage(messagePrefix + " " + welcomeLinkedMsg);
            updatePlayerPosition(player);
        } else {
            player.sendMessage(messagePrefix + " " + linkUsageMsg);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPositions.remove(uuid);
        // Keep linkedPlayers to maintain the link across sessions
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Only process if player has linked Discord account
        if (!linkedPlayers.containsKey(uuid)) {
            return;
        }

        Location currentLocation = event.getTo();
        if (currentLocation == null) return;

        Location lastLocation = lastPositions.get(uuid);

        if (lastLocation == null) {
            lastPositions.put(uuid, currentLocation);
            return;
        }

        // Check if player has moved far enough
        if (currentLocation.distance(lastLocation) >= movementThreshold) {
            if (cachePositions) {
                lastPositions.put(uuid, currentLocation);
            }
            updatePlayerPosition(player);

            if (logPositionUpdates) {
                getLogger().info("Position update for " + player.getName() +
                        ": (" + String.format("%.2f, %.2f, %.2f",
                        currentLocation.getX(), currentLocation.getY(), currentLocation.getZ()) + ")");
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(messagePrefix + " " + playerOnlyMsg);
            return true;
        }

        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "linkdiscord":
                if (args.length != 1) {
                    player.sendMessage(messagePrefix + " " + commandUsageMsg);
                    return true;
                }

                String discordId = args[0];
                linkDiscordAccount(player, discordId);
                return true;

            case "unlinkdiscord":
                unlinkDiscordAccount(player);
                return true;

            case "voicelinker":
                if (args.length > 0) {
                    switch (args[0].toLowerCase()) {
                        case "reload":
                            if (player.hasPermission("voicelinker.reload")) {
                                loadConfiguration();
                                player.sendMessage(messagePrefix + " §aConfiguration reloaded!");
                                return true;
                            } else {
                                player.sendMessage(messagePrefix + " §cYou don't have permission to use this command!");
                                return true;
                            }

                        case "list":
                            if (player.hasPermission("voicelinker.admin")) {
                                player.sendMessage(messagePrefix + " §eLinked Players List:");
                                if (linkedPlayers.isEmpty()) {
                                    player.sendMessage("§7No players are currently linked.");
                                } else {
                                    for (Map.Entry<UUID, String> entry : linkedPlayers.entrySet()) {
                                        String playerName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                                        player.sendMessage("§7- §f" + (playerName != null ? playerName : "Unknown") +
                                                " §7-> Discord ID: §f" + entry.getValue());
                                    }
                                }
                                return true;
                            } else {
                                player.sendMessage(messagePrefix + " §cYou don't have permission to use this command!");
                                return true;
                            }
                    }
                }

                // Show plugin info
                player.sendMessage(messagePrefix + " §ePlugin Information:");
                player.sendMessage("§7- API URL: §f" + apiBaseUrl);
                player.sendMessage("§7- Movement Threshold: §f" + movementThreshold + " blocks");
                player.sendMessage("§7- Proximity Distance: §f" + getConfig().getInt("position.proximity_distance", 30) + " blocks");
                player.sendMessage("§7- Update Interval: §f" + updateInterval + " seconds");
                player.sendMessage("§7- Total Linked Players: §f" + linkedPlayers.size());
                player.sendMessage("§7- Your Link Status: " +
                        (linkedPlayers.containsKey(player.getUniqueId()) ?
                                "§aLinked (Discord ID: " + linkedPlayers.get(player.getUniqueId()) + ")" :
                                "§cNot linked"));

                if (player.hasPermission("voicelinker.admin")) {
                    player.sendMessage("§7- Admin Commands: §f/voicelinker reload, /voicelinker list");
                }
                return true;

            default:
                return false;
        }
    }

    private void linkDiscordAccount(Player player, String discordId) {
        Runnable linkTask = () -> {
            if (!requestSemaphore.tryAcquire()) {
                Bukkit.getScheduler().runTask(this, () ->
                        player.sendMessage(messagePrefix + " §cThe server is busy, please try again later!"));
                return;
            }

            try {
                String jsonBody = String.format(
                        "{\"uuid\":\"%s\",\"discordId\":\"%s\"}",
                        player.getUniqueId().toString(),
                        discordId
                );

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiBaseUrl + "/link"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(apiTimeout))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                if (logApiCalls) {
                    getLogger().info("API Call: POST " + apiBaseUrl + "/link - " + jsonBody);
                }

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                Bukkit.getScheduler().runTask(this, () -> {
                    if (response.statusCode() == 200) {
                        linkedPlayers.put(player.getUniqueId(), discordId);
                        saveLinkedPlayers();
                        String successMsg = linkSuccessMsg.replace("{discord_id}", discordId);
                        player.sendMessage(messagePrefix + " " + successMsg);
                        updatePlayerPosition(player);

                        if (debugEnabled) {
                            getLogger().info("Successfully linked " + player.getName() + " with Discord ID: " + discordId);
                        }
                    } else {
                        String errorMsg = linkErrorMsg.replace("{error}", response.body());
                        player.sendMessage(messagePrefix + " " + errorMsg);

                        if (debugEnabled) {
                            getLogger().warning("Link failed for " + player.getName() + ": " + response.body());
                        }
                    }
                });

            } catch (IOException | InterruptedException e) {
                Bukkit.getScheduler().runTask(this, () -> {
                    player.sendMessage(messagePrefix + " " + connectionErrorMsg);
                    getLogger().warning("Connection error while linking " + player.getName() + ": " + e.getMessage());
                });
            } finally {
                requestSemaphore.release();
            }
        };

        if (asyncApiCalls) {
            CompletableFuture.runAsync(linkTask);
        } else {
            linkTask.run();
        }
    }

    private void unlinkDiscordAccount(Player player) {
        UUID uuid = player.getUniqueId();
        if (linkedPlayers.containsKey(uuid)) {
            linkedPlayers.remove(uuid);
            player.sendMessage(messagePrefix + " " + unlinkSuccessMsg);
            saveLinkedPlayers();

            if (debugEnabled) {
                getLogger().info("Unlinked Discord account for " + player.getName());
            }
        } else {
            player.sendMessage(messagePrefix + " " + notLinkedMsg);
        }
    }

    private void updatePlayerPosition(Player player) {
        if (!linkedPlayers.containsKey(player.getUniqueId())) {
            return;
        }

        Runnable updateTask = () -> {
            if (!requestSemaphore.tryAcquire()) {
                if (debugEnabled) {
                    getLogger().info("Skipping position update for " + player.getName() + " - too many concurrent requests");
                }
                return;
            }

            try {
                Location loc = player.getLocation();
                String jsonBody = String.format(
                        "{\"uuid\":\"%s\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}",
                        player.getUniqueId().toString(),
                        loc.getX(),
                        loc.getY(),
                        loc.getZ()
                );

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiBaseUrl + "/update-position"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(apiTimeout))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                if (logApiCalls) {
                    getLogger().info("API Call: POST " + apiBaseUrl + "/update-position - " + jsonBody);
                }

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200 && debugEnabled) {
                    getLogger().warning("Position update failed for " + player.getName() + ": " + response.body());
                }

            } catch (IOException | InterruptedException e) {
                if (debugEnabled) {
                    getLogger().warning("Cannot update position for " + player.getName() + ": " + e.getMessage());
                }
            } finally {
                requestSemaphore.release();
            }
        };

        if (asyncApiCalls) {
            CompletableFuture.runAsync(updateTask);
        } else {
            updateTask.run();
        }
    }

    private void updateAllPlayerPositions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (linkedPlayers.containsKey(player.getUniqueId())) {
                updatePlayerPosition(player);
            }
        }
    }
}