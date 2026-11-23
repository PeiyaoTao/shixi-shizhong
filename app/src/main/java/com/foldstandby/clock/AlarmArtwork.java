package com.foldstandby.clock;

import android.graphics.*;

/** Shared twin-bell clock artwork, with a simplified face for small preview sizes. */
final class AlarmArtwork {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF rect=new RectF();
    private final Matrix matrix=new Matrix();
    private final float[] matrixValues=new float[9];
    private final Shader[] gradients=new Shader[TextGradients.COLORS.length];
    private final Typeface numerals=Typeface.create("sans-serif",Typeface.NORMAL);
    private int ink,secondary,plate,gradient;
    private float minimumStroke;
    private boolean detailed;

    void palette(int primary,int secondary,int plate,int gradient){
        this.ink=primary;this.secondary=secondary;this.plate=plate;this.gradient=gradient;
    }
    private int alpha(int color,int opacity){return Color.argb(opacity,Color.red(color),Color.green(color),Color.blue(color));}
    private void brush(int color,float width,boolean primary){
        paint.setShader(null);paint.setColor(color);paint.setStrokeWidth(width==0?0:Math.max(width,minimumStroke));
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(width==0?Paint.Style.FILL:Paint.Style.STROKE);
        if(primary&&gradient>0){
            if(gradients[gradient]==null)gradients[gradient]=TextGradients.unitShader(gradient);
            TextGradients.applyBounds(gradients[gradient],matrix,matrixValues,-100,-107,100,96);
            paint.setShader(gradients[gradient]);
        }
    }
    void draw(Canvas canvas,float x,float y,float size,int hour,int minute,boolean detailed){
        this.detailed=detailed;minimumStroke=detailed?0:1.25f/Math.max(.01f,size);
        int save=canvas.save();canvas.translate(x,y);canvas.scale(size,size);
        // Splayed feet and the bell supports sit behind the case.
        brush(secondary,4.5f,false);
        canvas.drawLine(-42,58,-55,85,paint);canvas.drawLine(42,58,55,85,paint);
        brush(ink,3,true);
        canvas.drawLine(-61,86,-49,86,paint);canvas.drawLine(49,86,61,86,paint);
        brush(secondary,3.5f,false);
        canvas.drawLine(-44,-59,-54,-77,paint);canvas.drawLine(44,-59,54,-77,paint);
        brush(ink,0,true);rect.set(-10,-91,10,-85);canvas.drawRoundRect(rect,3,3,paint);
        brush(secondary,3,false);canvas.drawLine(0,-85,0,-75,paint);
        bell(canvas,-1);bell(canvas,1);

        brush(Color.BLACK,0,false);canvas.drawCircle(0,0,74,paint);
        brush(plate,7,false);canvas.drawCircle(0,0,72,paint);
        brush(alpha(ink,200),1.7f,true);canvas.drawCircle(0,0,75,paint);
        brush(alpha(secondary,150),1.2f,false);canvas.drawCircle(0,0,66.5f,paint);
        for(int i=0;i<60;i++){
            if(!detailed&&i%5!=0)continue;
            double angle=i*Math.PI/30;boolean major=i%5==0;
            float outer=61,inner=major?53:58;
            brush(major?ink:alpha(secondary,160),major?2.1f:1,major);
            canvas.drawLine((float)Math.sin(angle)*inner,-(float)Math.cos(angle)*inner,
                (float)Math.sin(angle)*outer,-(float)Math.cos(angle)*outer,paint);
        }
        if(detailed){
            brush(secondary,0,false);paint.setTypeface(numerals);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(12);
            canvas.drawText("12",0,-35,paint);canvas.drawText("3",42,4,paint);
            canvas.drawText("6",0,46,paint);canvas.drawText("9",-42,4,paint);
            // Four flush screws and a fine inset ring distinguish the bezel from the face.
            brush(alpha(secondary,150),0,false);
            for(int i=0;i<4;i++){
                double angle=(45+i*90)*Math.PI/180;
                canvas.drawCircle((float)Math.sin(angle)*71,-(float)Math.cos(angle)*71,1.1f,paint);
            }
        }
        hand(canvas,(hour%12+minute/60f)*30,38,3.6f);
        hand(canvas,minute*6,53,2.4f);
        brush(ink,0,true);canvas.drawCircle(0,0,5,paint);
        brush(Color.BLACK,0,false);canvas.drawCircle(0,0,2,paint);
        brush(alpha(ink,200),1,true);canvas.drawCircle(0,0,6.5f,paint);
        paint.setShader(null);canvas.restoreToCount(save);
    }
    private void bell(Canvas canvas,int side){
        int save=canvas.save();canvas.translate(side*57,-77);canvas.rotate(side*34);
        path.reset();path.moveTo(-29,5);path.cubicTo(-29,-14,-18,-29,0,-29);
        path.cubicTo(18,-29,29,-14,29,5);path.close();
        brush(alpha(ink,25),0,false);canvas.drawPath(path,paint);
        brush(ink,2.3f,true);canvas.drawPath(path,paint);
        brush(ink,3.8f,true);canvas.drawLine(-32,6,32,6,paint);
        if(detailed){
            path.reset();path.moveTo(-21,-4);path.cubicTo(-18,-16,-10,-22,0,-22);
            brush(alpha(secondary,180),1.4f,false);canvas.drawPath(path,paint);
        }
        brush(ink,0,true);rect.set(-6,-34,6,-28);canvas.drawRoundRect(rect,2,2,paint);
        paint.setShader(null);canvas.restoreToCount(save);
    }
    private void hand(Canvas canvas,float angle,float length,float width){
        width=Math.max(width,minimumStroke*.8f);
        int save=canvas.save();canvas.rotate(angle);
        path.reset();path.moveTo(0,10);path.lineTo(-width,2);path.lineTo(-width*.65f,-length+7);
        path.lineTo(0,-length);path.lineTo(width*.65f,-length+7);path.lineTo(width,2);path.close();
        brush(ink,0,true);canvas.drawPath(path,paint);paint.setShader(null);canvas.restoreToCount(save);
    }
}
