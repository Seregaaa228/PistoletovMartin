package dev.frontier.flamingofix;

import java.util.ArrayList;
import java.util.List;

public final class DeferredContactIteratorTest {
    private static int checks;
    private static void check(boolean value,String name) {if(!value)throw new AssertionError(name);checks++;}
    public static void main(String[] args) {
        for(int shot=0;shot<20;shot++)for(boolean veto:new boolean[]{false,true}) {
            Object contact=new Object(),otherVehicle=new Object(),ordinary=new Object();
            var recipients=new ArrayList<>(List.of(contact,otherVehicle,ordinary));
            var nativeIterator=new DeferredContactIterator<>(recipients.iterator(),value->value==contact);
            while(nativeIterator.hasNext())if(nativeIterator.next()!=ordinary)nativeIterator.remove();
            check(recipients.contains(contact),"native vehicle splash consumption lost exact contact "+shot);
            check(!recipients.contains(otherVehicle) && recipients.contains(ordinary),"unrelated recipient behavior changed "+shot);
            if(veto)recipients.remove(contact); // A later Forge protection handler.
            int direct=recipients.contains(contact)?1:0;recipients.remove(contact); // Before vanilla splash iteration.
            check(direct==(veto?0:1),"protection veto confused with native splash handling "+shot);
            check(recipients.equals(List.of(ordinary)),"vehicle entered second vanilla splash pass "+shot);
        }
        System.out.println("DeferredContactIteratorTest: "+checks+" checks passed");
    }
}
