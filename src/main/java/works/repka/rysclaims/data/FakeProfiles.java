package works.repka.rysclaims.data;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Кто хозяин фейкового игрока (разбор фейков 20.11, без игры). Моды делают фейка двумя способами (CFR jar сервера RepYS):
 * с UUID хозяина машины (установщик Create, цифровой шахтёр Mekanism, Cyclic, Modular Routers, Just Dire Things, AE2,
 * RFTools, Mob Grinding Utils) или с общим профилем мода без хозяина (Actually Additions через {@code [Minecraft]}
 * NeoForge, Immersive Engineering, Integrated Tunnels, Sophisticated, Ars Nouveau, Industrial Foregoing и запасные профили
 * тех, у кого хозяина не оказалось). Общий профиль это не игрок: его права не спрашиваем, машину судит место.
 *
 * <p>Список только из констант, без кэша: ничего не копится и не утекает.
 */
public final class FakeProfiles {
    /** Общие профили модов из сборки RepYS (сверено CFR 20.11). */
    static final Set<UUID> SHARED = Set.of(
        UUID.fromString("41c82c87-7afb-4024-ba57-13d2c99cae77"), // [Minecraft] FakePlayerFactory.getMinecraft: Actually Additions, Occultism
        UUID.fromString("41c82c87-7afb-4024-bb57-13d2c99cae77"), // [IntegratedTunnels]
        UUID.fromString("99562b85-bd1a-4ded-bb1a-c307bf0c0133"), // [ImmersiveEngineering]
        UUID.fromString("46e82cd0-d480-3d48-800a-77431ede078e"), // [Mekanism] без хозяина
        UUID.fromString("9e2faded-cafe-4ec2-c314-dad129ae971d"), // Deployer Create без хозяина
        UUID.fromString("7400926d-1007-4e53-880f-b43e67f2bf29"), // Ars_Nouveau
        UUID.fromString("4191a6f5-37fe-45d9-8ba3-4549be778e54"), // [JustDiresFakePlayer] без хозяина
        UUID.fromString("60c173a5-e1e6-4b87-85b1-272ce424521d"), // [AE2] без хозяина
        UUID.fromString("d28b7061-fb92-4064-90fb-7e02b95a72a6"), // [IF]
        UUID.fromString("b18836e2-b89d-3cde-a2b0-b130b0af3bdb"), // Mob Grinding Utils без хозяина
        UUID.nameUUIDFromBytes("modularrouters".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("rftools_builder".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("sophisticated_core_fake_player".getBytes(StandardCharsets.UTF_8)));

    private FakeProfiles() {}

    /**
     * Хозяин фейка: его UUID ({@code getUUID()}: Create и Mekanism подменяют его хозяином машины), если это не общий
     * профиль мода; иначе null.
     */
    public static @Nullable UUID owner(@Nullable UUID id) {
        if (id == null || SHARED.contains(id)) return null;
        return id;
    }

    /** Общий профиль мода без хозяина. */
    public static boolean shared(@Nullable UUID id) {
        return id != null && SHARED.contains(id);
    }
}
