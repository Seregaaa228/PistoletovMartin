package dev.frontier.flamingofix;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A house: roof at y 20, a floor at y 14, its slab at y 9, ground y 8 and below; bedrock at y 0. */
public final class MissilePenetrationTest {
    private static int checks;
    private static void check(boolean value,String label) {
        if(!value)throw new AssertionError(label);checks++;
    }
    public static void main(String[] args) {
        Predicate<BlockPos> house=pos->pos.getY()==20 || pos.getY()==14 || pos.getY()<=9;
        Predicate<BlockPos> bedrock=pos->pos.getY()<=0;
        Vec3 dive=new Vec3(0.2,-1,0.1);

        var roof=MissilePenetration.resolve(new Vec3(5.5,21,5.5),dive,24,house,bedrock);
        check(roof.burst().y>9.4 && roof.burst().y<10.6,"bursts on the slab, not the roof: "+roof.burst());
        check(roof.channel().stream().anyMatch(p->p.getY()==20) && roof.channel().stream().anyMatch(p->p.getY()==14),
                "channel through roof and floor: "+roof.channel());
        check(roof.channel().stream().noneMatch(p->p.getY()<=9),"the slab is the blast's: "+roof.channel());

        var ground=MissilePenetration.resolve(new Vec3(5.5,10,5.5),dive,24,house,bedrock);
        check(ground.channel().isEmpty() && ground.burst().y>9.4,"a ground hit bursts at the contact: "+ground.burst());

        var shallow=MissilePenetration.resolve(new Vec3(5.5,21,5.5),dive,3,house,bedrock);
        check(shallow.burst().y>17 && shallow.burst().y<18.5,"depth limit: "+shallow.burst());

        var deep=MissilePenetration.resolve(new Vec3(5.5,4,5.5),new Vec3(0,-1,0),24,pos->false,bedrock);
        check(deep.burst().y>=0.9 && deep.burst().y<2,"stops above an unbreakable block: "+deep.burst());

        var flat=MissilePenetration.resolve(new Vec3(5.5,21,5.5),new Vec3(1,-0.1,0),24,house,bedrock);
        check(flat.burst().y<11 && flat.burst().x-5.5<=flat.burst().y*0+12,"a shallow dive is steepened to 45 degrees: "+flat.burst());
        System.out.println("MissilePenetrationTest: "+checks+" checks passed");
    }
}
