package de.mark225.bmtrails;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Line;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BMTrails extends JavaPlugin implements Listener {
    public static record ConfigValue<T>(String key, T defaultValue) {

        public static FileConfiguration config;

        public static final ConfigValue<String> MARKER_SET_NAME = new ConfigValue<>("markerSetName", "Player Trails");
        public static final ConfigValue<Boolean> MARKER_SET_VISIBLE = new ConfigValue<>("markerSetVisibleDefault", true);
        public static final ConfigValue<Boolean> MARKER_SET_TOGGLEABLE = new ConfigValue<>("markerSetToggleable", true);
        public static final ConfigValue<List<String>> EXCLUDED_MAPS = new ConfigValue<>("excludedMaps", List.of());
        public static final ConfigValue<String> DEFAULT_COLOR = new ConfigValue<>("defaultTrailColor", "random");
        public static final ConfigValue<Boolean> PERMISSION_COLORS = new ConfigValue<>("usePermissionOverrides", false);
        public static final ConfigValue<List<String>> PERMISSION_OVERRIDES = new ConfigValue<>("permissionOverrides", List.of());
        public static final ConfigValue<Integer> SAMPLING_INTERVAL = new ConfigValue<>("samplingInterval", 20);
        public static final ConfigValue<Integer> MAX_TRAIL_POINTS = new ConfigValue<>("trailPointsMax", 100);
        public static final ConfigValue<Integer> TELEPORT_DETECTION_THRESHOLD = new ConfigValue<>("teleportDistance", 200);
        public static final ConfigValue<String> DISPLAY_NAME = new ConfigValue<>("displayName", "%player%");
        public static final ConfigValue<Boolean> PERMISSION_VISIBLE = new ConfigValue<>("usePermissionsForTrailVisibility", false);
        public static final ConfigValue<Integer> LINE_WIDTH = new ConfigValue<>("lineWidth", 2);
        public static final ConfigValue<Integer> MAX_DISTANCE = new ConfigValue<>("maxDistance", 1000);
        public static final ConfigValue<Boolean> PERSIST_TRAILS = new ConfigValue<>("persistTrails", true);
        public static final ConfigValue<Integer> HISTORY_RETENTION_DAYS = new ConfigValue<>("historyRetentionDays", 30);
        public static final ConfigValue<Boolean> SEPARATE_SESSION_TRAILS = new ConfigValue<>("separateSessionTrails", false);
        public static final ConfigValue<Map<String, String>> PLAYER_COLOR_OVERRIDES = new ConfigValue<>("playerColorOverrides", Map.of());

        public T getValue(){
            Object fromConfig = config.get(key, defaultValue);
            try{
                return (T) fromConfig;
            }catch (ClassCastException e){
                BMTrails.getInstance().getLogger().log(Level.WARNING, "Config value for %s does not match expected data type".formatted(key));
            }
            return defaultValue;
        }
    }

    private static BMTrails bmTrails;
    private static final String PERM_VISIBLE = "bmtrails.visible";
    private static final String PERM_COLOR_PREFIX = "bmtrails.color.";
    private static final String PERM_CUSTOM_COLOR = "bmtrails.customcolor";
    private static final DateTimeFormatter SESSION_LABEL_FORMAT = DateTimeFormatter.ofPattern("M/d/yy h:mma", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private ConcurrentMap<UUID, ConcurrentLinkedDeque<Vector3d>> currentTrails;
    private ConcurrentMap<UUID, Long> trailLastSeen;
    private ConcurrentMap<UUID, UUID> activeSessionIds;
    private ConcurrentMap<UUID, TrailSession> trailSessions;
    private ConcurrentMap<UUID, Color> colorCache;
    private ConcurrentMap<UUID, MarkerSet> markerSets;
    private ConcurrentMap<UUID, UUID> playerWorlds;
    private ConcurrentMap<UUID, String> nameCache;

    private Map<String, Color> colorPermissions;
    private Map<String, Color> playerColorOverrides;

    private List<Permission> permissions = new ArrayList<>();


    private BlueMapAPI blueMapAPI;

    private BukkitTask samplingTask;

    private boolean permissionFilter;

    private long lastUpdate = 0;
    private long lastCacheRefresh = 0;
    private long lastSave = 0;
    private int maxTrailLength;
    private String displayNamePreset;
    private Color defaultColor;
    private int defaultWidth;
    private int maxDistance;
    private int teleportDetectionThreshold;
    private boolean usePermissionColors;
    private boolean persistTrails;
    private int historyRetentionDays;
    private boolean separateSessionTrails;
    private File historyFile;


    private static final class TrailSession {
        private final UUID id;
        private final UUID player;
        private UUID world;
        private long startedAt;
        private long lastSeen;
        private final ConcurrentLinkedDeque<Vector3d> points;

        private TrailSession(UUID id, UUID player, UUID world, long startedAt, long lastSeen, Collection<Vector3d> points) {
            this.id = id;
            this.player = player;
            this.world = world;
            this.startedAt = startedAt;
            this.lastSeen = lastSeen;
            this.points = new ConcurrentLinkedDeque<>(points);
        }
    }

    public static BMTrails getInstance(){
        return bmTrails;
    }

    @Override
    public void onEnable() {
        bmTrails = this;
        BlueMapAPI.onDisable((api) -> {
            saveTrailHistory();
            if(samplingTask != null) samplingTask.cancel();
        });
        BlueMapAPI.onEnable((api) -> {
            blueMapAPI = api;
            getLogger().log(Level.INFO, "Enabling BMTrails");
            refreshConfig();
            createMarkerSets();
            registerPermissions();
            currentTrails = new ConcurrentHashMap<>();
            trailLastSeen = new ConcurrentHashMap<>();
            activeSessionIds = new ConcurrentHashMap<>();
            trailSessions = new ConcurrentHashMap<>();
            playerWorlds = new ConcurrentHashMap<>();
            historyFile = new File(getDataFolder(), "trail-history.yml");
            loadTrailHistory();
            Bukkit.getPluginManager().registerEvents(this, this);
            lastUpdate = 0;
            lastCacheRefresh = 0;
            lastSave = 0;
            samplingTask = Bukkit.getScheduler().runTaskTimer(this, this::samplingTask, 0, ConfigValue.SAMPLING_INTERVAL.getValue());
        });
    }

    public void refreshConfig(){
        ConfigValue.config = getConfig();
        saveDefaultConfig();
        permissionFilter = Boolean.TRUE.equals(ConfigValue.PERMISSION_VISIBLE.getValue());
        maxTrailLength = ConfigValue.MAX_TRAIL_POINTS.getValue();
        displayNamePreset = ConfigValue.DISPLAY_NAME.getValue();
        String colorString = ConfigValue.DEFAULT_COLOR.getValue();
        try{
            defaultColor = new Color(0xff000000 | Integer.parseInt(colorString, 16));
        }catch(NumberFormatException e){
            defaultColor = null;
        }
        defaultWidth = ConfigValue.LINE_WIDTH.getValue();
        usePermissionColors = ConfigValue.PERMISSION_COLORS.getValue();
        persistTrails = ConfigValue.PERSIST_TRAILS.getValue();
        historyRetentionDays = ConfigValue.HISTORY_RETENTION_DAYS.getValue();
        separateSessionTrails = ConfigValue.SEPARATE_SESSION_TRAILS.getValue();
        Pattern overridePattern = Pattern.compile("[a-zA-Z0-9]+:[0-9a-fA-F]{6}");
        colorPermissions = ConfigValue.PERMISSION_OVERRIDES.getValue().stream()
                .filter(str -> overridePattern.matcher(str).matches())
                .map(str -> {
                    String[] parts = str.split(":");
                    return new AbstractMap.SimpleEntry<>(parts[0], new Color(0xff000000 | Integer.parseInt(parts[1], 16)));
                }).collect(Collectors.toMap(ent -> ent.getKey(), ent -> ent.getValue()));
        playerColorOverrides = new HashMap<>();
        if(getConfig().isConfigurationSection(ConfigValue.PLAYER_COLOR_OVERRIDES.key())){
            getConfig().getConfigurationSection(ConfigValue.PLAYER_COLOR_OVERRIDES.key()).getValues(false).forEach((name, hex) -> {
                try{
                    playerColorOverrides.put(name.toLowerCase(Locale.ROOT), new Color(0xff000000 | Integer.parseInt(String.valueOf(hex), 16)));
                }catch(NumberFormatException e){
                    getLogger().log(Level.WARNING, "Ignoring invalid playerColorOverrides entry for " + name);
                }
            });
        }
        maxDistance = ConfigValue.MAX_DISTANCE.getValue();
        teleportDetectionThreshold = ConfigValue.TELEPORT_DETECTION_THRESHOLD.getValue();
    }

    private void createMarkerSets(){
        List<String> excludedMaps = ConfigValue.EXCLUDED_MAPS.getValue();
        markerSets = new ConcurrentHashMap<>();
        String name = ConfigValue.MARKER_SET_NAME.getValue();
        boolean visible = ConfigValue.MARKER_SET_VISIBLE.getValue();
        boolean toggleable = ConfigValue.MARKER_SET_TOGGLEABLE.getValue();
        for(World world : Bukkit.getWorlds()){
            BlueMapWorld bmw = blueMapAPI.getWorld(world.getUID()).orElse(null);
            if(bmw == null) continue;
            List<BlueMapMap> maps = bmw.getMaps().stream()
                    .filter(map -> !excludedMaps.contains(map.getId()))
                    .toList();
            if(maps.isEmpty()) continue;
            MarkerSet markerSet = MarkerSet.builder()
                    .label(name)
                    .defaultHidden(!visible)
                    .toggleable(toggleable)
                    .build();
            markerSets.put(world.getUID(), markerSet);
            maps.forEach(map -> map.getMarkerSets().put("bmtrails_" + map.getId() + "_" + world.getName(), markerSet));
        }
    }

    private void registerPermissions(){
        if(!permissions.isEmpty()) permissions.forEach(Bukkit.getPluginManager()::removePermission);
        permissions.clear();
        for(String colorName : colorPermissions.keySet()){
            Permission perm = new Permission(PERM_COLOR_PREFIX + colorName, PermissionDefault.FALSE);
            permissions.add(perm);
            Bukkit.getPluginManager().addPermission(perm);
        }
    }

    private void samplingTask(){
        Map<UUID, Location> locations = Bukkit.getOnlinePlayers().stream()
                .filter(player -> !permissionFilter || player.hasPermission(PERM_VISIBLE))
                .filter(player -> blueMapAPI.getWebApp().getPlayerVisibility(player.getUniqueId()))
                .filter(player -> markerSets.containsKey(player.getWorld().getUID()))
                .collect(Collectors.toMap(player -> player.getUniqueId(), player -> player.getLocation()));
        if(System.currentTimeMillis() - lastCacheRefresh >= 5000)
            refreshCaches();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> asyncCollectionTask(locations));
    }

    private Color resolveColor(Player player){
        Color playerOverride = playerColorOverrides.get(player.getName().toLowerCase(Locale.ROOT));
        if(playerOverride == null) playerOverride = playerColorOverrides.get(player.getUniqueId().toString().toLowerCase(Locale.ROOT));
        if(playerOverride != null) return playerOverride;
        if(usePermissionColors && player.hasPermission(PERM_CUSTOM_COLOR)){
            for(Map.Entry<String, Color> entry : colorPermissions.entrySet()){
                if(player.hasPermission(PERM_COLOR_PREFIX + entry.getKey())) return entry.getValue();
            }
        }
        if(defaultColor != null) return defaultColor;
        return new Color(0xff000000 | new Random(player.getName().hashCode()).nextInt());
    }

    private void refreshCaches(){
        lastCacheRefresh = System.currentTimeMillis();
        if(colorCache == null) colorCache = new ConcurrentHashMap<>();
        if(nameCache == null) nameCache = new ConcurrentHashMap<>();
        colorCache.clear();
        nameCache.clear();
        for(Player p : Bukkit.getOnlinePlayers()){
            if(usePermissionColors || permissionFilter)
                p.recalculatePermissions();
            colorCache.put(p.getUniqueId(), resolveColor(p));
            nameCache.put(p.getUniqueId(), ChatColor.stripColor(p.getDisplayName()));
        }
    }

    private void asyncCollectionTask(Map<UUID, Location> currentLocations){
        pruneExpiredHistory();
        if(!persistTrails) currentTrails.keySet().removeIf(key -> !currentLocations.containsKey(key));
        for(Map.Entry<UUID, Location> entry : currentLocations.entrySet()){
            UUID uuid = entry.getKey();
            Location loc = entry.getValue();
            Vector3d vector3d = Vector3d.from(loc.getX(), loc.getY(), loc.getZ());
            UUID world = loc.getWorld().getUID();
            trailLastSeen.put(uuid, System.currentTimeMillis());
            TrailSession session = ensureActiveSession(uuid, world);
            UUID previousWorld = playerWorlds.put(uuid, world);
            if(previousWorld != null && !previousWorld.equals(world)){
                currentTrails.put(uuid, new ConcurrentLinkedDeque<>(List.of(vector3d)));
                session.points.clear();
                session.points.addFirst(vector3d);
                session.world = world;
                session.lastSeen = System.currentTimeMillis();
            }else {
                Deque<Vector3d> deque = currentTrails.computeIfAbsent(uuid, key -> new ConcurrentLinkedDeque<>());

                if(!deque.isEmpty() && deque.peekFirst().distance(vector3d) > teleportDetectionThreshold)
                    deque.clear();

                if(!deque.isEmpty() && sameCoordinates(deque.peekFirst(), vector3d)){
                    session.world = world;
                    session.lastSeen = System.currentTimeMillis();
                    continue;
                }

                deque.addFirst(vector3d);
                session.points.addFirst(vector3d);
                session.world = world;
                session.lastSeen = System.currentTimeMillis();
                while (deque.size() > maxTrailLength && deque.size() > 0)
                    deque.removeLast();
                while (session.points.size() > maxTrailLength && session.points.size() > 0)
                    session.points.removeLast();
            }
        }
        if(System.currentTimeMillis() - lastUpdate >= 10000)
            Bukkit.getScheduler().runTaskAsynchronously(this, this::asyncTrailTask);
        if(persistTrails && System.currentTimeMillis() - lastSave >= 60000){
            lastSave = System.currentTimeMillis();
            saveTrailHistory();
        }
    }

    private void asyncTrailTask(){
        lastUpdate = System.currentTimeMillis();
        cleanObsoleteMarkers();
        if(currentTrails.isEmpty()) return;
        if(separateSessionTrails){
            for(TrailSession session : trailSessions.values()){
                MarkerSet markerSet = markerSets.get(session.world);
                if(markerSet != null && session.points.size() > 1) markerSet.put(markerKey(session), createMarker(session.player, session.points, sessionLabel(session)));
            }
            return;
        }
        for(Map.Entry<UUID, ConcurrentLinkedDeque<Vector3d>> entry : currentTrails.entrySet()){
            UUID world = playerWorlds.get(entry.getKey());
            if(world == null) continue;
            MarkerSet markerSet = markerSets.get(world);
            if(markerSet == null) continue;
            if(entry.getValue().size() > 1) {
                markerSet.put(entry.getKey().toString(), createMarker(entry.getKey(), entry.getValue(), null));
            }else{
                markerSet.remove(entry.getKey().toString());
            }
        }
    }

    private LineMarker createMarker(UUID player, Deque<Vector3d> points, String labelOverride){
        Color color = colorCache.get(player);
        if(color == null){
            color = defaultColor;
            if(color == null) color = new Color(0xffffff);
        }
        String label = labelOverride != null ? labelOverride : displayNamePreset.replace("%player%", nameCache.getOrDefault(player, "n/a"));
        Line line = new Line(points.toArray(Vector3d[]::new));
        return LineMarker.builder()
                .label(label)
                .detail(label)
                .line(line)
                .lineWidth(defaultWidth)
                .lineColor(color)
                .centerPosition()
                .maxDistance(maxDistance)
                .build();
    }


    private void cleanObsoleteMarkers(){
        for(Map.Entry<UUID, MarkerSet> entry : markerSets.entrySet()){
            MarkerSet markerSet = entry.getValue();
            markerSet.getMarkers().keySet().removeIf(key -> {
                UUID uuid = markerPlayerId(key);
                if(uuid == null) return true;
                if(separateSessionTrails){
                    TrailSession session = trailSessions.get(markerSessionId(key));
                    return session == null || !entry.getKey().equals(session.world);
                }
                if(!currentTrails.containsKey(uuid)) return true;
                if(!entry.getKey().equals(playerWorlds.get(uuid))) return true;
                return false;
            });
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if(separateSessionTrails) activeSessionIds.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        trailLastSeen.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        saveTrailHistory();
    }

    private TrailSession ensureActiveSession(UUID player, UUID world) {
        UUID sessionId = activeSessionIds.computeIfAbsent(player, ignored -> UUID.randomUUID());
        return trailSessions.computeIfAbsent(sessionId, id -> new TrailSession(id, player, world, System.currentTimeMillis(), System.currentTimeMillis(), List.of()));
    }

    private String markerKey(TrailSession session) {
        return session.player + ":" + session.id;
    }

    private UUID markerPlayerId(String key) {
        try{
            return UUID.fromString(key.contains(":") ? key.substring(0, key.indexOf(':')) : key);
        }catch(IllegalArgumentException e){
            return null;
        }
    }

    private UUID markerSessionId(String key) {
        if(!key.contains(":")) return null;
        try{
            return UUID.fromString(key.substring(key.indexOf(':') + 1));
        }catch(IllegalArgumentException e){
            return null;
        }
    }

    private String sessionLabel(TrailSession session) {
        String player = nameCache.getOrDefault(session.player, session.player.toString());
        return player + " " + SESSION_LABEL_FORMAT.format(Instant.ofEpochMilli(session.startedAt));
    }

    private boolean sameCoordinates(Vector3d first, Vector3d second) {
        return Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    private void pruneExpiredHistory() {
        if(historyRetentionDays <= 0) return;
        long cutoff = System.currentTimeMillis() - historyRetentionDays * 86_400_000L;
        currentTrails.keySet().removeIf(uuid -> trailLastSeen.getOrDefault(uuid, System.currentTimeMillis()) < cutoff);
        trailSessions.values().removeIf(session -> session.lastSeen < cutoff);
    }

    private void loadTrailHistory() {
        if(!persistTrails || !historyFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(historyFile);
        for(String key : config.getConfigurationSection("players") == null ? Set.<String>of() : config.getConfigurationSection("players").getKeys(false)){
            try{
                UUID uuid = UUID.fromString(key);
                UUID world = UUID.fromString(config.getString("players." + key + ".world"));
                trailLastSeen.put(uuid, config.getLong("players." + key + ".lastSeen"));
                playerWorlds.put(uuid, world);
                currentTrails.put(uuid, readPoints(config.getStringList("players." + key + ".points")));
            }catch(Exception e){
                getLogger().log(Level.WARNING, "Ignoring invalid persisted trail for " + key, e);
            }
        }
        for(String key : config.getConfigurationSection("sessions") == null ? Set.<String>of() : config.getConfigurationSection("sessions").getKeys(false)){
            try{
                UUID id = UUID.fromString(key);
                TrailSession session = new TrailSession(id,
                        UUID.fromString(config.getString("sessions." + key + ".player")),
                        UUID.fromString(config.getString("sessions." + key + ".world")),
                        config.getLong("sessions." + key + ".startedAt"),
                        config.getLong("sessions." + key + ".lastSeen"),
                        readPoints(config.getStringList("sessions." + key + ".points")));
                trailSessions.put(id, session);
            }catch(Exception e){
                getLogger().log(Level.WARNING, "Ignoring invalid persisted session " + key, e);
            }
        }
    }

    private ConcurrentLinkedDeque<Vector3d> readPoints(List<String> points) {
        ConcurrentLinkedDeque<Vector3d> deque = new ConcurrentLinkedDeque<>();
        for(String point : points){
            String[] parts = point.split(",");
            if(parts.length == 3) deque.add(Vector3d.from(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
        }
        return deque;
    }

    private List<String> writePoints(Deque<Vector3d> points) {
        return points.stream()
                .map(point -> formatCoordinate(point.getX()) + "," + formatCoordinate(point.getY()) + "," + formatCoordinate(point.getZ()))
                .toList();
    }

    private String formatCoordinate(double coordinate) {
        return BigDecimal.valueOf(coordinate)
                .setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private void saveTrailHistory() {
        if(!persistTrails || historyFile == null || currentTrails == null || trailSessions == null) return;
        pruneExpiredHistory();
        YamlConfiguration config = new YamlConfiguration();
        currentTrails.forEach((uuid, points) -> {
            config.set("players." + uuid + ".world", playerWorlds.get(uuid) == null ? null : playerWorlds.get(uuid).toString());
            config.set("players." + uuid + ".lastSeen", trailLastSeen.getOrDefault(uuid, System.currentTimeMillis()));
            config.set("players." + uuid + ".points", writePoints(points));
        });
        trailSessions.forEach((id, session) -> {
            config.set("sessions." + id + ".player", session.player.toString());
            config.set("sessions." + id + ".world", session.world.toString());
            config.set("sessions." + id + ".startedAt", session.startedAt);
            config.set("sessions." + id + ".lastSeen", session.lastSeen);
            config.set("sessions." + id + ".points", writePoints(session.points));
        });
        try{
            config.save(historyFile);
        }catch(IOException e){
            getLogger().log(Level.WARNING, "Unable to save trail history", e);
        }
    }

    @Override
    public void onDisable() {
        saveTrailHistory();
        if(samplingTask != null) samplingTask.cancel();
    }
}
