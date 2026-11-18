package works.repka.rysclaims.data;

/** Личная добавка игрока к лимитам команды: принадлежит игроку и уходит с ним в другую команду. */
public final class PlayerBonus {
    public int claims;
    public int force;

    public PlayerBonus(int claims, int force) {
        this.claims = claims;
        this.force = force;
    }

    boolean isEmpty() {
        return claims == 0 && force == 0;
    }
}
