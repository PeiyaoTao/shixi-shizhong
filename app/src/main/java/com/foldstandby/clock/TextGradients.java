package com.foldstandby.clock;

import android.graphics.*;

/** Stable preset IDs are persisted with the theme, independently of clock style. */
final class TextGradients {
    static final String[] NAMES={"纯色","极光","晨曦","海洋","莓果","金霞"};
    static final int[][] COLORS={{0xffe8ebf0,0xffe8ebf0},{0xffa5f3cf,0xff70c9ff,0xffc7a6ff},{0xffffce9a,0xffff8fa9,0xffb9a4ff},{0xffb7f7f0,0xff7dd3fc,0xff7c9cff},{0xffffb5dd,0xffd9adff,0xff9bb5ff},{0xffffedb2,0xffffc46b,0xfff99b8b}};
    static Shader shader(int index,float left,float top,float right,float bottom){return new LinearGradient(left,top,right,bottom,COLORS[Math.max(0,Math.min(index,COLORS.length-1))],null,Shader.TileMode.CLAMP);}
    static Shader unitShader(int index){return new LinearGradient(0,0,1,0,COLORS[Math.max(0,Math.min(index,COLORS.length-1))],null,Shader.TileMode.CLAMP);}
    static void applyBounds(Shader shader,Matrix matrix,float[] values,float left,float top,float right,float bottom){
        float dx=right-left,dy=bottom-top;
        if(dx==0f&&dy==0f)dx=1f;
        values[0]=dx;values[1]=-dy;values[2]=left;
        values[3]=dy;values[4]=dx;values[5]=top;
        values[6]=0f;values[7]=0f;values[8]=1f;
        matrix.setValues(values);
        shader.setLocalMatrix(matrix);
    }
}
