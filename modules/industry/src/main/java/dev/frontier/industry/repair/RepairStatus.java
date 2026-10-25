package dev.frontier.industry.repair;

/** Stable wire keys; animation has only three visual states. */
public enum RepairStatus {
    IDLE("idle", "Ожидание приказа", 0),
    MOVING("moving", "Идёт к входу объекта", 1),
    REPAIRING("repairing", "Ремонтирует", 2),
    NO_PATH("no_path", "Нет пути к входу; повтор через 5 секунд", 0),
    NO_MATERIALS("no_materials", "Нужен ремонтный комплект во входных слотах", 0),
    UNLOADED("unloaded", "Цель выгружена — прогресс сохранён", 0),
    CANCELLED("cancelled", "Приказ ремонта отменён", 0),
    TARGET_MISSING("target_missing", "Назначенный объект исчез", 0),
    UNAUTHORIZED("unauthorized", "Объект или роль не разрешены", 0),
    OCCUPIED("occupied", "Рабочий занят другой задачей", 0),
    COMPLETE("complete", "Ремонт завершён", 0),
    DEAD("dead", "Рабочий погиб", 0);

    public final String key, reason;
    public final int animation;
    RepairStatus(String key, String reason, int animation) {
        this.key = key; this.reason = reason; this.animation = animation;
    }
    public static RepairStatus fromKey(String key) {
        for (RepairStatus status : values()) if (status.key.equals(key)) return status;
        return IDLE;
    }
}
