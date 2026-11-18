package works.repka.rysclaims.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import works.repka.rysclaims.RYSClaims;

/**
 * Сеть RYSClaims (план, раздел 9.2). Пока один пакет: {@link Denied}, причина отказа, если действие дошло до сервера.
 * Остальные пакеты окна земли (подписка на регионы, правки) придут с клиентом З4.
 */
public final class ClaimsNet {
    private ClaimsNet() {}

    /**
     * Сервер → клиент: отказ. {@code reason} это ключ {@code ClaimService.Reason}, {@code owner} имя хозяина земли (пусто,
     * если земля скрыта от этого игрока или ничья).
     */
    public record Denied(String reason, String owner) implements CustomPacketPayload {
        public static final Type<Denied> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "denied"));
        public static final StreamCodec<ByteBuf, Denied> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), Denied::reason, ByteBufCodecs.stringUtf8(128), Denied::owner, Denied::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // optional: клиент без мода (или старый) не отваливается, сервер тогда шлёт строку над хотбаром
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(Denied.TYPE, Denied.CODEC, ClaimsNet::onDenied);
    }

    private static void onDenied(Denied payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) Client.denied(payload);
    }

    /** Отдельный класс: на выделенном сервере клиентские классы не загружаются. */
    private static final class Client {
        static void denied(Denied d) {
            works.repka.rysclaims.client.DenyFeedback.show(d.reason(), d.owner());
        }
    }

    /** Может ли клиент принять пакет отказа. */
    public static boolean accepts(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(Denied.TYPE);
    }

    public static void denied(ServerPlayer player, String reason, String owner) {
        PacketDistributor.sendToPlayer(player, new Denied(reason, owner));
    }
}
