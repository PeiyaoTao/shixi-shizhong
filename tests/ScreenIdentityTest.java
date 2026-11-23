package com.foldstandby.clock;
public class ScreenIdentityTest {
    public static void main(String[] args) {
        if(!ScreenIdentity.isVerifiedCover("SM-F971U1",1248,1972))throw new AssertionError("cover");
        if(!ScreenIdentity.isVerifiedCover("SM-F971U1",1972,1248))throw new AssertionError("rotation");
        if(ScreenIdentity.isVerifiedCover("SM-F971U1",2448,1848))throw new AssertionError("inner");
        if(ScreenIdentity.isVerifiedCover("SM-F971U1",1868,1248))throw new AssertionError("window is not physical mode");
        if(ScreenIdentity.isVerifiedCover("other",1248,1972))throw new AssertionError("unknown model");
        System.out.println("PASS: 5 screen identity checks");
    }
}
