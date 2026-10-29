package com.lexinote.nativeapp;
import org.junit.Test;
import static org.junit.Assert.*;
public class LayoutStyleTest {
 @Test public void adaptiveHeaderAndModesKeepLargeType(){
  assertTrue(LayoutStyle.compactHeader(320,1.15f));assertTrue(LayoutStyle.compactHeader(360,1.15f));
  assertFalse(LayoutStyle.compactHeader(412,1f));
  assertTrue(LayoutStyle.modeHeight(320,1.15f)>=56);
  assertTrue(LayoutStyle.modeHeight(412,1f)>=48);
 }
 @Test public void densityChangesSpaceNotTargetSize(){
  assertTrue(LayoutStyle.cardPadding(false)>LayoutStyle.cardPadding(true));
  assertTrue(LayoutStyle.cardGap(false)>LayoutStyle.cardGap(true));
  assertEquals(48,LayoutStyle.TOUCH_MIN);
 }
 @Test public void allAccentPresetsAndCustomColorsStayReadable(){
  int[] colors={0xff10b981,0xff2563eb,0xff6366f1,0xfff59e0b,0xffe11d48,0xff8b5cf6,0xffffffff,0xff000000,0xff888888};
  for(int c:colors){
   assertTrue(LayoutStyle.contrast(LayoutStyle.onColor(c),c)>=4.5);
   assertTrue(LayoutStyle.contrast(LayoutStyle.textAccent(c,0xffffffff),0xffffffff)>=5);
   assertTrue(LayoutStyle.contrast(LayoutStyle.textAccent(c,0xff1e293b),0xff1e293b)>=5);
   assertTrue(LayoutStyle.contrast(LayoutStyle.fillAccent(c),0xffffffff)>=4.5);
  }
 }
}

