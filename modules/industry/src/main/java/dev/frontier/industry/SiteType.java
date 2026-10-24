package dev.frontier.industry;
public enum SiteType {
 POWER_STATION("Электростанция"), SUBSTATION("Подстанция"), DRONE_FACTORY("Завод дронов"), VEHICLE_FACTORY("Завод техники"), EQUIPMENT_FACTORY("Оружейный завод"), MISSILE_FACTORY("Ракетный завод"), RESEARCH_CENTER("Исследовательский центр");
 private final String title; SiteType(String title){this.title=title;}
 public String title(){return title;} public boolean isFactory(){return ordinal()>=2 && ordinal()<=5;}
 public boolean isConsumer(){return isFactory() || this==RESEARCH_CENTER;}
 public String path(){return name().toLowerCase(java.util.Locale.ROOT);}
}
