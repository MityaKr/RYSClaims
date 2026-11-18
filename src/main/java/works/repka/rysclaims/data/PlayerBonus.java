package works.repka.rysclaims.data;

/** Личная добавка игрока к лимитам команды: принадлежит игроку и уходит с ним в другую команду. */
public final class PlayerBonus {
    public int claims;
    public int force;
    /** Игрок ответил «Оставить себе» на вопрос о личной земле при вступлении (макет 45): больше не спрашивать. */
    public boolean keepPersonal;

    public PlayerBonus(int claims, int force) {
        this.claims = claims;
        this.force = force;
    }

    boolean isEmpty() {
        return claims == 0 && force == 0 && !keepPersonal;
    }
}
