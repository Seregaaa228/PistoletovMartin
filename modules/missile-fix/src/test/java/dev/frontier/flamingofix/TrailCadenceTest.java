package dev.frontier.flamingofix;

public final class TrailCadenceTest {
    public static void main(String[] args) {
        int checks=0,oldBlackoutPhases=0;
        for(int interval:new int[]{2,4,6,8,12,16,24})for(int phase=0;phase<24;phase++) {
            long previous=Long.MIN_VALUE;
            int samples=0,flames=0,oldFlames=0;
            for(int tick=phase;tick<phase+interval*20;tick++) {
                int sample=TrailCadence.sample(previous,tick,interval,true);
                if(sample==0)continue;
                if(previous!=Long.MIN_VALUE && tick-previous!=interval)
                    throw new AssertionError("particle sample changed its distance/quality cadence");
                previous=tick;samples++;
                if((sample&TrailCadence.FLAME)!=0)flames++;
                if(tick%4==0)oldFlames++;
            }
            if(samples!=20 || flames!=20)
                throw new AssertionError("nearby exhaust disappeared interval="+interval+" phase="+phase);
            if(oldFlames==0)oldBlackoutPhases++;
            checks++;
        }
        if(oldBlackoutPhases==0)throw new AssertionError("old phase-lock failure was not reproduced");
        if((TrailCadence.sample(Long.MIN_VALUE,3,24,false)&TrailCadence.FLAME)!=0)
            throw new AssertionError("far smoke gained an unbounded bright emitter");
        if(TrailCadence.sample(100,1,24,true)==0)
            throw new AssertionError("clock rewind must not suppress exhaust for the old world's age");
        // The priority order remains within the existing Minimal eight-particle
        // budget, including when only one final slot is available.
        for(int alreadyUsed=0;alreadyUsed<8;alreadyUsed++) {
            int budget=8-alreadyUsed,flames=0,spent=0;
            int sample=TrailCadence.sample(Long.MIN_VALUE,3,24,true);
            if((sample&TrailCadence.FLAME)!=0 && spent<budget){spent++;flames++;}
            if((sample&TrailCadence.SMOKE)!=0 && spent<budget)spent++;
            if(spent>budget || flames!=1)throw new AssertionError("smoke starved an admitted flame");
            checks++;
        }
        System.out.println("TrailCadenceTest: "+(checks+2)+" checks passed; old blackout phases="+oldBlackoutPhases);
    }
}
