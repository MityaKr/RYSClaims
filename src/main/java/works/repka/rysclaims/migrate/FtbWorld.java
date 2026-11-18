package works.repka.rysclaims.migrate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.migrate.Snbt;

/**
 * Файлы FTB Teams и FTB Chunks мира, прочитанные без игры через {@link Snbt} ядра. Код FTB не используется: читаются только
 * данные (план, раздел 10.3). Файл, который не прочитался, не мешает остальным: он попадает в {@link #errors}.
 */
public final class FtbWorld {
    /** Ранги FTB Teams, которые делают игрока участником команды. */
    public static final List<String> MEMBER_RANKS = List.of("owner", "officer", "member");

    /** Команда FTB Teams: {@code ftbteams/<тип>/<UUID>.snbt}. */
    public static final class Team {
        public final UUID id;
        /** {@code party}, {@code player}, {@code server}. */
        public final String type;
        public final String name;
        public final @Nullable UUID owner;
        /** Игрок → ранг ({@code owner}, {@code officer}, {@code member}, {@code ally}, {@code invited}…). */
        public final Map<UUID, String> ranks;
        public final JsonObject properties;

        Team(UUID id, String type, String name, @Nullable UUID owner, Map<UUID, String> ranks, JsonObject properties) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.owner = owner;
            this.ranks = ranks;
            this.properties = properties;
        }

        /** Участники: у личной команды сам игрок, у партии игроки с рангом участника. */
        public List<UUID> members() {
            if ("player".equals(type)) return List.of(id);
            List<UUID> out = new ArrayList<>();
            ranks.forEach((p, r) -> {
                if (MEMBER_RANKS.contains(r)) out.add(p);
            });
            return out;
        }

        /** Игроки с рангом {@code ally}: в FTB союзник это игрок, а не команда. */
        public List<UUID> allies() {
            List<UUID> out = new ArrayList<>();
            ranks.forEach((p, r) -> {
                if ("ally".equals(r)) out.add(p);
            });
            return out;
        }

        public String property(String key, String fallback) {
            return Snbt.string(properties, key, fallback);
        }

        public boolean flag(String key, boolean fallback) {
            return Snbt.bool(properties, key, fallback);
        }
    }

    /** Чанк FTB Chunks. {@code forceLoaded} это время включения прогрузки, 0: выключена. */
    public record Chunk(int x, int z, long time, long forceLoaded, long expiry) {}

    /** Земля команды или личный файл игрока: {@code ftbchunks/<UUID>.snbt}. */
    public static final class Land {
        public final UUID id;
        public final int maxClaims, maxForce;
        public final int extraClaims, extraForce;
        public final long lastLogin;
        public final Map<String, List<Chunk>> chunks;
        public final Map<UUID, JsonObject> memberData;
        public final int preventedAccess;

        Land(UUID id, int maxClaims, int maxForce, int extraClaims, int extraForce, long lastLogin,
             Map<String, List<Chunk>> chunks, Map<UUID, JsonObject> memberData, int preventedAccess) {
            this.id = id;
            this.maxClaims = maxClaims;
            this.maxForce = maxForce;
            this.extraClaims = extraClaims;
            this.extraForce = extraForce;
            this.lastLogin = lastLogin;
            this.chunks = chunks;
            this.memberData = memberData;
            this.preventedAccess = preventedAccess;
        }

        public int chunkCount() {
            int n = 0;
            for (List<Chunk> l : chunks.values()) n += l.size();
            return n;
        }

        public int forcedCount() {
            int n = 0;
            for (List<Chunk> l : chunks.values()) for (Chunk c : l) if (c.forceLoaded() != 0) n++;
            return n;
        }
    }

    public final Map<UUID, Team> teams = new LinkedHashMap<>();
    public final Map<UUID, Land> lands = new LinkedHashMap<>();
    /** Настройки сервера FTB Chunks или пустой объект. */
    public JsonObject config = new JsonObject();
    public @Nullable Path configFile;
    /** {@code id} из {@code ftbteams/ftbteams.snbt}: имя папки карты у клиента. */
    public @Nullable String serverId;
    /** Файлы, которые не прочитались: путь и причина. */
    public final List<String> errors = new ArrayList<>();

    /**
     * Читает мир. {@code configs} это кандидаты файла настроек по старшинству: обычно
     * {@code world/serverconfig/ftbchunks-world.snbt}, затем {@code config/ftbchunks-world.snbt}; берётся первый найденный.
     */
    public static FtbWorld read(Path worldDir, List<Path> configs) throws IOException {
        FtbWorld w = new FtbWorld();
        for (Path cfg : configs) {
            if (!Files.isRegularFile(cfg)) continue;
            try {
                w.config = Snbt.readObject(cfg);
                w.configFile = cfg;
            } catch (IOException | RuntimeException e) {
                w.errors.add(cfg + ": " + e.getMessage());
            }
            break;
        }
        Path teams = worldDir.resolve("ftbteams");
        Path main = teams.resolve("ftbteams.snbt");
        if (Files.isRegularFile(main)) {
            try {
                w.serverId = Snbt.string(Snbt.readObject(main), "id", null);
            } catch (IOException | RuntimeException e) {
                w.errors.add(main + ": " + e.getMessage());
            }
        }
        for (String type : List.of("party", "player", "server")) {
            for (Path f : files(teams.resolve(type))) {
                try {
                    Team t = readTeam(Snbt.readObject(f), type);
                    if (t != null) w.teams.put(t.id, t);
                } catch (IOException | RuntimeException e) {
                    w.errors.add(f + ": " + e.getMessage());
                }
            }
        }
        for (Path f : files(worldDir.resolve("ftbchunks"))) {
            String name = f.getFileName().toString();
            UUID id = uuid(name.substring(0, name.length() - ".snbt".length()));
            if (id == null) continue;
            try {
                w.lands.put(id, readLand(id, Snbt.readObject(f)));
            } catch (IOException | RuntimeException e) {
                w.errors.add(f + ": " + e.getMessage());
            }
        }
        return w;
    }

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".snbt")).sorted().toList();
        }
    }

    static @Nullable Team readTeam(JsonObject o, String fallbackType) {
        UUID id = uuid(Snbt.string(o, "id", ""));
        if (id == null) return null;
        String type = Snbt.string(o, "type", fallbackType);
        JsonObject props = Snbt.object(o, "properties");
        String name = Snbt.string(props, "ftbteams:display_name", Snbt.string(o, "player_name", id.toString().substring(0, 8)));
        Map<UUID, String> ranks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : Snbt.object(o, "ranks").entrySet()) {
            UUID p = uuid(e.getKey());
            if (p != null && e.getValue().isJsonPrimitive()) ranks.put(p, e.getValue().getAsString());
        }
        return new Team(id, type, name, uuid(Snbt.string(o, "owner", "")), ranks, props);
    }

    static Land readLand(UUID id, JsonObject o) {
        Map<String, List<Chunk>> chunks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : Snbt.object(o, "chunks").entrySet()) {
            if (!e.getValue().isJsonArray()) continue;
            List<Chunk> list = new ArrayList<>();
            for (JsonElement x : e.getValue().getAsJsonArray()) {
                if (!x.isJsonObject()) continue;
                JsonObject c = x.getAsJsonObject();
                list.add(new Chunk(Snbt.integer(c, "x", 0), Snbt.integer(c, "z", 0), Snbt.longValue(c, "time", 0),
                    Snbt.longValue(c, "force_loaded", 0), Snbt.longValue(c, "expiry_time", 0)));
            }
            chunks.put(e.getKey(), list);
        }
        Map<UUID, JsonObject> members = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : Snbt.object(o, "member_data").entrySet()) {
            UUID p = uuid(e.getKey());
            if (p != null && e.getValue().isJsonObject()) members.put(p, e.getValue().getAsJsonObject());
        }
        return new Land(id, Snbt.integer(o, "max_claim_chunks", 0), Snbt.integer(o, "max_force_load_chunks", 0),
            Snbt.integer(o, "extra_claim_chunks", 0), Snbt.integer(o, "extra_force_load_chunks", 0),
            Snbt.longValue(o, "last_login_time", 0), chunks, members, Snbt.array(o, "prevented_access").size());
    }

    static @Nullable UUID uuid(String s) {
        try {
            return s == null || s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
