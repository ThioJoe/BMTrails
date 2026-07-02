package de.mark225.bmtrails;

import com.flowpowered.math.vector.Vector2d;
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
        public static final ConfigValue<String> HEATMAP_RADIUS_SHAPE = new ConfigValue<>("heatmapRadiusShape", "circle");
        public static final ConfigValue<Integer> HEATMAP_CELL_SIZE = new ConfigValue<>("heatmapCellSize", 4);
        public static final ConfigValue<Double> HEATMAP_OPACITY = new ConfigValue<>("heatmapOpacity", 0.4);
        public static final ConfigValue<String> HEATMAP_MIN_COLOR = new ConfigValue<>("heatmapMinColor", "00ff00");
        public static final ConfigValue<String> HEATMAP_MAX_COLOR = new ConfigValue<>("heatmapMaxColor", "ff0000");
        public static final ConfigValue<Integer> HEATMAP_MAX_DISTANCE = new ConfigValue<>("heatmapMaxDistance", 1000);
        public static final ConfigValue<Integer> HEATMAP_MERGE_HEIGHT_TOLERANCE = new ConfigValue<>("heatmapMergeHeightTolerance", 8);
        public static final ConfigValue<Integer> HEATMAP_COLOR_LEVELS = new ConfigValue<>("heatmapColorLevels", 8);

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
    private static final long[][] HEATMAP_NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final String PERM_VISIBLE = "bmtrails.visible";
    private static final String PERM_COLOR_PREFIX = "bmtrails.color.";
    private static final String PERM_CUSTOM_COLOR = "bmtrails.customcolor";
    private static final DateTimeFormatter SESSION_LABEL_FORMAT = DateTimeFormatter.ofPattern("M/d/yy h:mma", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    // Separator between a trail's base marker key and its per-segment index (a teleport splits one trail into
    // several line segments). '#' never appears in a UUID, so it can't collide with the player/session UUID parts.
    private static final String SEGMENT_KEY_SEPARATOR = "#";

    // Keyed per (player, world) so each dimension a player visits keeps its own independent, persistent trail.
    private ConcurrentMap<TrailId, ConcurrentLinkedDeque<Vector3d>> currentTrails;
    private ConcurrentMap<TrailId, Long> trailLastSeen;
    private ConcurrentMap<UUID, UUID> activeSessionIds;
    private ConcurrentMap<UUID, TrailSession> trailSessions;
    private ConcurrentMap<UUID, Color> colorCache;
    private ConcurrentMap<UUID, MarkerSet> markerSets;
    private ConcurrentMap<UUID, MarkerSet> heatmapMarkerSets;
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
    private boolean markerSetSortingSupported = true;
    private boolean enableTrails;
    private boolean enableHeatmaps;
    private boolean heatmapVisibleDefault;
    private boolean heatmapToggleable;
    private int heatmapRadius;
    private boolean heatmapSquareRadius;
    private int heatmapCellSize;
    private double heatmapOpacity;
    private int[] heatmapMinRgb;
    private int[] heatmapMaxRgb;
    private int heatmapMaxDistance;
    private int heatmapMergeHeightTolerance;
    private int heatmapColorLevels;
    private File historyFile;


    /**
     * Identifies a single per-dimension trail: one player's movement within one specific world. Trails (and the
     * sessions that back them) are tracked per (player, world) so that moving between dimensions never disturbs the
     * trail left behind in the previous world - each dimension keeps its own trail and renders on its own map.
     */
    private static record TrailId(UUID player, UUID world) {}

    private static final class TrailSession {
        private final UUID id;
        private final UUID player;
        private final UUID world;
        private final long startedAt;
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
        heatmapSquareRadius = "square".equalsIgnoreCase(String.valueOf(ConfigValue.HEATMAP_RADIUS_SHAPE.getValue()).trim());
        heatmapCellSize = Math.max(1, ConfigValue.HEATMAP_CELL_SIZE.getValue());
        heatmapOpacity = Math.min(1.0, Math.max(0.0, ConfigValue.HEATMAP_OPACITY.getValue()));
        heatmapMinRgb = parseRgb(ConfigValue.HEATMAP_MIN_COLOR.getValue(), new int[]{0x00, 0xff, 0x00});
        heatmapMaxRgb = parseRgb(ConfigValue.HEATMAP_MAX_COLOR.getValue(), new int[]{0xff, 0x00, 0x00});
        heatmapMaxDistance = ConfigValue.HEATMAP_MAX_DISTANCE.getValue();
        heatmapMergeHeightTolerance = Math.max(0, ConfigValue.HEATMAP_MERGE_HEIGHT_TOLERANCE.getValue());
        heatmapColorLevels = Math.max(1, ConfigValue.HEATMAP_COLOR_LEVELS.getValue());
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
            // Resolve the BlueMap world for every Bukkit world - including the nether and the end, which are
            // separate Bukkit worlds. Try the world's UUID first (BlueMap's usual key) and fall back to handing it
            // the Bukkit World object directly, so a dimension is still picked up if the UUID lookup comes up empty.
            BlueMapWorld bmw = blueMapAPI.getWorld(world.getUID())
                    .or(() -> blueMapAPI.getWorld(world))
                    .orElse(null);
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
        // Without persistence we only keep each online player's trail for the dimension they are currently in;
        // trails for dimensions they have left (or players who have logged off) are dropped rather than retained.
        if(!persistTrails){
            currentTrails.keySet().removeIf(id -> {
                Location loc = currentLocations.get(id.player());
                return loc == null || !loc.getWorld().getUID().equals(id.world());
            });
            trailSessions.values().removeIf(session -> {
                Location loc = currentLocations.get(session.player);
                return loc == null || !loc.getWorld().getUID().equals(session.world);
            });
        }
        long now = System.currentTimeMillis();
        for(Map.Entry<UUID, Location> entry : currentLocations.entrySet()){
            UUID uuid = entry.getKey();
            Location loc = entry.getValue();
            Vector3d vector3d = Vector3d.from(loc.getX(), loc.getY(), loc.getZ());
            UUID world = loc.getWorld().getUID();
            TrailId trailId = new TrailId(uuid, world);
            trailLastSeen.put(trailId, now);

            // Trails and sessions are tracked per dimension. Moving to another world leaves the previous world's
            // trail/session untouched (it stays persisted and keeps rendering on that world's map); a fresh session
            // is started for the new world instead. Teleports within a world are kept as part of the same session
            // and only split into separate line segments at render time (see splitIntoSegments / putTrailSegments).
            TrailSession session = ensureActiveSession(uuid, world);
            Deque<Vector3d> deque = currentTrails.computeIfAbsent(trailId, key -> new ConcurrentLinkedDeque<>());

            if(!deque.isEmpty() && sameCoordinates(deque.peekFirst(), vector3d)){
                session.lastSeen = now;
                continue;
            }

            deque.addFirst(vector3d);
            session.points.addFirst(vector3d);
            session.lastSeen = now;
            while (deque.size() > maxTrailLength && deque.size() > 0)
                deque.removeLast();
            while (session.points.size() > maxTrailLength && session.points.size() > 0)
                session.points.removeLast();
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
        if(separateSessionTrails){
            for(TrailSession session : trailSessions.values()){
                MarkerSet root = markerSets.get(session.world);
                if(root == null || session.points.size() <= 1) continue;
                renderTrailOverlay(root, session.player, session.id, session.points);
            }
            return;
        }
        for(Map.Entry<TrailId, ConcurrentLinkedDeque<Vector3d>> entry : currentTrails.entrySet()){
            MarkerSet root = markerSets.get(entry.getKey().world());
            if(root == null) continue;
            if(entry.getValue().size() <= 1) continue;
            renderTrailOverlay(root, entry.getKey().player(), null, entry.getValue());
        }
    }

    /**
     * Renders a single player's (or session's) trail, nested as Player Trails -> player -> (session) just like the
     * heatmap overlays. A teleport splits the path into several discontinuous line segments (see
     * {@link #splitIntoSegments}); rather than listing each segment as its own toggle, all segments of one trail are
     * grouped into the leaf marker set and the individual segment markers are made unlisted / non-toggleable, so the
     * whole trail toggles as a single entry and the gaps are purely visual. When the running BlueMap build does not
     * support nested marker sets, the segments fall back into the world root set (keys stay player/session-qualified).
     */
    private void renderTrailOverlay(MarkerSet root, UUID player, UUID sessionId, Collection<Vector3d> points){
        MarkerSet playerSet = trailPlayerSet(root, player);
        MarkerSet leaf;
        if(playerSet == null){
            leaf = root; // nested marker sets unsupported: flatten into the world root set
        }else if(sessionId != null){
            leaf = trailSessionSet(playerSet, sessionId); // falls back to the player set if it can't nest a third level
        }else{
            leaf = playerSet;
        }
        // Keys stay fully qualified (player[:session]#index) regardless of which set they land in, so the recursive
        // cleanObsoleteMarkers / shouldRemoveMarker logic can still resolve the player & session from any segment key.
        String baseKey = sessionId != null ? player + ":" + sessionId : player.toString();
        putTrailSegments(leaf, baseKey, player, points);
    }

    /**
     * Splits a player's path into contiguous segments, breaking it wherever two consecutive points are farther
     * apart than {@link #teleportDetectionThreshold} (i.e. a teleport / large jump). All points are kept - this only
     * decides where the drawn line should have a visual gap - so the underlying session and its history stay intact.
     */
    private List<List<Vector3d>> splitIntoSegments(Collection<Vector3d> points){
        List<List<Vector3d>> segments = new ArrayList<>();
        List<Vector3d> current = new ArrayList<>();
        Vector3d previous = null;
        for(Vector3d point : points){
            if(previous != null && previous.distance(point) > teleportDetectionThreshold){
                segments.add(current);
                current = new ArrayList<>();
            }
            current.add(point);
            previous = point;
        }
        if(!current.isEmpty()) segments.add(current);
        return segments;
    }

    /**
     * Renders one trail as a line marker per contiguous segment into {@code markerSet} (its leaf set). Each segment
     * marker is keyed {@code baseKey + SEGMENT_KEY_SEPARATOR + index} and is unlisted / non-toggleable so the segments
     * of a single trail behave as one grouped overlay rather than separate toggles. Any markers from a previous render
     * of this same trail are removed first so a shrinking segment count never leaves stale lines behind. Each rendered
     * segment is labelled "Segment 1", "Segment 2", ... (numbered contiguously over the segments actually drawn) rather
     * than inheriting the trail/session label, so hovering a teleport-split piece identifies which segment it is.
     */
    private void putTrailSegments(MarkerSet markerSet, String baseKey, UUID player, Collection<Vector3d> points){
        removeTrailSegments(markerSet, baseKey);
        List<List<Vector3d>> segments = splitIntoSegments(points);
        int index = 0;
        int segmentNumber = 0;
        for(List<Vector3d> segment : segments){
            if(segment.size() > 1)
                markerSet.put(baseKey + SEGMENT_KEY_SEPARATOR + index, createMarker(player, segment, "Segment " + (++segmentNumber)));
            index++;
        }
    }

    /** Removes every line marker belonging to a trail (its per-segment keys, plus any legacy single-marker key). */
    private void removeTrailSegments(MarkerSet markerSet, String baseKey){
        String segmentPrefix = baseKey + SEGMENT_KEY_SEPARATOR;
        markerSet.getMarkers().keySet().removeIf(key -> key.equals(baseKey) || key.startsWith(segmentPrefix));
    }

    private LineMarker createMarker(UUID player, Collection<Vector3d> points, String labelOverride){
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
        // The segments of one trail are grouped under their (session/player) marker set, which provides the single
        // toggle. The individual segment lines exist only to make the path discontinuous at teleports, so they are
        // unlisted and not separately toggleable.
        builder = applyMarkerToggleOptions(builder, false, false);
        builder = applyMarkerUnlisted(builder);
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
        return !currentTrails.containsKey(new TrailId(uuid, world));
    }

    private boolean isTrackedWorld(UUID world){
        return (markerSets != null && markerSets.containsKey(world))
                || (heatmapMarkerSets != null && heatmapMarkerSets.containsKey(world));
    }

    // ----- Heatmaps -------------------------------------------------------------------------------------------------

    private void buildHeatmaps(){
        cleanObsoleteHeatmaps();
        if(separateSessionTrails){
            for(TrailSession session : trailSessions.values()){
                MarkerSet root = heatmapMarkerSets.get(session.world);
                if(root == null || session.points.size() <= 1) continue;
                renderHeatmapOverlay(root, session.player, session.id, session.points);
            }
            return;
        }
        for(Map.Entry<TrailId, ConcurrentLinkedDeque<Vector3d>> entry : currentTrails.entrySet()){
            MarkerSet root = heatmapMarkerSets.get(entry.getKey().world());
            if(root == null) continue;
            if(entry.getValue().size() <= 1) continue;
            renderHeatmapOverlay(root, entry.getKey().player(), null, entry.getValue());
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
                    // "circle": Euclidean falloff (round footprint); "square": Chebyshev (axis-aligned box footprint,
                    // which makes regions rectilinear and merge into much simpler polygons).
                    if(heatmapSquareRadius ? (Math.abs(dx) > heatmapRadius || Math.abs(dz) > heatmapRadius)
                            : (Math.sqrt(dx * dx + dz * dz) > heatmapRadius)) continue;
                    long key = packCell(cx, cz);
                    counts.computeIfAbsent(key, k -> new int[1])[0]++;
                    heights.computeIfAbsent(key, k -> new double[1])[0] += py;
                }
            }
        }
        Map<String, ShapeMarker> cells = new HashMap<>();
        if(counts.isEmpty()) return cells;

        // Colour by the RANK of each cell's hit count among the distinct counts, not the raw magnitude, so a single
        // heavily-camped cell doesn't push everything else to the bottom (green).
        List<Integer> distinctCounts = counts.values().stream().map(c -> c[0]).distinct().sorted().toList();
        Map<Integer, Integer> rankByCount = new HashMap<>();
        for(int i = 0; i < distinctCounts.size(); i++) rankByCount.put(distinctCounts.get(i), i);
        int levels = distinctCounts.size();
        int alpha = (int) Math.round(heatmapOpacity * 255.0);

        // Quantise each cell's rank into a fixed number of colour bands (heatmapColorLevels) and snap its height to
        // the nearest whole block. Without colour bucketing the dense rank produces almost as many distinct colours
        // as there are cells, so visually-identical neighbours rarely share an EXACT colour and can't be merged.
        int bands = Math.max(1, heatmapColorLevels);
        Map<Long, Integer> snappedHeight = new HashMap<>();
        Map<Integer, Set<Long>> cellsByBand = new HashMap<>();
        for(Map.Entry<Long, int[]> entry : counts.entrySet()){
            long key = entry.getKey();
            int count = entry.getValue()[0];
            snappedHeight.put(key, (int) Math.round(heights.get(key)[0] / count));
            double rank = levels <= 1 ? 1.0 : (double) rankByCount.get(count) / (levels - 1);
            int band = bands <= 1 ? 0 : (int) Math.round(rank * (bands - 1));
            cellsByBand.computeIfAbsent(band, k -> new HashSet<>()).add(key);
        }

        // Within each colour band, flood-fill connected regions where adjacent cells differ in (snapped) height by no
        // more than heatmapMergeHeightTolerance, and draw every rectangle of a region at the region's single average
        // height. This keeps abutting rectangles perfectly coplanar (no sub-block height seams between them, which is
        // what made merged cells still look like separate strips) while a tall structure that jumps more than the
        // tolerance starts a new region instead of being flattened onto the ground.
        for(Map.Entry<Integer, Set<Long>> bandEntry : cellsByBand.entrySet()){
            double t = bands <= 1 ? 1.0 : (double) bandEntry.getKey() / (bands - 1);
            Set<Long> bandCells = bandEntry.getValue();
            Set<Long> visited = new HashSet<>();
            for(long start : bandCells){
                if(!visited.add(start)) continue;
                List<Long> region = new ArrayList<>();
                Deque<Long> stack = new ArrayDeque<>();
                stack.push(start);
                region.add(start);
                while(!stack.isEmpty()){
                    long cur = stack.pop();
                    int curHeight = snappedHeight.get(cur);
                    long cx = unpackX(cur), cz = unpackZ(cur);
                    for(long[] dir : HEATMAP_NEIGHBORS){
                        long nb = packCell(cx + dir[0], cz + dir[1]);
                        if(bandCells.contains(nb) && !visited.contains(nb)
                                && Math.abs(snappedHeight.get(nb) - curHeight) <= heatmapMergeHeightTolerance){
                            visited.add(nb);
                            stack.push(nb);
                            region.add(nb);
                        }
                    }
                }
                double regionY = region.stream().mapToInt(snappedHeight::get).average().orElse(0);
                // A connected region without holes can be drawn as ONE polygon marker (its outline), which is far
                // cheaper than slicing it into many rectangles. Regions with holes fall back to greedy meshing.
                List<long[]> outline = regionOutline(region);
                if(outline != null){
                    long anchor = region.get(0);
                    for(long c : region){
                        if(unpackZ(c) < unpackZ(anchor) || (unpackZ(c) == unpackZ(anchor) && unpackX(c) < unpackX(anchor))) anchor = c;
                    }
                    cells.put(prefix + "p" + unpackX(anchor) + "_" + unpackZ(anchor),
                            heatmapPolygon(outline, regionY, t, alpha, label));
                }else{
                    meshRegion(cells, prefix, label, region, regionY, t, alpha);
                }
            }
        }
        return cells;
    }

    /**
     * Greedy-meshes a connected region of cells into maximal rectangles (grow east, then south by whole rows) and adds
     * one {@link ShapeMarker} per rectangle, all at the same {@code regionY} so they tile seamlessly.
     */
    private void meshRegion(Map<String, ShapeMarker> cells, String prefix, String label, List<Long> region, double regionY, double t, int alpha){
        Set<Long> remaining = new HashSet<>(region);
        List<Long> ordered = region.stream()
                .sorted(Comparator.<Long>comparingLong(l -> unpackZ(l)).thenComparingLong(l -> unpackX(l)))
                .toList();
        for(long seed : ordered){
            if(!remaining.contains(seed)) continue;
            long cx0 = unpackX(seed), cz0 = unpackZ(seed);
            long cx1 = cx0;
            while(remaining.contains(packCell(cx1 + 1, cz0))) cx1++;
            long cz1 = cz0;
            while(rowPresent(remaining, cx0, cx1, cz1 + 1)) cz1++;
            for(long cxx = cx0; cxx <= cx1; cxx++)
                for(long czz = cz0; czz <= cz1; czz++)
                    remaining.remove(packCell(cxx, czz));
            double rx1 = cx0 * (double) heatmapCellSize;
            double rz1 = cz0 * (double) heatmapCellSize;
            double rx2 = (cx1 + 1) * (double) heatmapCellSize;
            double rz2 = (cz1 + 1) * (double) heatmapCellSize;
            cells.put(prefix + cx0 + "_" + cz0 + "_" + cx1 + "_" + cz1,
                    heatmapRect(rx1, rz1, rx2, rz2, regionY, t, alpha, label));
        }
    }

    private boolean rowPresent(Set<Long> remaining, long cx0, long cx1, long cz){
        for(long cxx = cx0; cxx <= cx1; cxx++){
            if(!remaining.contains(packCell(cxx, cz))) return false;
        }
        return true;
    }

    /**
     * Traces the outline of a connected region of cells as a single rectilinear polygon (list of {cornerX, cornerZ}
     * in cell-corner units, collinear points removed). Returns {@code null} if the region has a hole or its boundary
     * touches itself at a corner, in which case the caller falls back to rectangle meshing. Each boundary edge is
     * emitted with the interior on a consistent side, so a hole-free region yields exactly one closed loop.
     */
    private List<long[]> regionOutline(List<Long> region){
        Set<Long> cellSet = new HashSet<>(region);
        Map<Long, Long> next = new HashMap<>();
        for(long c : region){
            long cx = unpackX(c), cz = unpackZ(c);
            if(!cellSet.contains(packCell(cx, cz - 1)) && next.put(packCell(cx, cz), packCell(cx + 1, cz)) != null) return null;
            if(!cellSet.contains(packCell(cx + 1, cz)) && next.put(packCell(cx + 1, cz), packCell(cx + 1, cz + 1)) != null) return null;
            if(!cellSet.contains(packCell(cx, cz + 1)) && next.put(packCell(cx + 1, cz + 1), packCell(cx, cz + 1)) != null) return null;
            if(!cellSet.contains(packCell(cx - 1, cz)) && next.put(packCell(cx, cz + 1), packCell(cx, cz)) != null) return null;
        }
        if(next.isEmpty()) return null;
        List<Long> loop = new ArrayList<>();
        long start = next.keySet().iterator().next();
        long cur = start;
        do {
            loop.add(cur);
            Long nx = next.get(cur);
            if(nx == null) return null;
            cur = nx;
        } while(cur != start && loop.size() <= next.size());
        if(cur != start || loop.size() != next.size()) return null; // unclosed, or more than one loop => holes
        // Drop collinear corners so a plain rectangle stays 4 points.
        int n = loop.size();
        List<long[]> points = new ArrayList<>();
        for(int i = 0; i < n; i++){
            long prev = loop.get((i - 1 + n) % n), curr = loop.get(i), nxt = loop.get((i + 1) % n);
            boolean sameX = unpackX(prev) == unpackX(curr) && unpackX(curr) == unpackX(nxt);
            boolean sameZ = unpackZ(prev) == unpackZ(curr) && unpackZ(curr) == unpackZ(nxt);
            if(!sameX && !sameZ) points.add(new long[]{unpackX(curr), unpackZ(curr)});
        }
        return points;
    }

    private ShapeMarker heatmapPolygon(List<long[]> outline, double y, double t, int alpha, String label){
        Vector2d[] points = new Vector2d[outline.size()];
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for(int i = 0; i < outline.size(); i++){
            double wx = outline.get(i)[0] * (double) heatmapCellSize;
            double wz = outline.get(i)[1] * (double) heatmapCellSize;
            points[i] = new Vector2d(wx, wz);
            minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
            minZ = Math.min(minZ, wz); maxZ = Math.max(maxZ, wz);
        }
        var builder = ShapeMarker.builder()
                .label(label)
                .detail(label)
                .shape(new Shape(points), (float) y)
                .position((minX + maxX) / 2.0, y, (minZ + maxZ) / 2.0)
                .fillColor(heatColor(t, alpha))
                .lineColor(new Color(0x00000000))
                .lineWidth(1)
                .depthTestEnabled(false)
                .maxDistance(heatmapMaxDistance);
        builder = applyMarkerUnlisted(builder);
        builder = applyMarkerToggleOptions(builder, false, false);
        return builder.build();
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
        if(session != null) applyMarkerSetSorting(set, sessionSorting(session.player, sessionId));
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
            return !currentTrails.containsKey(new TrailId(player, world));
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
        trailLastSeen.put(new TrailId(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getUID()),
                System.currentTimeMillis());
        saveTrailHistory();
    }

    /**
     * Returns the player's active session for the world they are currently in, creating a new one when needed. A
     * session is bound to a single world for its whole life: as soon as the player moves to a different dimension we
     * start a fresh session for that dimension instead of reusing (and overwriting) the previous one, so each
     * dimension's path is preserved and rendered on its own map rather than being clobbered on every world change.
     */
    private TrailSession ensureActiveSession(UUID player, UUID world) {
        UUID activeId = activeSessionIds.get(player);
        TrailSession active = activeId == null ? null : trailSessions.get(activeId);
        if(active != null && world.equals(active.world)) return active;
        long now = System.currentTimeMillis();
        UUID sessionId = UUID.randomUUID();
        TrailSession session = new TrailSession(sessionId, player, world, now, now, List.of());
        trailSessions.put(sessionId, session);
        activeSessionIds.put(player, sessionId);
        return session;
    }

    private UUID markerPlayerId(String key) {
        key = stripSegmentSuffix(key);
        try{
            return UUID.fromString(key.contains(":") ? key.substring(0, key.indexOf(':')) : key);
        }catch(IllegalArgumentException e){
            return null;
        }
    }

    private UUID markerSessionId(String key) {
        key = stripSegmentSuffix(key);
        if(!key.contains(":")) return null;
        try{
            return UUID.fromString(key.substring(key.indexOf(':') + 1));
        }catch(IllegalArgumentException e){
            return null;
        }
    }

    /** Strips the trailing {@code SEGMENT_KEY_SEPARATOR + index} from a marker key so the UUID parts can be parsed. */
    private String stripSegmentSuffix(String key) {
        int separator = key.indexOf(SEGMENT_KEY_SEPARATOR);
        return separator < 0 ? key : key.substring(0, separator);
    }

    private String sessionLabel(TrailSession session) {
        return resolvePlayerName(session.player) + " " + sessionTimestampLabel(session);
    }

    private String sessionTimestampLabel(TrailSession session) {
        return SESSION_LABEL_FORMAT.format(Instant.ofEpochMilli(session.startedAt));
    }

    /**
     * Sorting value for a player's session marker set: the most recently started session sorts first (0), the next
     * most recent 1, and so on, so newer sessions appear at the top of the menu (BlueMap orders sets by ascending
     * sorting value). Ranked only among that same player's sessions, by counting how many of their sessions started
     * more recently than this one. Ties on start time fall back to a stable comparison of the session ids.
     */
    private int sessionSorting(UUID player, UUID sessionId) {
        TrailSession self = trailSessions.get(sessionId);
        if(self == null) return 0;
        int rank = 0;
        for(TrailSession other : trailSessions.values()){
            if(other.id.equals(sessionId) || !other.player.equals(player)) continue;
            if(other.startedAt > self.startedAt
                    || (other.startedAt == self.startedAt && other.id.compareTo(sessionId) > 0)) rank++;
        }
        return rank;
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

    /**
     * Player-level child set under the trail root ("Player Trails" -> player), mirroring {@link #heatmapPlayerSet}.
     * Returns null when the running BlueMap build does not support nested marker sets.
     */
    private MarkerSet trailPlayerSet(MarkerSet root, UUID player){
        Map<String, MarkerSet> children = childMarkerSets(root);
        if(children == null) return null;
        String label = resolvePlayerName(player);
        MarkerSet set = children.computeIfAbsent("player_" + player, key ->
                createMarkerSet(label, markerSetVisibleDefault, markerSetToggleable));
        // The set is only created once, but the name may not have been resolvable at creation time
        // (e.g. created from persisted history while the player was offline) - refresh it once we know better.
        if(!label.equals(player.toString()) && !label.equals(set.getLabel()))
            set.setLabel(label);
        return set;
    }

    /**
     * Session-level child set under a player set (player -> session), mirroring {@link #heatmapSessionSet}. Falls back
     * to the player set if a third nesting level isn't supported.
     */
    private MarkerSet trailSessionSet(MarkerSet playerSet, UUID sessionId){
        Map<String, MarkerSet> children = childMarkerSets(playerSet);
        if(children == null) return playerSet;
        TrailSession session = trailSessions.get(sessionId);
        String label = session != null ? sessionTimestampLabel(session) : sessionId.toString();
        MarkerSet set = children.computeIfAbsent("session_" + sessionId, key ->
                createMarkerSet(label, markerSetVisibleDefault, markerSetToggleable));
        if(session != null && !label.equals(set.getLabel()))
            set.setLabel(label);
        if(session != null) applyMarkerSetSorting(set, sessionSorting(session.player, sessionId));
        return set;
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

    /**
     * Sets a marker set's sorting value (lower values appear first in BlueMap's menu). Done reflectively because the
     * {@code sorting} property only exists on newer BlueMap builds than this plugin compiles against; on older builds
     * the method is absent and we simply skip it (the sets just keep their default order).
     */
    private void applyMarkerSetSorting(MarkerSet markerSet, int sorting) {
        if(!markerSetSortingSupported) return;
        try{
            Method method = markerSet.getClass().getMethod("setSorting", int.class);
            method.invoke(markerSet, sorting);
        }catch(NoSuchMethodException e){
            markerSetSortingSupported = false;
        }catch(IllegalAccessException | InvocationTargetException e){
            getLogger().log(Level.WARNING, "Unable to apply marker-set sorting", e);
        }
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
        currentTrails.keySet().removeIf(id -> trailLastSeen.getOrDefault(id, System.currentTimeMillis()) < cutoff);
        trailLastSeen.keySet().removeIf(id -> !currentTrails.containsKey(id));
        trailSessions.values().removeIf(session -> session.lastSeen < cutoff);
    }

    private void loadTrailHistory() {
        if(!persistTrails || !historyFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(historyFile);
        for(String key : config.getConfigurationSection("players") == null ? Set.<String>of() : config.getConfigurationSection("players").getKeys(false)){
            try{
                UUID uuid = UUID.fromString(key);
                // Current-window trails are stored per world (players.<uuid>.worlds.<worldUuid>). Files written by
                // older plugin versions used a single-world layout (players.<uuid>.world/points) instead; read both
                // so existing history keeps displaying after an update.
                var worlds = config.getConfigurationSection("players." + key + ".worlds");
                if(worlds != null){
                    for(String worldKey : worlds.getKeys(false)){
                        UUID world = UUID.fromString(worldKey);
                        TrailId id = new TrailId(uuid, world);
                        String base = "players." + key + ".worlds." + worldKey;
                        trailLastSeen.put(id, config.getLong(base + ".lastSeen"));
                        currentTrails.put(id, readPoints(config.getStringList(base + ".points")));
                    }
                }else if(config.getString("players." + key + ".world") != null){
                    // Legacy format: the old plugin reset trails on world change, so all points belong to that world.
                    TrailId id = new TrailId(uuid, UUID.fromString(config.getString("players." + key + ".world")));
                    trailLastSeen.put(id, config.getLong("players." + key + ".lastSeen"));
                    currentTrails.put(id, readPoints(config.getStringList("players." + key + ".points")));
                }
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
        // Rebuild any missing rolling-window trail from that player's newest persisted session in the same world.
        // The sessions carry the same point data, so this recovers the (non-session-mode) trail display for history
        // files whose players section is missing entries or was written by a different plugin version.
        Map<TrailId, TrailSession> newestSessions = new HashMap<>();
        for(TrailSession session : trailSessions.values()){
            TrailId id = new TrailId(session.player, session.world);
            TrailSession newest = newestSessions.get(id);
            if(newest == null || session.lastSeen > newest.lastSeen) newestSessions.put(id, session);
        }
        newestSessions.forEach((id, session) -> {
            if(session.points.isEmpty() || currentTrails.containsKey(id)) return;
            ConcurrentLinkedDeque<Vector3d> points = new ConcurrentLinkedDeque<>(session.points);
            while(points.size() > maxTrailLength) points.removeLast();
            currentTrails.put(id, points);
            trailLastSeen.putIfAbsent(id, session.lastSeen);
        });
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
        currentTrails.forEach((id, points) -> {
            String base = "players." + id.player() + ".worlds." + id.world();
            config.set(base + ".lastSeen", trailLastSeen.getOrDefault(id, System.currentTimeMillis()));
            config.set(base + ".points", writePoints(points));
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
