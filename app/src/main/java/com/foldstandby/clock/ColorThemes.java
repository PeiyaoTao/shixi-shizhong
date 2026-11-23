package com.foldstandby.clock;

import org.json.JSONObject;

/** Complete, contrast-aware combinations, applied at the user's selected theme scope. */
final class ColorThemes {
    static final String[] NAMES={"月白","冰川","苔绿","日落","琥珀","薰衣草","樱桃","海盐","纸白","奶油","石墨","暖红"};
    // background, time, secondary, accent, plate
    private static final int[][] COLORS={
        {0xff000000,0xffeee9db,0xff98958c,0xffd8ceb8,0xff18191b},
        {0xff07121a,0xffd9f3ff,0xff86a9bc,0xff80cfff,0xff142b3b},
        {0xff0b1712,0xffdef2d5,0xff8ca997,0xffa9d18e,0xff1c3026},
        {0xff1b1016,0xffffded0,0xffb68b9a,0xffffa06e,0xff35202c},
        {0xff160f06,0xffffd594,0xffb49a74,0xffffb849,0xff302515},
        {0xff151023,0xffe8dfff,0xffa297bf,0xffbfabff,0xff2a203e},
        {0xff1d0b13,0xffffdce8,0xffb88699,0xffff8eb7,0xff36202a},
        {0xff081719,0xffd6f5ee,0xff82aaa7,0xff85d6cb,0xff183137},
        {0xfff3f0e9,0xff202934,0xff68717c,0xff46698c,0xffdfdcd5},
        {0xffeee1ca,0xff4d3f33,0xff8b7968,0xffa85f35,0xffdbccb1},
        {0xff121418,0xffd8dce2,0xff888f9a,0xffa5b2c5,0xff242a33},
        {0xff000000,0xffd17a65,0xff985d53,0xffbd6656,0xff1c1210}
    };
    static int color(int theme,int index){if(index==0)return 0xff000000;if(theme==8)return new int[]{0xff000000,0xfff3f0e9,0xffaaa99f,0xffb7c9dd,0xff586576}[index];if(theme==9)return new int[]{0xff000000,0xffeee1ca,0xffb5a48d,0xfff2b789,0xff655647}[index];return COLORS[theme][index];}
    static JSONObject theme(int index){int[] c=new int[5];for(int i=0;i<5;i++)c[i]=color(index,i);JSONObject j=new JSONObject();int[] roles={c[0],c[1],c[2],c[3],c[3],c[1],c[3],c[3],c[2],c[1],c[4]};for(int i=0;i<Scene.ROLES.length;i++)Scene.put(j,Scene.ROLES[i],roles[i]);return j;}
    static void apply(JSONObject target,int index){Scene.put(target,"timeGradient",0);JSONObject values=theme(index);for(String role:Scene.ROLES)Scene.put(target,role,values.optInt(role));}
    static boolean matches(JSONObject target,int index){JSONObject values=theme(index);for(String role:Scene.ROLES)if(!target.has(role)||target.optInt(role)!=values.optInt(role))return false;return true;}
    static void migrateLightPreset(JSONObject target){for(int index=8;index<=9;index++){int[] c=COLORS[index],old={c[0],c[1],c[2],c[3],c[3],c[1],c[3],c[3],c[2],c[1],c[4]};boolean matches=true;for(int role=0;role<old.length;role++)matches&=target.has(Scene.ROLES[role])&&target.optInt(Scene.ROLES[role])==old[role];if(matches){int gradient=target.optInt("timeGradient");apply(target,index);Scene.put(target,"timeGradient",gradient);}}}
}
