package com.foldstandby.clock;

import android.graphics.*;

/** Conserved sand volume, granular flow and a gravity-aware reset turn. All coordinates are local. */
final class Sandglass {
    static final float CX=150, CY=206, TOP=115, BOTTOM=297, REPOSE=.64f;
    static final long PREPARE_MS=420, TURN_MS=1080, SETTLE_MS=360;
    static final long DEMO_DRAIN_MS=18000, DEMO_CYCLE_MS=DEMO_DRAIN_MS+TURN_MS+SETTLE_MS;
    private static final float ROW=.5f;
    private static final int ROWS=182;
    private static final float[] WIDTHS=new float[ROWS];
    private static final float CAPACITY=capacity(), VOLUME=CAPACITY*.82f;
    private static final int GRAINS=1200;
    private static final float[] GX=new float[GRAINS],GY=new float[GRAINS],GR=new float[GRAINS];
    private static final int TEX_L=70,TEX_T=110,TEX_W=160,TEX_H=190,TEX_SCALE=2;
    private static final RectF TEX_DST=new RectF(TEX_L,TEX_T,TEX_L+TEX_W,TEX_T+TEX_H);
    private static Bitmap grainBitmap;
    static {
        long seed=529813;
        for(int i=0;i<GRAINS;i++){
            seed=(seed*1664525+1013904223)&0xffffffffL;GX[i]=76+(seed%14800)/100f;
            seed=(seed*1664525+1013904223)&0xffffffffL;GY[i]=116+(seed%18000)/100f;
            seed=(seed*1664525+1013904223)&0xffffffffL;GR[i]=.26f+(seed%39)/100f;
        }
    }
    private static synchronized Bitmap grainTexture(){
        if(grainBitmap==null){
            grainBitmap=Bitmap.createBitmap(TEX_W*TEX_SCALE,TEX_H*TEX_SCALE,Bitmap.Config.ARGB_8888);
            Canvas gc=new Canvas(grainBitmap);
            gc.scale(TEX_SCALE,TEX_SCALE);
            gc.translate(-TEX_L,-TEX_T);
            Paint gp=new Paint(Paint.ANTI_ALIAS_FLAG);
            gp.setStyle(Paint.Style.FILL);
            for(int i=0;i<GRAINS;i++){
                gp.setColor(i%4==0?Color.WHITE:Color.BLACK);
                gp.setAlpha(i%4==0?55:48);
                gc.drawCircle(GX[i],GY[i],GR[i],gp);
            }
        }
        return grainBitmap;
    }

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline=new Path(),upper=new Path(),lower=new Path(),sand=new Path(),detail=new Path();
    private final float[] sampleX=new float[2100],sampleY=new float[2100];
    private int samples;
    private int accent,muted,highlight,plate,gradient=-1,shaderAccent;
    private Shader shader;
    private final Matrix shaderMatrix=new Matrix();
    private long lastTime=Long.MIN_VALUE;
    private long suppressedResetAt=Long.MIN_VALUE;
    private double flowTime;
    private float displayedFraction,angle;
    private float cachedFraction=Float.NaN,cachedStrength=Float.NaN,cachedLevel,cachedPeak;
    private boolean resetting;

    Sandglass(){
        chamber(upper,true);chamber(lower,false);
        outline.moveTo(CX-width(TOP)-3,111);
        for(float y=TOP;y<=BOTTOM;y+=1)outline.lineTo(CX-width(y)-3,y);
        outline.lineTo(CX-width(BOTTOM)-3,301);outline.lineTo(CX+width(BOTTOM)+3,301);
        for(float y=BOTTOM;y>=TOP;y-=1)outline.lineTo(CX+width(y)+3,y);
        outline.lineTo(CX+width(TOP)+3,111);outline.close();
        for(float y=CY+1;y<BOTTOM;y+=2.5f)for(float x=-width(y);x<width(y);x+=2.5f){
            if(samples<sampleX.length){sampleX[samples]=x+1.25f;sampleY[samples]=y-CY;samples++;}
        }
    }
    private static void chamber(Path path,boolean top){
        float a=top?TOP:CY,b=top?CY:BOTTOM;
        path.moveTo(CX-width(a),a);
        for(float y=a;y<=b;y+=1)path.lineTo(CX-width(y),y);
        path.lineTo(CX+width(b),b);
        for(float y=b;y>=a;y-=1)path.lineTo(CX+width(y),y);
        path.close();
    }
    static float width(float y){
        double u=Math.min(1,Math.abs(y-CY)/(CY-TOP));
        return 2.4f+(float)(75*Math.pow(Math.sin(u*Math.PI/2),1.5)*(1-.16*Math.pow(u,6)));
    }
    private static float capacity(){float area=0;for(int i=0;i<ROWS;i++){WIDTHS[i]=width(TOP+(i+.5f)*ROW);area+=2*WIDTHS[i]*ROW;}return area;}
    static float clamp(float n){return Math.max(0,Math.min(1,n));}
    static float ease(float n){n=clamp(n);return n*n*n*(n*(n*6-15)+10);}
    static long prepareDuration(float from){return from>.015f?PREPARE_MS:0;}
    static long resetDuration(float from){return prepareDuration(from)+TURN_MS+SETTLE_MS;}
    static boolean resetActive(long time,long at,float from){return at>=0&&time>=at&&time-at<resetDuration(from);}
    static float crater(float level,float strength){return Math.min(15,Math.max(0,(CY-level)*.36f))*strength;}
    static float upperSurface(float level,float x,float strength){
        float radius=Math.max(5,width(level)*.91f),u=Math.max(0,1-Math.abs(x)/radius);
        return level+crater(level,strength)*u*u;
    }
    static float upperArea(float level,float strength){
        float area=0,depth=crater(level,strength),radius=Math.max(5,width(level)*.91f);
        for(int i=0;i<ROWS;i++){
            float rowBottom=TOP+(i+1)*ROW,begin=Math.max(TOP+i*ROW,level),height=rowBottom-begin;
            if(height<=0)continue;float y=(begin+rowBottom)/2;
            float hole=depth<.001f||y>=level+depth?0:radius*(1-(float)Math.sqrt((y-level)/depth));
            area+=2*Math.max(0,WIDTHS[i]-hole)*height;
        }
        return area;
    }
    static float lowerSurface(float peak,float x){return peak+REPOSE*((float)Math.sqrt(x*x+4)-2);}
    static float lowerArea(float peak){
        float area=0;
        for(int i=0;i<ROWS;i++){float y=CY+(i+.5f)*ROW;
            if(y<=peak)continue;float u=(y-peak)/REPOSE+2;
            area+=2*Math.min(WIDTHS[ROWS-1-i],(float)Math.sqrt(u*u-4))*ROW;
        }
        return area;
    }
    static float upperLevel(float fraction,float strength){
        float target=VOLUME*clamp(fraction),lo=TOP-20,hi=CY+1;
        for(int i=0;i<17;i++){float m=(lo+hi)/2;if(upperArea(m,strength)>target)lo=m;else hi=m;}
        return (lo+hi)/2;
    }
    static float lowerPeak(float fraction){
        float target=VOLUME*clamp(fraction),lo=CY-50,hi=BOTTOM+1;
        for(int i=0;i<17;i++){float m=(lo+hi)/2;if(lowerArea(m)>target)lo=m;else hi=m;}
        return (lo+hi)/2;
    }
    static float volume(){return VOLUME;}
    static float fallingY(float seconds){return CY+2+54*seconds+430*seconds*seconds;}

    void palette(int accent,int muted,int highlight,int plate,int gradient){
        this.accent=accent;this.muted=muted;this.highlight=highlight;this.plate=plate;
        if(this.gradient!=gradient||shaderAccent!=accent){
            this.gradient=gradient;shaderAccent=accent;
            shader=gradient>0?TextGradients.shader(gradient,75,112,225,300):
                new LinearGradient(75,145,225,255,new int[]{shade(accent,.63f),accent,shade(accent,.81f)},new float[]{0,.4f,1},Shader.TileMode.CLAMP);
        }
    }
    private static int shade(int color,float scale){return Color.rgb(Math.round(Color.red(color)*scale),Math.round(Color.green(color)*scale),Math.round(Color.blue(color)*scale));}
    boolean draw(Canvas canvas,float fraction,boolean pouring,boolean motion,long time,long resetAt,float resetFrom){
        fraction=clamp(fraction);resetFrom=clamp(resetFrom);
        if(!motion)suppressedResetAt=resetAt;
        resetting=motion&&resetAt!=suppressedResetAt&&resetActive(time,resetAt,resetFrom);angle=0;displayedFraction=fraction;
        long dt=lastTime==Long.MIN_VALUE||time<lastTime?0:Math.min(80,time-lastTime);lastTime=time;
        if(motion&&(pouring||resetting))flowTime+=dt;
        float strength=pouring?1:.32f;
        if(resetting){
            long elapsed=time-resetAt,prepare=prepareDuration(resetFrom);
            if(elapsed<prepare){
                displayedFraction=resetFrom*(1-ease(elapsed/(float)prepare));
                upright(canvas,displayedFraction,true,1,true);
            }else if(elapsed<prepare+TURN_MS){
                float turn=(elapsed-prepare)/(float)TURN_MS,progress=ease(turn);
                angle=-180*progress;
                float lift=(float)Math.sin(turn*Math.PI);
                int save=canvas.save();canvas.translate(-18*lift,-4*lift);canvas.scale(1-.24f*lift,1-.24f*lift,CX,CY);canvas.rotate(angle,CX,CY);
                shaderMatrix.setRotate(-angle,CX,CY);shader.setLocalMatrix(shaderMatrix);
                tiltedSand(canvas,angle,progress);shell(canvas);canvas.restoreToCount(save);
                shader.setLocalMatrix(null);
                displayedFraction=0;
            }else{
                float settling=ease((elapsed-prepare-TURN_MS)/(float)SETTLE_MS);
                upright(canvas,fraction,pouring,strength*settling,true);
            }
        }else upright(canvas,fraction,pouring,strength,motion&&pouring);
        return motion&&(resetting||pouring&&fraction>0);
    }
    private void upright(Canvas c,float fraction,boolean pouring,float strength,boolean moving){
        if(!Float.isFinite(cachedFraction)||!Float.isFinite(cachedStrength)
                ||Math.abs(fraction-cachedFraction)>0.0001f
                ||Math.abs(strength-cachedStrength)>0.0001f
                ||((fraction==0f||fraction==1f)&&fraction!=cachedFraction)){
            cachedFraction=fraction;cachedStrength=strength;
            cachedLevel=upperLevel(fraction,strength);cachedPeak=lowerPeak(1-fraction);
        }
        float level=cachedLevel,peak=cachedPeak;
        int save=c.save();c.clipPath(upper);
        sand.reset();sand.moveTo(65,upperSurface(level,-85,strength));
        for(int x=-85;x<=85;x+=2)sand.lineTo(CX+x,upperSurface(level,x,strength));
        sand.lineTo(235,CY+3);sand.lineTo(65,CY+3);sand.close();
        if(fraction>0){fillSand(c,sand,moving,false);surface(c,level,strength);if(moving)funnelGrains(c,level,strength);}
        c.restoreToCount(save);
        save=c.save();c.clipPath(lower);
        sand.reset();sand.moveTo(65,lowerSurface(peak,-85));
        for(int x=-85;x<=85;x+=2)sand.lineTo(CX+x,lowerSurface(peak,x));
        sand.lineTo(235,BOTTOM+3);sand.lineTo(65,BOTTOM+3);sand.close();
        if(fraction<1)fillSand(c,sand,moving,false);
        if(moving&&fraction<1)landslide(c,peak);
        c.restoreToCount(save);
        if(pouring&&fraction>0)fall(c,Math.max(CY+5,peak-1),moving);
        shell(c);
    }
    private void fillSand(Canvas c,Path shape,boolean moving,boolean turning){
        paint.setStyle(Paint.Style.FILL);paint.setColor(accent);paint.setShader(shader);c.drawPath(shape,paint);paint.setShader(null);
        int save=c.save();c.clipPath(shape);
        if(!turning){
            paint.setFilterBitmap(true);paint.setAlpha(255);
            c.drawBitmap(grainTexture(),null,TEX_DST,paint);
        }else{
            float movement=(float)(flowTime*.018);
            for(int i=0;i<GRAINS;i++){
                paint.setColor(i%4==0?Color.WHITE:Color.BLACK);paint.setAlpha(i%4==0?55:48);
                float x=GX[i]+(float)Math.sin(movement+i)*1.8f,y=GY[i];
                c.drawCircle(x,y,GR[i],paint);
            }
            paint.setAlpha(255);
        }
        c.restoreToCount(save);
    }
    private void surface(Canvas c,float level,float strength){
        detail.reset();detail.moveTo(65,upperSurface(level,-85,strength));
        for(int x=-85;x<=85;x+=3)detail.lineTo(CX+x,upperSurface(level,x,strength));
        stroke(c,detail,accent,.8f,130);
    }
    private void funnelGrains(Canvas c,float level,float strength){
        float radius=width(level)*.86f;
        paint.setColor(highlight);
        if(gradient>0)paint.setShader(shader);
        for(int i=0;i<24;i++){
            double t=(flowTime/(1300+i%5*110)+i*.381966)%1;
            float x=(i%2==0?-1:1)*radius*(float)(1-t*t),y=upperSurface(level,x,strength)+1+(i%3)*.7f;
            paint.setAlpha(65+i%4*20);c.drawCircle(CX+x,y,.4f+i%3*.13f,paint);
        }
        paint.setShader(null);paint.setAlpha(255);
    }
    private void fall(Canvas c,float end,boolean moving){
        // Independent emission ages yield acceleration under gravity instead of a looping dotted line.
        double clock=moving?flowTime:0;paint.setStyle(Paint.Style.FILL);paint.setColor(accent);paint.setShader(shader);
        for(int i=0;i<44;i++){
            float age=(float)((clock+i*17.3+i%3*2.1)%760)/1000f;
            float y=fallingY(age);if(y>end)continue;
            float x=CX+(float)Math.sin(i*3.17)*(.35f+age*1.7f);
            paint.setAlpha(175+i%4*20);c.drawCircle(x,y,.4f+i%4*.16f,paint);
        }
        paint.setShader(null);paint.setAlpha(255);
        if(moving){
            paint.setColor(accent);
            if(gradient>0)paint.setShader(shader);
            for(int i=0;i<9;i++){
                float age=(float)((flowTime+i*43.7)%250)/1000f,side=i%2==0?-1:1;
                float x=side*(2+(18+i%3*5)*age),y=end-(23+i%3*5)*age+165*age*age;
                paint.setAlpha(Math.round(180*(1-age/.25f)));c.drawCircle(CX+x,y,.45f+i%2*.18f,paint);
            }
            paint.setShader(null);paint.setAlpha(255);
        }
    }
    private void landslide(Canvas c,float peak){
        paint.setColor(accent);
        if(gradient>0)paint.setShader(shader);
        for(int i=0;i<20;i++){
            float age=(float)((flowTime+i*81.3)%1100)/1000f,side=i%2==0?-1:1;
            float x=side*(1+12*age+31*age*age),y=lowerSurface(peak,x)-.7f;
            paint.setAlpha(Math.round(140*(1-age/1.1f)));c.drawCircle(CX+x,y,.4f+i%3*.1f,paint);
        }
        paint.setShader(null);paint.setAlpha(255);
    }
    private void tiltedSand(Canvas c,float rotation,float turn){
        double gravity=Math.toRadians(rotation-10*Math.sin(turn*Math.PI));
        float nx=(float)Math.sin(gravity),ny=(float)Math.cos(gravity),tx=ny,ty=-nx,cone=REPOSE*(1-turn);
        float lo=-150,hi=150;int desired=Math.round(samples*.82f);
        for(int k=0;k<16;k++){
            float m=(lo+hi)/2;int count=0;
            for(int i=0;i<samples;i++)if(nx*sampleX[i]+ny*sampleY[i]-cone*Math.abs(tx*sampleX[i]+ty*sampleY[i])>=m)count++;
            if(count>desired)lo=m;else hi=m;
        }
        float level=(lo+hi)/2;
        sand.reset();
        for(int u=-180;u<=180;u+=3){float v=level+cone*Math.abs(u);if(u==-180)sand.moveTo(CX+tx*u+nx*v,CY+ty*u+ny*v);else sand.lineTo(CX+tx*u+nx*v,CY+ty*u+ny*v);}
        sand.lineTo(CX+tx*180+nx*260,CY+ty*180+ny*260);sand.lineTo(CX-tx*180+nx*260,CY-ty*180+ny*260);sand.close();
        int save=c.save();c.clipPath(lower);fillSand(c,sand,true,true);c.restoreToCount(save);
    }
    private void shell(Canvas c){
        stroke(c,outline,muted,1.7f,200);
        detail.reset();detail.moveTo(89,124);detail.cubicTo(81,139,102,164,116,177);
        detail.moveTo(106,250);detail.cubicTo(94,264,83,280,91,288);
        stroke(c,detail,highlight,2.1f,135);
        detail.reset();detail.moveTo(210,125);detail.cubicTo(218,140,198,165,185,178);
        detail.moveTo(195,251);detail.cubicTo(207,265,218,281,209,289);
        stroke(c,detail,muted,.9f,120);
        paint.setStyle(Paint.Style.FILL);paint.setColor(accent);paint.setShader(shader);
        c.drawRoundRect(72,96,228,103,3,3,paint);c.drawRoundRect(72,309,228,316,3,3,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.3f);paint.setColor(muted);
        if(gradient>0){paint.setShader(shader);paint.setAlpha(165);}
        c.drawRoundRect(77,104,223,111,2,2,paint);c.drawRoundRect(77,301,223,308,2,2,paint);
        paint.setShader(null);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);
    }
    private void stroke(Canvas c,Path path,int color,float width,int alpha){
        paint.setShader(gradient>0?shader:null);paint.setColor(color);paint.setAlpha(alpha);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(path,paint);paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);
    }
    float fractionForCheck(){return displayedFraction;}
    float angleForCheck(){return angle;}
    boolean isResetting(){return resetting;}
}
