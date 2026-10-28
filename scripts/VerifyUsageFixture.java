package com.lexinote.nativeapp;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.json.*;

/** Verify the real native decoder, domain validator and cross-entity references. No Android or API. */
public final class VerifyUsageFixture {
  static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
  public static void main(String[] paths) throws Exception {
    for(String path:paths) {
      BackupArchive.Prepared archive=BackupArchive.decode(Files.readAllBytes(Paths.get(path)));
      Library library=new Library(archive.backup);
      JSONArray vocab=library.array("vocabularies");
      int words=0,phrases=0,meanings=0,annotations=0,saved=0,temporary=0,messages=0;
      Set<String> vocabularyIds=new HashSet<>();
      for(int i=0;i<vocab.length();i++) {
        JSONObject v=vocab.getJSONObject(i); vocabularyIds.add(v.getString("id"));
        if(v.getString("type").equals("word")) words++; else if(v.getString("type").equals("phrase")) phrases++;
        check(!Instant.parse(v.getString("updated_at")).isBefore(Instant.parse(v.getString("created_at"))),"Vocabulary time order");
        JSONArray ms=v.getJSONArray("meanings"); meanings+=ms.length();
        for(int j=0;j<ms.length();j++) {
          JSONObject m=ms.getJSONObject(j);
          check(!m.getString("zh_definition").isEmpty()&&!m.getString("en_definition").isEmpty()&&!m.getString("example").isEmpty(),"Empty definition/example");
          annotations+=m.getJSONArray("annotations").length();
        }
      }
      check(words==80&&phrases==40,"Expected 80 words and 40 phrases");
      check(meanings==128&&annotations==12,"Meaning/annotation count mismatch");
      check(library.array("notebooks").length()==8,"Notebook count mismatch");
      for(int i=0;i<library.array("notebooks").length();i++) {
        JSONObject n=library.array("notebooks").getJSONObject(i);
        JSONArray ids=n.getJSONArray("vocabulary_ids");
        for(int j=0;j<ids.length();j++) check(vocabularyIds.contains(ids.getString(j)),"Dangling notebook reference");
      }
      Set<String> conversationIds=new HashSet<>();
      for(int i=0;i<library.array("conversations").length();i++) {
        JSONObject c=library.array("conversations").getJSONObject(i),meta=c.getJSONObject("metadata");
        conversationIds.add(c.getString("id"));
        if(meta.getString("retention").equals("saved")) saved++;
        if(meta.getString("retention").equals("temporary")) temporary++;
        JSONArray ms=c.getJSONArray("ai_messages"); messages+=ms.length();
        check(!meta.getString("title").isEmpty(),"Missing conversation title");
        check(!Instant.parse(meta.getString("last_used_at")).isBefore(Instant.parse(ms.getJSONObject(ms.length()-1).getString("created_at"))),"Conversation time order");
      }
      check(saved==8&&temporary==5&&messages==54,"Conversation retention/messages mismatch");
      JSONArray history=library.array("recent_queries"); check(history.length()==10,"Recent queries mismatch");
      for(int i=0;i<history.length();i++) {
        JSONObject h=history.getJSONObject(i);
        check(conversationIds.contains(h.getString("id")),"Recent query missing its conversation");
        check(vocabularyIds.contains(h.getJSONObject("query_results").getString("local_vocabulary_id")),"Query missing vocabulary");
      }
      check(archive.assets.isEmpty(),"Unexpected images");
      check(!library.snapshot().getJSONObject("settings").has("providers"),"Provider credentials must not be portable");
      System.out.println("PASS "+Paths.get(path).getFileName()+": 80 words,40 phrases,128 meanings,12 annotations,8 notebooks,10 recent queries,5 temporary,8 saved,54 messages");
    }
  }
}
