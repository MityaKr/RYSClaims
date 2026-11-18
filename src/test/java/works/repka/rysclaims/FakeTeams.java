package works.repka.rysclaims;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.server.Teams;

/** Команды для проверок: кто где, с какой ролью, у кого какие права, кто с кем в союзе. Игрок без команды соло. */
public final class FakeTeams implements Teams {
    private final Map<UUID, UUID> teamOf = new HashMap<>();
    private final Map<UUID, String> role = new HashMap<>();
    private final Map<UUID, List<UUID>> members = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> allies = new HashMap<>();
    /** Право → роли, у которых оно есть. Нет записи: есть у всех. */
    private final Map<String, Set<String>> perms = new HashMap<>();

    public FakeTeams team(UUID team, UUID leader, UUID... others) {
        List<UUID> list = new ArrayList<>();
        list.add(leader);
        teamOf.put(leader, team);
        role.put(leader, "leader");
        for (UUID o : others) {
            list.add(o);
            teamOf.put(o, team);
            role.put(o, "member");
        }
        members.put(team, list);
        return this;
    }

    public FakeTeams join(UUID team, UUID player, String r) {
        members.computeIfAbsent(team, t -> new ArrayList<>()).add(player);
        teamOf.put(player, team);
        role.put(player, r);
        return this;
    }

    /** Игрок ушёл из команды и стал соло. */
    public FakeTeams leave(UUID player) {
        UUID team = teamOf.remove(player);
        if (team != null && members.containsKey(team)) members.get(team).remove(player);
        role.remove(player);
        return this;
    }

    public FakeTeams role(UUID player, String r) {
        role.put(player, r);
        return this;
    }

    public FakeTeams ally(UUID a, UUID b) {
        allies.computeIfAbsent(a, x -> new HashSet<>()).add(b);
        allies.computeIfAbsent(b, x -> new HashSet<>()).add(a);
        return this;
    }

    public FakeTeams perm(String permission, String... roles) {
        perms.put(permission, Set.of(roles));
        return this;
    }

    @Override
    public UUID teamOf(UUID player) {
        return teamOf.getOrDefault(player, player);
    }

    @Override
    public Collection<UUID> members(UUID team) {
        return members.getOrDefault(team, List.of(team));
    }

    @Override
    public boolean can(UUID player, String permission) {
        Set<String> roles = perms.get(permission);
        return roles == null || roles.contains(roleOf(player));
    }

    @Override
    public String roleOf(UUID player) {
        return role.getOrDefault(player, "leader");
    }

    @Override
    public Collection<UUID> allies(UUID team) {
        return allies.getOrDefault(team, Set.of());
    }

    @Override
    public @Nullable UUID leader(UUID team) {
        List<UUID> m = members.get(team);
        return m == null ? team : m.get(0);
    }
}
