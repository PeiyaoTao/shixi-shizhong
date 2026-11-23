import com.foldstandby.clock.PostureGate;

public class PostureGateTest {
    static int checks;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        check(!PostureGate.matches(Float.NaN,8,0,-5),"missing hinge");
        check(!PostureGate.matches(0,8,0,-5),"closed");
        check(!PostureGate.matches(4.9f,8,0,-5),"near closed");
        check(!PostureGate.matches(170.1f,8,0,-5),"near flat hinge");
        check(!PostureGate.matches(180,8,0,-5),"fully unfolded");
        check(PostureGate.classify(90,0,0,9.8f)==PostureGate.STAND,"one flat half supports the other raised half");
        check(PostureGate.classify(90,0,0,-9.8f)==PostureGate.STAND,"inverted flat base support");
        check(PostureGate.matches(10,2,0,9.6f),"small angle with a nearly flat base");
        check(PostureGate.classify(90,.20f,-.02f,9.80f)==PostureGate.STAND,"actual user's hovering support");
        check(!PostureGate.matches(90,Float.NaN,0,-5),"NaN gravity");
        check(!PostureGate.matches(90,8,Float.POSITIVE_INFINITY,-5),"infinite gravity");
        check(!PostureGate.matches(90,0,0,0),"missing gravity");
        check(!PostureGate.matches(90,20,0,-5),"invalid acceleration");
        check(PostureGate.classify(90,-8.23f,.10f,-5.34f)==PostureGate.TENT,
                "actual failing Fold8 posture now works without old calibration");
        check(PostureGate.classify(90,8,0,-5)==PostureGate.TENT,"opposite landscape direction");
        check(PostureGate.classify(10,8,0,5)==PostureGate.STAND,"small angle face up support");
        check(PostureGate.classify(90,0,8,5)==PostureGate.STAND,"portrait support");
        for(float hinge:new float[]{5,8,10,20,45,90,140,170})
            for(double tilt:new double[]{0,5,20.1,30,60,90})
                for(int axis=0;axis<2;axis++)for(int direction:new int[]{-1,1})for(int face:new int[]{-1,1}){
                    float plane=(float)(9.8*Math.sin(Math.toRadians(tilt)))*direction;
                    float z=(float)(9.8*Math.cos(Math.toRadians(tilt)))*face;
                    check(PostureGate.matches(hinge,axis==0?plane:0,axis==1?plane:0,z),
                            "automatic support matrix: "+hinge+"/"+tilt+"/"+axis+"/"+direction+"/"+face);
                }

        PostureGate gate=new PostureGate();
        check(!gate.update(true,0),"entry delay");
        check(!gate.update(true,2199),"entry debounce");
        check(gate.update(true,2200),"enter");
        check(gate.update(false,2300),"exit grace");
        check(gate.update(false,3199),"exit debounce");
        check(!gate.update(false,3200),"exit");
        check(!gate.update(true,3300),"reentry delay");
        check(!gate.update(false,3400),"jitter resets entry");

        gate.reset();
        check(!gate.isStill(0),"no sample cannot settle");
        gate.observe(90,-8.23f,.10f,-5.34f,0);
        check(!gate.isStill(999),"settling delay");
        check(gate.isStill(1000),"stable gravity settles");
        check(!gate.isStill(2500),"stale gravity does not settle");
        gate.observe(90,-8.23f,.10f,-5.34f,3000);
        check(!gate.isStill(3000),"gap restarts settling");
        gate.observe(90,-8.22f,.11f,-5.33f,4000);
        check(gate.isStill(4000),"small sensor noise preserves settling");
        gate.observe(96,-8.22f,.11f,-5.33f,4200);
        check(!gate.isStill(4200),"hinge movement restarts settling");
        gate.observe(96,-7.2f,.11f,-6.5f,4400);
        check(!gate.isStill(5000),"gravity movement restarts settling");
        gate.observe(Float.NaN,-7.2f,.11f,-6.5f,5100);
        check(!gate.isStill(6100),"invalid sample clears settling");

        gate.reset();
        for(int i=0;i<40;i++){
            double tilt=Math.toRadians(25+i*.8);
            float x=(float)(9.8*Math.sin(tilt)),z=(float)(-9.8*Math.cos(tilt));
            long now=i*200L;
            gate.observe(90,x,0,z,now);
            check(!gate.update(PostureGate.matches(90,x,0,z)&&gate.isStill(now),now),
                    "slow continuous hand movement cannot trigger: "+i);
        }
        gate.reset();
        boolean active=false;
        for(long now=0;now<=3200;now+=200){
            gate.observe(90,8,0,-5,now);
            active=gate.update(PostureGate.matches(90,8,0,-5)&&gate.isStill(now),now);
            if(now<3200)check(!active,"no premature launch: "+now);
        }
        check(active,"stable support triggers after 3.2 seconds");
        for(long now=3400;now<=4400;now+=200){
            gate.observe(90,8-(now-3200)/500f,2,-5,now);
            active=gate.update(gate.isStill(now),now);
        }
        check(!active,"picking up an active clock exits after debounce");
        gate.reset();
        check(!gate.update(true,5000),"rest/restart clears old active state");
        check(!gate.isStill(5000),"rest/restart requires fresh samples");
        System.out.println("PASS: "+checks+" automatic posture, movement and debounce checks");
    }
}
