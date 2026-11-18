package works.repka.rysclaims.data;

/** Участок команды: постоянный номер, имя (пустое показывается как «Участок N») и измерение. Чанки ссылаются на номер. */
public final class Plot {
    public final int id;
    public String name;
    public final String dim;

    public Plot(int id, String name, String dim) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.dim = dim;
    }
}
