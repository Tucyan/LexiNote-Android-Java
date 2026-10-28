package com.lexinote.nativeapp;
import java.util.Locale;
import java.text.Normalizer;
import org.json.*;
final class NotebookSearch {
 static boolean matches(JSONObject v,String query) {
  String q=norm(query); if(q.isEmpty()) return true;
  if(has(v.optString("content"),q)) return true;
  JSONArray tags=v.optJSONArray("tags"); for(int i=0;tags!=null&&i<tags.length();i++) if(has(tags.optString(i),q)) return true;
  JSONArray ms=v.optJSONArray("meanings"); for(int i=0;ms!=null&&i<ms.length();i++) { JSONObject m=ms.optJSONObject(i); if(m==null) continue;
   for(String k:new String[]{"part_of_speech","phonetic","zh_definition","en_definition","example","source","remarks"}) if(has(m.optString(k),q)) return true;
   JSONArray ns=m.optJSONArray("annotations"); for(int j=0;ns!=null&&j<ns.length();j++) { JSONObject n=ns.optJSONObject(j); if(n!=null&&(has(n.optString("content"),q)||has(n.optString("target_text"),q))) return true; }
  } return false;
 }
 static String norm(String s){return s==null?"":Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);}
 static boolean has(String s,String q){return norm(s).contains(q);}
 static String emptyState(boolean f,boolean c){return f?"filtered":c?"parent":"empty";}
}
