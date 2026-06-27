package de.mark225.bmtrails;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Line;
import de.bluecolored.bluemap.api.math.Shape;
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
import java.io.InputStream;
import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

        // Feature toggles for the two top-level overlays. Both reuse the same sampled point data.
        public static final ConfigValue<Boolean> ENABLE_TRAILS = new ConfigValue<>("enablePlayerTrails", true);
        public static final ConfigValue<Boolean> ENABLE_HEATMAPS = new ConfigValue<>("enablePlayerHeatmaps", true);

        // Heatmap overlay settings.
        public static final ConfigValue<String> HEATMAP_SET_NAME = new ConfigValue<>("heatmapMarkerSetName", "Player Heatmaps");
        public static final ConfigValue<Boolean> HEATMAP_SET_VISIBLE = new ConfigValue<>("heatmapVisibleDefault", false);
        public static final ConfigValue<Boolean> HEATMAP_SET_TOGGLEABLE = new ConfigValue<>("heatmapMarkerSetToggleable", true);
        public static final ConfigValue<Integer> HEATMAP_RADIUS = new ConfigValue<>("heatmapRadius", 8);
        public static final ConfigValue<Integer> HEATMAP_CELL_SIZE = new ConfigValue<>("heatmapCellSize", 4);
        public static final ConfigValue<Double> HEATMAP_OPACITY = new ConfigValue<>("heatmapOpacity", 0.4);
        public static final ConfigValue<String> HEATMAP_MIN_COLOR = new ConfigValue<>("heatmapMinColor", "00ff00");
        public static final ConfigValue<String> HEATMAP_MAX_COLOR = new ConfigValue<>("heatmapMaxColor", "ff0000");
        public static final ConfigValue<Integer> HEATMAP_MAX_DISTANCE = new ConfigValue<>("heatmapMaxDistance", 1000);
        public static final ConfigValue<Integer> HEATMAP_MERGE_HEIGHT_TOLERANCE = new ConfigValue<>("heatmapMergeHeightTolerance", 8);

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
    private ConcurrentMap<UUID, MarkerSet> heatmapMarkerSets;
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
    private boolean markerSetVisibleDefault;
    private boolean markerSetToggleable;
    private boolean markerToggleOptionsSupported = true;
    private boolean markerListedSupported = true;
    private boolean nestedMarkerSetsSupported = true;
    private boolean enableTrails;
    private boolean enableHeatmaps;
    private boolean heatmapVisibleDefault;
    private boolean heatmapToggleable;
    private int heatmapRadius;
    private int heatmapCellSize;
    private double heatmapOpacity;
    private int[] heatmapMinRgb;
    private int[] heatmapMaxRgb;
    private int heatmapMaxDistance;
    private int heatmapMergeHeightTolerance;
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
            if(!enableTrails && !enableHeatmaps){
                getLogger().log(Level.WARNING, "Both player trails and heatmaps are disabled in the config; BMTrails will not collect or display anything.");
                return;
            }
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
        saveDefaultConfig();
        migrateConfig();
        reloadConfig();
        ConfigValue.config = getConfig();
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
        markerSetVisibleDefault = ConfigValue.MARKER_SET_VISIBLE.getValue();
        markerSetToggleable = ConfigValue.MARKER_SET_TOGGLEABLE.getValue();
        enableTrails = ConfigValue.ENABLE_TRAILS.getValue();
        enableHeatmaps = ConfigValue.ENABLE_HEATMAPS.getValue();
        heatmapVisibleDefault = ConfigValue.HEATMAP_SET_VISIBLE.getValue();
        heatmapToggleable = ConfigValue.HEATMAP_SET_TOGGLEABLE.getValue();
        heatmapRadius = Math.max(1, ConfigValue.HEATMAP_RADIUS.getValue());
        heatmapCellSize = Math.max(1, ConfigValue.HEATMAP_CELL_SIZE.getValue());
        heatmapOpacity = Math.min(1.0, Math.max(0.0, ConfigValue.HEATMAP_OPACITY.getValue()));
        heatmapMinRgb = parseRgb(ConfigValue.HEATMAP_MIN_COLOR.getValue(), new int[]{0x00, 0xff, 0x00});
        heatmapMaxRgb = parseRgb(ConfigValue.HEATMAP_MAX_COLOR.getValue(), new int[]{0xff, 0x00, 0x00});
        heatmapMaxDistance = ConfigValue.HEATMAP_MAX_DISTANCE.getValue();
        heatmapMergeHeightTolerance = Math.max(0, ConfigValue.HEATMAP_MERGE_HEIGHT_TOLERANCE.getValue());
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

    /**
     * Adds any top-level settings (and their comment lines) that are present in the bundled default config but
     * missing from the user's config.yml, appending them to the end of the existing file. The user's file is otherwise
     * left exactly as-is - including any custom comments or reordering they made - so this only ever grows the file
     * with the defaults they are missing (e.g. after a plugin update introduces new options). Order is not preserved;
     * missing settings are simply appended.
     */
    private void migrateConfig(){
        File configFile = new File(getDataFolder(), "config.yml");
        if(!configFile.exists()) return; // Fresh installs already get the complete default from saveDefaultConfig().
        String defaultText;
        try(InputStream in = getResource("config.yml")){
            if(in == null) return;
            defaultText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }catch(IOException e){
            getLogger().log(Level.WARNING, "Unable to read bundled default config for migration", e);
            return;
        }
        String userText;
        try{
            userText = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        }catch(IOException e){
            getLogger().log(Level.WARNING, "Unable to read config.yml for migration", e);
            return;
        }

        Set<String> existingKeys = topLevelKeys(userText);
        List<ConfigSegment> segments = parseConfigSegments(defaultText);
        List<ConfigSegment> missing = segments.stream()
                .filter(segment -> !existingKeys.contains(segment.key()))
                .toList();
        if(missing.isEmpty()) return;

        List<String> lines = new ArrayList<>();
        for(ConfigSegment segment : missing){
            List<String> leading = segment.leading();
            // The very first segment carries the file header comment block; strip it (everything up to and including
            // the first blank line) so we don't duplicate the header at the bottom of the user's file.
            if(segment.first()){
                int firstBlank = leading.indexOf("");
                if(firstBlank >= 0) leading = leading.subList(firstBlank + 1, leading.size());
            }
            lines.addAll(leading);
            lines.addAll(segment.value());
        }
        // Trim leading/trailing blank lines from the appended block.
        while(!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        while(!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        if(lines.isEmpty()) return;

        StringBuilder sb = new StringBuilder(userText);
        if(!userText.endsWith("\n")) sb.append("\n");
        sb.append("\n# ===== The following settings were added automatically by BMTrails =====\n");
        sb.append(String.join("\n", lines)).append("\n");
        try{
            Files.writeString(configFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
            getLogger().log(Level.INFO, "Added " + missing.size() + " missing config setting(s) to config.yml: "
                    + missing.stream().map(ConfigSegment::key).collect(Collectors.joining(", ")));
        }catch(IOException e){
            getLogger().log(Level.WARNING, "Unable to write migrated config.yml", e);
        }
    }

    private record ConfigSegment(String key, List<String> leading, List<String> value, boolean first) {}

    private Set<String> topLevelKeys(String text){
        Set<String> keys = new HashSet<>();
        for(String line : text.split("\n", -1)){
            if(isTopLevelKey(line)) keys.add(line.substring(0, line.indexOf(':')));
        }
        return keys;
    }

    /**
     * Splits config text into one segment per top-level key. Each segment owns all comment/blank lines since the
     * previous key (so a setting's documentation travels with it) plus the key line and any indented value lines.
     */
    private List<ConfigSegment> parseConfigSegments(String text){
        String[] lines = text.split("\n", -1);
        List<ConfigSegment> segments = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        int i = 0;
        while(i < lines.length){
            String line = lines[i];
            if(isTopLevelKey(line)){
                List<String> value = new ArrayList<>();
                value.add(line);
                i++;
                while(i < lines.length && isValueContinuation(lines[i])){
                    value.add(lines[i]);
                    i++;
                }
                segments.add(new ConfigSegment(line.substring(0, line.indexOf(':')),
                        new ArrayList<>(pending), value, segments.isEmpty()));
                pending.clear();
            }else{
                pending.add(line);
                i++;
            }
        }
        return segments;
    }

    private boolean isTopLevelKey(String line){
        return line.matches("^[A-Za-z0-9_]+:.*");
    }

    private boolean isValueContinuation(String line){
        return line.startsWith(" ") || line.startsWith("\t");
    }

    private int[] parseRgb(String hex, int[] fallback){
        try{
            int rgb = Integer.parseInt(hex, 16);
            return new int[]{(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff};
        }catch(NumberFormatException e){
            getLogger().log(Level.WARNING, "Invalid hex color '" + hex + "', using fallback");
            return fallback;
        }
    }

    private void createMarkerSets(){
        List<String> excludedMaps = ConfigValue.EXCLUDED_MAPS.getValue();
        markerSets = new ConcurrentHashMap<>();
        heatmapMarkerSets = new ConcurrentHashMap<>();
        String name = ConfigValue.MARKER_SET_NAME.getValue();
        String heatmapName = ConfigValue.HEATMAP_SET_NAME.getValue();
        for(World world : Bukkit.getWorlds()){
            BlueMapWorld bmw = blueMapAPI.getWorld(world.getUID()).orElse(null);
            if(bmw == null) continue;
            List<BlueMapMap> maps = bmw.getMaps().stream()
                    .filter(map -> !excludedMaps.contains(map.getId()))
                    .toList();
            if(maps.isEmpty()) continue;
            if(enableTrails){
                MarkerSet markerSet = createMarkerSet(name, markerSetVisibleDefault, markerSetToggleable);
                markerSets.put(world.getUID(), markerSet);
                maps.forEach(map -> map.getMarkerSets().put("bmtrails_" + map.getId() + "_" + world.getName(), markerSet));
            }
            if(enableHeatmaps){
                MarkerSet heatmapSet = createMarkerSet(heatmapName, heatmapVisibleDefault, heatmapToggleable);
                heatmapMarkerSets.put(world.getUID(), heatmapSet);
                maps.forEach(map -> map.getMarkerSets().put("bmheatmap_" + map.getId() + "_" + world.getName(), heatmapSet));
            }
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
                .filter(player -> isTrackedWorld(player.getWorld().getUID()))
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
        if(enableTrails) buildTrails();
        if(enableHeatmaps) buildHeatmaps();
    }

    private void buildTrails(){
        cleanObsoleteMarkers();
        if(currentTrails.isEmpty()) return;
        if(separateSessionTrails){
            for(TrailSession session : trailSessions.values()){
                MarkerSet rootMarkerSet = markerSets.get(session.world);
                if(rootMarkerSet == null || session.points.size() <= 1) continue;
                MarkerSet sessionMarkerSet = markerSetForSession(rootMarkerSet, session);
                String label = sessionMarkerSet == rootMarkerSet ? sessionLabel(session) : sessionTimestampLabel(session);
                sessionMarkerSet.put(markerKey(session), createMarker(session.player, session.points, label));
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
        Color color = trailColor(player);
        String label = labelOverride != null ? labelOverride : displayNamePreset.replace("%player%", resolvePlayerName(player));
        Line line = new Line(points.toArray(Vector3d[]::new));
        var builder = LineMarker.builder()
                .label(label)
                .detail(label)
                .line(line)
                .lineWidth(defaultWidth)
                .lineColor(color)
                .centerPosition()
                .maxDistance(maxDistance);
        builder = applyMarkerToggleOptions(builder, true, false);
        return builder.build();
    }


    /**
     * Resolves the colour to draw a player's trail with. The live {@link #colorCache} is cleared every refresh and
     * only repopulated for online players, so for offline players (e.g. trails restored from persisted history) it
     * misses. We must still return a <b>fully-opaque</b> colour - the previous {@code new Color(0xffffff)} fallback
     * had an alpha of 0 (the high byte is the alpha channel), which made offline trails completely transparent and
     * therefore invisible on the map even though the marker was still present/listed.
     */
    private Color trailColor(UUID player){
        Color cached = colorCache.get(player);
        if(cached != null) return cached;
        // Player is offline: colorCache misses, so re-check the static per-player overrides ourselves
        // (permission-based overrides can't be resolved without an online player and are skipped).
        Color override = playerOverrideColor(player);
        if(override != null) return override;
        if(defaultColor != null) return defaultColor;
        // Deterministic, opaque per-player colour - mirrors the random fallback resolveColor() uses while online.
        return new Color(0xff000000 | new Random(resolvePlayerName(player).hashCode()).nextInt());
    }

    private Color playerOverrideColor(UUID player){
        if(playerColorOverrides == null || playerColorOverrides.isEmpty()) return null;
        Color byUuid = playerColorOverrides.get(player.toString().toLowerCase(Locale.ROOT));
        if(byUuid != null) return byUuid;
        String name = resolvePlayerName(player);
        if(name != null){
            Color byName = playerColorOverrides.get(name.toLowerCase(Locale.ROOT));
            if(byName != null) return byName;
        }
        return null;
    }

    private void cleanObsoleteMarkers(){
        for(Map.Entry<UUID, MarkerSet> entry : markerSets.entrySet()){
            cleanObsoleteMarkers(entry.getValue(), entry.getKey());
        }
    }

    private void cleanObsoleteMarkers(MarkerSet markerSet, UUID world) {
        markerSet.getMarkers().keySet().removeIf(key -> shouldRemoveMarker(key, world));
        Map<String, MarkerSet> childMarkerSets = childMarkerSets(markerSet);
        if(childMarkerSets == null) return;
        childMarkerSets.entrySet().removeIf(entry -> {
            cleanObsoleteMarkers(entry.getValue(), world);
            Map<String, MarkerSet> grandchildren = childMarkerSets(entry.getValue());
            return entry.getValue().getMarkers().isEmpty() && (grandchildren == null || grandchildren.isEmpty());
        });
    }

    private boolean shouldRemoveMarker(String key, UUID world) {
        UUID uuid = markerPlayerId(key);
        if(uuid == null) return true;
        if(separateSessionTrails){
            TrailSession session = trailSessions.get(markerSessionId(key));
            return session == null || !world.equals(session.world);
        }
        if(!currentTrails.containsKey(uuid)) return true;
        return !world.equals(playerWorlds.get(uuid));
    }

    private boolean isTrackedWorld(UUID world){
        return (markerSets != null && markerSets.containsKey(world))
                || (heatmapMarkerSets != null && heatmapMarkerSets.containsKey(world));
    }

    // ----- Heatmaps -------------------------------------------------------------------------------------------------

    private void buildHeatmaps(){
        cleanObsoleteHeatmaps();
        if(currentTrails.isEmpty()) return;
        if(separateSessionTrails){
            for(TrailSession session : trailSessions.values()){
                MarkerSet root = heatmapMarkerSets.get(session.world);
                if(root == null || session.points.size() <= 1) continue;
                renderHeatmapOverlay(root, session.player, session.id, session.points);
            }
            return;
        }
        for(Map.Entry<UUID, ConcurrentLinkedDeque<Vector3d>> entry : currentTrails.entrySet()){
            UUID world = playerWorlds.get(entry.getKey());
            if(world == null) continue;
            MarkerSet root = heatmapMarkerSets.get(world);
            if(root == null) continue;
            if(entry.getValue().size() <= 1) continue;
            renderHeatmapOverlay(root, entry.getKey(), null, entry.getValue());
        }
    }

    /**
     * Renders a single player's (or session's) heatmap as a grid of semi-transparent {@link ShapeMarker} squares,
     * nested as Player Heatmaps -> player -> (session) just like the trail overlays. Each rebuild fully replaces the
     * previous cells for that overlay. When the running BlueMap build does not support nested marker sets, the cells
     * are flattened into the world root set with a player/session-qualified key prefix instead.
     */
    private void renderHeatmapOverlay(MarkerSet root, UUID player, UUID sessionId, Deque<Vector3d> points){
        MarkerSet leaf;
        String prefix;
        MarkerSet playerSet = heatmapPlayerSet(root, player);
        if(playerSet == null){
            // Nested marker sets unsupported: flatten everything into the world root set.
            leaf = root;
            prefix = player + (sessionId != null ? ":" + sessionId : "") + ":";
        }else if(sessionId != null){
            leaf = heatmapSessionSet(playerSet, sessionId);
            // heatmapSessionSet falls back to the player set if it can't nest a third level.
            prefix = leaf == playerSet ? sessionId + ":" : "";
        }else{
            leaf = playerSet;
            prefix = "";
        }
        String label = sessionId != null ? heatmapSessionLabel(player, sessionId) : resolvePlayerName(player);
        Map<String, ShapeMarker> cells = buildHeatmapCells(points, label, prefix);
        Map<String, Marker> markers = leaf.getMarkers();
        if(prefix.isEmpty()){
            markers.clear();
        }else{
            markers.keySet().removeIf(key -> key.startsWith(prefix));
        }
        markers.putAll(cells);
    }

    private Map<String, ShapeMarker> buildHeatmapCells(Deque<Vector3d> points, String label, String prefix){
        // Count how many sampled points fall within heatmapRadius of each grid cell. Overlapping coverage from nearby
        // points raises a cell's hit count, so areas the player lingered in accumulate the highest counts.
        Map<Long, int[]> counts = new HashMap<>();    // cell -> [hit count]
        Map<Long, double[]> heights = new HashMap<>(); // cell -> [sum of contributing point Y]
        for(Vector3d point : points){
            double px = point.getX();
            double pz = point.getZ();
            double py = point.getY();
            long minCx = cellIndex(px - heatmapRadius);
            long maxCx = cellIndex(px + heatmapRadius);
            long minCz = cellIndex(pz - heatmapRadius);
            long maxCz = cellIndex(pz + heatmapRadius);
            for(long cx = minCx; cx <= maxCx; cx++){
                double centerX = cx * (double) heatmapCellSize + heatmapCellSize / 2.0;
                double dx = centerX - px;
                for(long cz = minCz; cz <= maxCz; cz++){
                    double centerZ = cz * (double) heatmapCellSize + heatmapCellSize / 2.0;
                    double dz = centerZ - pz;
                    if(Math.sqrt(dx * dx + dz * dz) > heatmapRadius) continue;
                    long key = packCell(cx, cz);
                    counts.computeIfAbsent(key, k -> new int[1])[0]++;
                    heights.computeIfAbsent(key, k -> new double[1])[0] += py;
                }
            }
        }
        Map<String, ShapeMarker> cells = new HashMap<>();
        if(counts.isEmpty()) return cells;

        // Colour by the RANK of each cell's hit count among the distinct counts, not the raw magnitude, so a single
        // heavily-camped cell doesn't push everything else to the bottom (green). Cells that share a count share a
        // colour, and the distinct counts are spread evenly from heatmapMinColor (lowest) to heatmapMaxColor (highest).
        List<Integer> distinctCounts = counts.values().stream().map(c -> c[0]).distinct().sorted().toList();
        Map<Integer, Integer> rankByCount = new HashMap<>();
        for(int i = 0; i < distinctCounts.size(); i++) rankByCount.put(distinctCounts.get(i), i);
        int levels = distinctCounts.size();
        int alpha = (int) Math.round(heatmapOpacity * 255.0);

        // Pre-compute each occupied cell's colour level (dense rank) and average height.
        Map<Long, Double> heightByCell = new HashMap<>();
        Map<Integer, List<Long>> cellsByLevel = new HashMap<>();
        for(Map.Entry<Long, int[]> entry : counts.entrySet()){
            long key = entry.getKey();
            int count = entry.getValue()[0];
            heightByCell.put(key, heights.get(key)[0] / count);
            cellsByLevel.computeIfAbsent(rankByCount.get(count), k -> new ArrayList<>()).add(key);
        }

        // Merge contiguous same-colour cells into maximal rectangles to cut the marker count (BlueMap renders one
        // polygon per marker, so thousands of tiny cells lag the web client). Cells only merge while their height
        // stays within heatmapMergeHeightTolerance of the rectangle's seed cell, so a tall structure isn't flattened
        // onto the ground; the merged rectangle is drawn at its cells' average height.
        for(Map.Entry<Integer, List<Long>> levelEntry : cellsByLevel.entrySet()){
            double t = levels <= 1 ? 1.0 : (double) levelEntry.getKey() / (levels - 1);
            Set<Long> remaining = new HashSet<>(levelEntry.getValue());
            List<Long> ordered = levelEntry.getValue().stream()
                    .sorted(Comparator.<Long>comparingLong(l -> unpackZ(l)).thenComparingLong(l -> unpackX(l)))
                    .toList();
            for(long seed : ordered){
                if(!remaining.contains(seed)) continue;
                long cx0 = unpackX(seed), cz0 = unpackZ(seed);
                double seedHeight = heightByCell.get(seed);
                // Grow east as far as same-colour, height-compatible cells allow, then grow south by whole rows.
                long cx1 = cx0;
                while(canMergeCell(remaining, heightByCell, packCell(cx1 + 1, cz0), seedHeight)) cx1++;
                long cz1 = cz0;
                while(rowMergeable(remaining, heightByCell, cx0, cx1, cz1 + 1, seedHeight)) cz1++;
                double heightSum = 0;
                int n = 0;
                for(long cxx = cx0; cxx <= cx1; cxx++){
                    for(long czz = cz0; czz <= cz1; czz++){
                        long k = packCell(cxx, czz);
                        remaining.remove(k);
                        heightSum += heightByCell.get(k);
                        n++;
                    }
                }
                double y = heightSum / n;
                double rx1 = cx0 * (double) heatmapCellSize;
                double rz1 = cz0 * (double) heatmapCellSize;
                double rx2 = (cx1 + 1) * (double) heatmapCellSize;
                double rz2 = (cz1 + 1) * (double) heatmapCellSize;
                cells.put(prefix + cx0 + "_" + cz0 + "_" + cx1 + "_" + cz1,
                        heatmapRect(rx1, rz1, rx2, rz2, y, t, alpha, label));
            }
        }
        return cells;
    }

    private boolean canMergeCell(Set<Long> remaining, Map<Long, Double> heightByCell, long key, double seedHeight){
        Double height = heightByCell.get(key);
        return remaining.contains(key) && height != null && Math.abs(height - seedHeight) <= heatmapMergeHeightTolerance;
    }

    private boolean rowMergeable(Set<Long> remaining, Map<Long, Double> heightByCell, long cx0, long cx1, long cz, double seedHeight){
        for(long cxx = cx0; cxx <= cx1; cxx++){
            if(!canMergeCell(remaining, heightByCell, packCell(cxx, cz), seedHeight)) return false;
        }
        return true;
    }

    private ShapeMarker heatmapRect(double x1, double z1, double x2, double z2, double y, double t, int alpha, String label){
        Shape shape = Shape.createRect(x1, z1, x2, z2);
        var builder = ShapeMarker.builder()
                .label(label)
                .detail(label)
                .shape(shape, (float) y)
                .position((x1 + x2) / 2.0, y, (z1 + z2) / 2.0)
                .fillColor(heatColor(t, alpha))
                .lineColor(new Color(0x00000000))
                .lineWidth(1)
                .depthTestEnabled(false)
                .maxDistance(heatmapMaxDistance);
        // Merged cells make up one combined heatmap: keep them out of the marker list and not separately toggleable
        // so the per-session marker set is the single toggle the user sees and interacts with.
        builder = applyMarkerUnlisted(builder);
        builder = applyMarkerToggleOptions(builder, false, false);
        return builder.build();
    }

    private long cellIndex(double coordinate){
        return (long) Math.floor(coordinate / heatmapCellSize);
    }

    private long packCell(long cx, long cz){
        return (cx << 32) ^ (cz & 0xffffffffL);
    }

    private long unpackX(long packed){
        return packed >> 32;
    }

    private long unpackZ(long packed){
        return (int) (packed & 0xffffffffL);
    }

    private Color heatColor(double t, int alpha){
        t = Math.min(1.0, Math.max(0.0, t));
        int r = (int) Math.round(heatmapMinRgb[0] + (heatmapMaxRgb[0] - heatmapMinRgb[0]) * t);
        int g = (int) Math.round(heatmapMinRgb[1] + (heatmapMaxRgb[1] - heatmapMinRgb[1]) * t);
        int b = (int) Math.round(heatmapMinRgb[2] + (heatmapMaxRgb[2] - heatmapMinRgb[2]) * t);
        return new Color((alpha << 24) | (r << 16) | (g << 8) | b);
    }

    private MarkerSet heatmapPlayerSet(MarkerSet root, UUID player){
        Map<String, MarkerSet> children = childMarkerSets(root);
        if(children == null) return null;
        String label = resolvePlayerName(player);
        MarkerSet set = children.computeIfAbsent("player_" + player, key ->
                createMarkerSet(label, heatmapVisibleDefault, heatmapToggleable));
        if(!label.equals(player.toString()) && !label.equals(set.getLabel()))
            set.setLabel(label);
        return set;
    }

    private MarkerSet heatmapSessionSet(MarkerSet playerSet, UUID sessionId){
        Map<String, MarkerSet> children = childMarkerSets(playerSet);
        if(children == null) return playerSet;
        TrailSession session = trailSessions.get(sessionId);
        String label = session != null ? sessionTimestampLabel(session) : sessionId.toString();
        MarkerSet set = children.computeIfAbsent("session_" + sessionId, key ->
                createMarkerSet(label, heatmapVisibleDefault, heatmapToggleable));
        if(session != null && !label.equals(set.getLabel()))
            set.setLabel(label);
        return set;
    }

    private String heatmapSessionLabel(UUID player, UUID sessionId){
        TrailSession session = trailSessions.get(sessionId);
        return session != null ? sessionLabel(session) : resolvePlayerName(player);
    }

    private void cleanObsoleteHeatmaps(){
        for(Map.Entry<UUID, MarkerSet> entry : heatmapMarkerSets.entrySet()){
            cleanObsoleteHeatmaps(entry.getValue(), entry.getKey());
        }
    }

    private void cleanObsoleteHeatmaps(MarkerSet root, UUID world){
        Map<String, MarkerSet> players = childMarkerSets(root);
        if(players == null){
            // Flattened fallback: cell keys are "<player>[:<session>]:<cx>_<cz>".
            root.getMarkers().keySet().removeIf(key -> shouldRemoveMarker(key, world));
            return;
        }
        players.entrySet().removeIf(playerEntry -> {
            UUID player = parseSetId(playerEntry.getKey(), "player_");
            if(player == null) return true;
            MarkerSet playerSet = playerEntry.getValue();
            if(separateSessionTrails){
                Map<String, MarkerSet> sessions = childMarkerSets(playerSet);
                if(sessions != null){
                    sessions.entrySet().removeIf(sessionEntry -> {
                        UUID sessionId = parseSetId(sessionEntry.getKey(), "session_");
                        TrailSession session = sessionId == null ? null : trailSessions.get(sessionId);
                        return session == null || !world.equals(session.world);
                    });
                    return sessions.isEmpty() && playerSet.getMarkers().isEmpty();
                }
                return playerSet.getMarkers().isEmpty();
            }
            return !currentTrails.containsKey(player) || !world.equals(playerWorlds.get(player));
        });
    }

    private UUID parseSetId(String key, String expectedPrefix){
        if(!key.startsWith(expectedPrefix)) return null;
        try{
            return UUID.fromString(key.substring(expectedPrefix.length()));
        }catch(IllegalArgumentException e){
            return null;
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
        return resolvePlayerName(session.player) + " " + sessionTimestampLabel(session);
    }

    private String sessionTimestampLabel(TrailSession session) {
        return SESSION_LABEL_FORMAT.format(Instant.ofEpochMilli(session.startedAt));
    }

    /**
     * Resolves a display name for the given player. The live {@link #nameCache} only contains currently-online
     * players, so for offline players (e.g. ones restored from persisted history) we fall back to the server's
     * cached offline-player name, and only use the raw UUID as a last resort.
     */
    private String resolvePlayerName(UUID player) {
        String cached = nameCache == null ? null : nameCache.get(player);
        if(cached != null) return cached;
        try{
            String offline = Bukkit.getOfflinePlayer(player).getName();
            if(offline != null && !offline.isBlank()) return offline;
        }catch(Exception ignored){
            // fall through to the UUID
        }
        return player.toString();
    }

    private MarkerSet markerSetForSession(MarkerSet rootMarkerSet, TrailSession session) {
        Map<String, MarkerSet> childMarkerSets = childMarkerSets(rootMarkerSet);
        if(childMarkerSets == null) return rootMarkerSet;
        String label = resolvePlayerName(session.player);
        MarkerSet sessionMarkerSet = childMarkerSets.computeIfAbsent("player_" + session.player, key ->
                createMarkerSet(label, markerSetVisibleDefault, markerSetToggleable));
        // The set is only created once, but the name may not have been resolvable at creation time
        // (e.g. created from persisted history while the player was offline) - refresh it once we know better.
        if(!label.equals(session.player.toString()) && !label.equals(sessionMarkerSet.getLabel()))
            sessionMarkerSet.setLabel(label);
        return sessionMarkerSet;
    }

    private MarkerSet createMarkerSet(String label, boolean visible, boolean toggleable) {
        return MarkerSet.builder()
                .label(label)
                .defaultHidden(!visible)
                .toggleable(toggleable)
                .build();
    }

    private <T> T applyMarkerToggleOptions(T builder, boolean toggleable, boolean defaultHidden) {
        if(!markerToggleOptionsSupported) return builder;
        try{
            builder = invokeBuilderBoolean(builder, "toggleable", toggleable);
            return invokeBuilderBoolean(builder, "defaultHidden", defaultHidden);
        }catch(NoSuchMethodException e){
            markerToggleOptionsSupported = false;
        }catch(IllegalAccessException | InvocationTargetException e){
            getLogger().log(Level.WARNING, "Unable to apply marker-level toggle options", e);
        }
        return builder;
    }

    private <T> T invokeBuilderBoolean(T builder, String methodName, boolean value) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = builder.getClass().getMethod(methodName, boolean.class);
        return (T) method.invoke(builder, value);
    }

    /**
     * Marks a marker builder as not listed in BlueMap's marker menu, so heatmap cells don't clutter the layer list -
     * only the per-session marker set shows as a single toggle. Done reflectively because older BlueMap builds may not
     * expose the {@code listed} builder option.
     */
    private <T> T applyMarkerUnlisted(T builder) {
        if(!markerListedSupported) return builder;
        try{
            return invokeBuilderBoolean(builder, "listed", false);
        }catch(NoSuchMethodException e){
            markerListedSupported = false;
        }catch(IllegalAccessException | InvocationTargetException e){
            getLogger().log(Level.WARNING, "Unable to apply marker listed option", e);
        }
        return builder;
    }

    private Map<String, MarkerSet> childMarkerSets(MarkerSet markerSet) {
        if(!nestedMarkerSetsSupported) return null;
        try{
            Method method = markerSet.getClass().getMethod("getMarkerSets");
            return (Map<String, MarkerSet>) method.invoke(markerSet);
        }catch(NoSuchMethodException e){
            nestedMarkerSetsSupported = false;
        }catch(IllegalAccessException | InvocationTargetException | ClassCastException e){
            getLogger().log(Level.WARNING, "Unable to access nested BlueMap marker sets", e);
        }
        return null;
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
