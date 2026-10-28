package com.lexinote.nativeapp;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
public class NotebookSearchTest {
 @Test public void searchesOnlyBusinessValues() throws Exception {
  JSONObject v = new JSONObject("{id:'source',content:'derive',created_at:'2026-10-28',tags:['study'],meanings:[{part_of_speech:'verb',zh_definition:'得到',en_definition:'obtain',example:'derive from',source:'book',remarks:'note',annotations:[{content:'comment',target_text:'fragment'}]}]}");
  for(String s : new String[]{"derive","verb","得到","obtain","from","book","note","comment","fragment","study"}) assertTrue(s,NotebookSearch.matches(v,s));
  for(String s : new String[]{"source","2026-10-28","created_at","annotations"}) assertFalse(s,NotebookSearch.matches(v,s));
 }
 @Test public void normalizesWidthAndWhitespace() throws Exception {
  JSONObject v = new JSONObject().put("content", "ＡＢＣ  def");
  assertTrue(NotebookSearch.matches(v, "abc　 def"));
 }
 @Test public void distinguishesEmptyStates() {
  assertEquals("filtered",NotebookSearch.emptyState(true,true));
  assertEquals("parent",NotebookSearch.emptyState(false,true));
  assertEquals("empty",NotebookSearch.emptyState(false,false));
 }
}
