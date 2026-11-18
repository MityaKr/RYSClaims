package works.repka.rysclaims.server;

import java.util.UUID;
import works.repka.ryscore.team.TeamsApi;

/**
 * События команд (план, раздел 8): вступивший с личной землёй получает вопрос «Отдать команде?» (макет 45); ушедший может
 * забрать чанки, которые принёс; слияние переносит землю (предпросмотр говорит, хватит ли лимита) и отменяется вместе со
 * слиянием; распад освобождает землю с отсчётом 7 дней. Любая перемена поднимает версию прав.
 */
public final class TeamHooks implements TeamsApi.Listener {
    @Override
    public void onJoined(UUID team, UUID player, boolean carryProgress) {
        ClaimSync.touch(team);
        ClaimSync.landChanged(team);
        ClaimSync.accessChanged();
        if (!ClaimsServer.running()) return;
        // личная земля переходит не сама: игрок отвечает на вопрос (макет 45)
        Membership.joined(team, player);
        ClaimsServer.loaderDirty();
    }

    @Override
    public void onLeft(UUID team, UUID player, boolean keepCopy) {
        ClaimSync.touch(team);
        ClaimSync.landChanged(team);
        ClaimSync.landChanged(player);
        ClaimSync.accessChanged();
        Membership.left(team, player);
        ClaimsServer.loaderDirty();
    }

    @Override
    public void onDisbanded(UUID team) {
        ClaimSync.accessChanged();
        if (ClaimsServer.running()) ClaimsServer.service().disbanded(team, Commands.teamName(ClaimsServer.service(), team).getString());
        ClaimsServer.loaderDirty();
    }

    @Override
    public void onMerged(UUID from, UUID into) {
        ClaimSync.accessChanged();
        if (ClaimsServer.running()) ClaimsServer.service().merged(from, into);
    }

    @Override
    public void onMergeUndone(UUID from, UUID into) {
        ClaimSync.accessChanged();
        if (ClaimsServer.running()) ClaimsServer.service().mergeUndone(from, into);
    }

    @Override
    public void onRoleChanged(UUID team, UUID player) {
        ClaimSync.touch(team);
        ClaimSync.accessChanged();
        ClaimsServer.loaderDirty();
    }

    @Override
    public java.util.List<TeamsApi.MergeLine> previewMerge(UUID from, UUID into) {
        return Membership.preview(from, into);
    }

    @Override
    public void onAlliesChanged(UUID team) {
        ClaimSync.accessChanged();
    }

    @Override
    public void onRenamed(UUID team) {
        // имя команды лежит в палитрах регионов: подписчикам регионы заново
        ClaimSync.resendAll();
        ClaimSync.touch(team);
    }
}
