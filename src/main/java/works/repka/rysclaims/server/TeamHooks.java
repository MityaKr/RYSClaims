package works.repka.rysclaims.server;

import java.util.UUID;
import works.repka.ryscore.team.TeamsApi;

/**
 * События команд. В 0.1: вступивший отдаёт личную землю команде, если хватает лимита; слияние переносит землю и
 * отменяется вместе со слиянием. Уход и распад землю не трогают (0.2), но меняют, кто «в сети» для прогрузки.
 */
public final class TeamHooks implements TeamsApi.Listener {
    @Override
    public void onJoined(UUID team, UUID player, boolean carryProgress) {
        ClaimSync.touch(team);
        ClaimSync.accessChanged();
        if (!ClaimsServer.running()) return;
        ClaimsServer.service().joined(team, player);
        ClaimsServer.loaderDirty();
    }

    @Override
    public void onLeft(UUID team, UUID player, boolean keepCopy) {
        ClaimSync.touch(team);
        ClaimSync.accessChanged();
        ClaimsServer.loaderDirty();
    }

    @Override
    public void onDisbanded(UUID team) {
        ClaimSync.accessChanged();
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
