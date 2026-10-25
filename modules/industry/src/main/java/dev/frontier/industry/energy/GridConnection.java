package dev.frontier.industry.energy;

/** One shared diagnostic for simulation, menus and contextual tooltips. */
public enum GridConnection {
    GENERATOR(true, "Генератор электросети"),
    CONNECTED(true, "Линия станция → подстанция → потребитель исправна"),
    NO_SUBSTATION(false, "Подстанция не подключена"),
    SUBSTATION_UNLOADED(false, "Чанки подстанции выгружены"),
    SUBSTATION_DAMAGED(false, "Подстанция повреждена — нужен ремонт"),
    NO_STATION(false, "Электростанция не подключена"),
    STATION_UNLOADED(false, "Чанки электростанции выгружены"),
    STATION_DAMAGED(false, "Электростанция повреждена — нужен ремонт");

    private final boolean connected;
    private final String reason;

    GridConnection(boolean connected, String reason) {
        this.connected = connected;
        this.reason = reason;
    }

    public boolean connected() { return connected; }
    public String reason() { return reason; }

    public static GridConnection distribution(Node station) {
        if (station == null) return NO_STATION;
        if (!station.loaded()) return STATION_UNLOADED;
        return station.functional() ? CONNECTED : STATION_DAMAGED;
    }

    public static GridConnection consumer(Node substation, Node station) {
        if (substation == null) return NO_SUBSTATION;
        if (!substation.loaded()) return SUBSTATION_UNLOADED;
        if (!substation.functional()) return SUBSTATION_DAMAGED;
        return distribution(station);
    }

    public record Node(boolean loaded, boolean functional) {}
}
