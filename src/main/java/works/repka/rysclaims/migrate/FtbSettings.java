package works.repka.rysclaims.migrate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.migrate.Snbt;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;

/**
 * Настройки сервера FTB Chunks ({@code ftbchunks-world.snbt}) в наши (план, раздел 10.5). Списки «измерения без привата»
 * складываются с нашими по умолчанию: FTB их не знал, а пустой список FTB не значит «приват в подземельях».
 */
public final class FtbSettings {
    private FtbSettings() {}

    /** Переносит в {@code s} всё, что в конфиге FTB задано. Чего в конфиге нет, остаётся как было. */
    public static Settings apply(JsonObject cfg, Settings s) {
        JsonObject claiming = Snbt.object(cfg, "claiming");
        JsonObject loading = Snbt.object(cfg, "force_loading");
        s.claimsPerTeam = Snbt.integer(claiming, "max_claimed_chunks", s.claimsPerTeam);
        s.forcePerTeam = Snbt.integer(loading, "max_force_loaded_chunks", s.forcePerTeam);
        s.bonusMode = Settings.BonusMode.parse(Snbt.string(claiming, "party_limit_mode", s.bonusMode.key()), s.bonusMode);
        s.hardClaims = Snbt.integer(claiming, "hard_team_claim_limit", s.hardClaims);
        s.hardForce = Snbt.integer(loading, "hard_team_force_limit", s.hardForce);
        for (JsonElement e : Snbt.array(claiming, "claim_dimension_blacklist")) s.disabledDimensions.add(e.getAsString());
        for (JsonElement e : Snbt.array(claiming, "claim_dimension_whitelist")) s.onlyDimensions.add(e.getAsString());
        // FTB «always» прогружает и без игроков; «default» и «never» держат прогрузку, пока участник в сети
        s.maxLoad = "always".equals(Snbt.string(loading, "force_load_mode", "default")) ? Settings.LoadMode.ALWAYS : Settings.LoadMode.ONLINE;
        s.protection = !Snbt.bool(cfg, "disable_protection", !s.protection);
        s.allyMode = Settings.AllyMode.parse(Snbt.string(cfg, "ally_mode", "default"), s.allyMode);
        s.pvpMode = Settings.PvpMode.parse(Snbt.string(cfg, "pvp_mode", "always"), s.pvpMode);
        s.noWilderness = Snbt.bool(cfg, "no_wilderness", s.noWilderness);
        for (JsonElement e : Snbt.array(cfg, "no_wilderness_dimensions")) s.noWildernessDimensions.add(e.getAsString());
        // защита FTB «включена» значит у нас «через границу нельзя». Жидкости как в FTB (план 11: в RepYS они текут);
        // огонь через границу не переходит и после переноса (план 11, таблица настроек), fire_spread_protection не берём
        if (cfg.has("flowing_fluid_protection")) s.fluidsCross = !Snbt.bool(cfg, "flowing_fluid_protection", true);
        if (cfg.has("piston_protection")) s.pistonsCross = !Snbt.bool(cfg, "piston_protection", true);
        if (cfg.has("protect_unknown_explosions") && !Snbt.bool(cfg, "protect_unknown_explosions", true)) s.ownerless = Settings.Ownerless.ALWAYS;
        int days = Snbt.integer(Snbt.object(cfg, "fake_players"), "max_prevented_log_age", s.logDays);
        if (days > 0) s.logDays = days;
        JsonObject defs = Snbt.object(cfg, "team_prop_defaults");
        if (!defs.entrySet().isEmpty()) {
            Rights r = s.defaultRights.copy();
            setFrom(r, Rights.Action.BUILD, Snbt.string(defs, "def_block_edit", null));
            String interact = Snbt.string(defs, "def_block_interact", null);
            setFrom(r, Rights.Action.OPEN, interact);
            setFrom(r, Rights.Action.USE, interact);
            setFrom(r, Rights.Action.ENTITY, stricter(Snbt.string(defs, "def_entity_interact", null), Snbt.string(defs, "def_entity_attack", null)));
            s.defaultRights = r;
        }
        return s;
    }

    /** Режим FTB ({@code private}, {@code allies}, {@code public}) в набор прав или null. */
    public static @Nullable Rights.Preset preset(@Nullable String mode) {
        if (mode == null) return null;
        return switch (mode) {
            case "private" -> Rights.Preset.TEAM;
            case "allies" -> Rights.Preset.TEAM_AND_ALLIES;
            case "public" -> Rights.Preset.OPEN;
            default -> null;
        };
    }

    /** Более строгий из двух режимов FTB: «Животные, рамки, стойки» собраны из двух прав FTB. */
    public static @Nullable String stricter(@Nullable String a, @Nullable String b) {
        if (a == null) return b;
        if (b == null) return a;
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(String mode) {
        return switch (mode) {
            case "private" -> 0;
            case "allies" -> 1;
            case "public" -> 2;
            default -> 1;
        };
    }

    static void setFrom(Rights r, Rights.Action a, @Nullable String mode) {
        Rights.Preset p = preset(mode);
        if (p != null) r.set(a, p);
    }
}
