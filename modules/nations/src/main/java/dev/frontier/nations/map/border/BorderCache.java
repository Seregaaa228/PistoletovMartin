package dev.frontier.nations.map.border;

import dev.frontier.nations.map.geometry.BorderPolygon;
import java.util.LinkedHashMap;
import java.util.Map;

/** Accessed only on the client game thread. Replaced on login, cleared on logout. */
public final class BorderCache {
    private static final Map<String,BorderPolygon> BORDERS=new LinkedHashMap<>();
    private static long revision;
    private static Result result;
    private static long nextRequest,resultRequest;
    private static Pending pending;
    private record Pending(long request,String faction,long since){}
    public record Result(String faction,boolean accepted,String message){}
    private BorderCache(){}
    public static void replace(Map<String,BorderPolygon> values){BORDERS.clear();merge(values);}
    public static void merge(Map<String,BorderPolygon> values){values.forEach(BorderCache::update);revision++;}
    public static void update(String faction,BorderPolygon polygon){
        if(!validId(faction))return;
        if(polygon==null)BORDERS.remove(faction);
        else if(BORDERS.containsKey(faction)||BORDERS.size()<NationBorders.MAX_ENTRIES)BORDERS.put(faction,polygon);
        revision++;
    }
    public static BorderPolygon get(String faction){return BORDERS.get(faction);}
    public static long revision(){return revision;}
    public static boolean hasPendingSave(){expire();return pending!=null;}
    private static void expire(){
        if(pending!=null&&System.nanoTime()-pending.since>10_000_000_000L)
            result(pending.request,pending.faction,false,"Нет подтверждения сервера. Контур сохранён в редакторе; можно отправить снова");
    }
    public static long beginSave(String faction){
        if(hasPendingSave()||!validId(faction))return 0;
        result=null;resultRequest=0;pending=new Pending(++nextRequest,faction,System.nanoTime());return pending.request;
    }
    public static void abortSave(long request){if(pending!=null&&pending.request==request)pending=null;}
    public static void result(long request,String faction,boolean accepted,String message){
        if(pending==null||pending.request!=request||!pending.faction.equals(faction))return;
        resultRequest=pending.request;pending=null;result=new Result(faction,accepted,message);
    }
    public static Result takeResult(long request){expire();return request!=0&&resultRequest==request?takeResult():null;}
    public static Result takeResult(){Result value=result;result=null;return value;}
    public static void clear(){BORDERS.clear();pending=null;result=null;resultRequest=0;revision++;}
    private static boolean validId(String id){return id!=null&&!id.isBlank()&&id.length()<=NationBorders.MAX_ID_LENGTH;}
}
