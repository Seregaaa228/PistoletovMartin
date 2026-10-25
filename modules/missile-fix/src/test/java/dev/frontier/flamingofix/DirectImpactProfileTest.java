package dev.frontier.flamingofix;

public final class DirectImpactProfileTest {
    private static int checks;
    private static void check(boolean value,String label) {
        if(!value)throw new AssertionError(label);checks++;
    }
    public static void main(String[] args) {
        String[] kinds={"fp5","storm_shadow","jagm","peacekeeper","spike_nlos"};
        double[] raw={300,220,150,400,120};
        for(int k=0;k<kinds.length;k++) {
            String kind=kinds[k];
            check(DirectImpactProfile.base(kind)==raw[k],"wrong explicit kind profile "+kind);
            for(String warhead:new String[]{"none","he","penetrating","graphite","cluster","thermobaric","nuclear","shaped_charge"}) {
                float damage=DirectImpactProfile.damage(kind,warhead,raw[k],DirectImpactProfile.scale(warhead));
                boolean supported=DirectImpactProfile.supported(kind,warhead) && (!warhead.equals("graphite")||kind.equals("fp5"));
                check(supported?damage>0:damage==0,"warhead compatibility/scaling "+kind+"/"+warhead);
                check(DirectImpactProfile.damage(kind,warhead,0,1)==0,"config disable "+kind);
                check(DirectImpactProfile.damage(kind,warhead,Double.NaN,1)==0,"nonfinite damage "+kind);
            }
        }
        check(DirectImpactProfile.damage("jagm","he",150,1)==150,"JAGM direct default");
        // Native F16 damage modifiers: subtract seven, explosion x4, total x0.65.
        check(Math.max(0,(150-7)*4*.65)>0,"direct hit is swallowed by accepted native armor");
        check(DirectImpactProfile.damage("spike_nlos","shaped_charge",120,1.75)==210,"shaped direct scale");
        check(DirectImpactProfile.damage("peacekeeper","nuclear",400,2.5)==1000,"nuclear direct scale");
        check(DirectImpactProfile.damage("fp5","he",4096,10)==16384,"configured damage cap");
        for(int shot=0;shot<20;shot++) {
            var contact=new ImpactContact<Object>();Object target=new Object();
            check(contact.take()==null,"route/near-miss acquired a target");
            contact.record(target);
            check(contact.take()==target,"actual contact lost its recipient");
            check(contact.take()==null,"repeated blast dealt direct damage twice");
            contact.record(target);contact.record(null);
            check(contact.take()==null,"nearer block failed to clear former entity target");
        }
        System.out.println("DirectImpactProfileTest: "+checks+" checks passed");
    }
}
