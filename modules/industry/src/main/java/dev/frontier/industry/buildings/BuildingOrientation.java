package dev.frontier.industry.buildings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Direction lives in the existing saved blueprint integer; versions 1-6 keep their meaning. */
public final class BuildingOrientation {
    public static final int VERSION=16;
    private BuildingOrientation(){}
    public static boolean applies(int version){return version>=6&&version<=316&&version%100>=6&&version%100<=16;}
    public static int quarter(int version){return applies(version)?version/100:0;}
    public static int forPlayer(Player player){
        Direction entrance=player.getDirection().getOpposite();
        return VERSION+100*switch(entrance){case EAST->1;case SOUTH->2;case WEST->3;default->0;};
    }
    public static BlockPos rotate(BlockPos p,int version){return switch(quarter(version)){case 1->new BlockPos(-p.getZ(),p.getY(),p.getX());case 2->new BlockPos(-p.getX(),p.getY(),-p.getZ());case 3->new BlockPos(p.getZ(),p.getY(),-p.getX());default->p;};}
    public static Direction rotate(Direction d,int version){for(int i=0;i<quarter(version);i++)d=d.getClockWise();return d;}
    public static BlockState rotate(BlockState s,int version){
        var id=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if(id!=null&&id.getNamespace().equals("another_furniture"))return s.rotate(Rotation.values()[quarter(version)]);
        if(s.hasProperty(BlockStateProperties.HORIZONTAL_FACING))s=s.setValue(BlockStateProperties.HORIZONTAL_FACING,rotate(s.getValue(BlockStateProperties.HORIZONTAL_FACING),version));
        else s=s.rotate(Rotation.values()[quarter(version)]);
        return s;
    }
}
