package works.repka.rysclaims.data;

/**
 * Участок команды: постоянный номер, имя (пустое показывается как «Участок N») и измерение. Чанки ссылаются на номер.
 * {@code rights}: свои права участка (план, раздел 6 «Потом»), null значит права команды.
 */
public final class Plot {
    public final int id;
    public String name;
    public final String dim;
    public @org.jetbrains.annotations.Nullable Rights rights;

    public Plot(int id, String name, String dim) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.dim = dim;
    }

    /** Копия под другим номером (перенос и отмена слияния): имя, измерение и свои права. */
    public Plot copy(int newId) {
        Plot p = new Plot(newId, name, dim);
        p.rights = rights == null ? null : rights.copy();
        return p;
    }
}
