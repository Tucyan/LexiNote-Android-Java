package com.lexinote.nativeapp;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class OfflineDictionaryTest {
  @Test public void independentDefinitionsKeepTheirLanguageAndProvenance() throws Exception {
    JSONObject result = OfflineDictionary.toResult("bank", "bæŋk", "n. a financial institution\\nn. sloping land", "n. 银行\\nn. 河岸");
    assertEquals("ecdict", result.getString("lookup_origin"));
    assertFalse(result.getJSONObject("field_meta").getJSONObject("word").getBoolean("is_ai_generated"));
    JSONArray meanings = result.getJSONArray("meanings");
    assertEquals(4, meanings.length());
    assertTrue(meanings.getJSONObject(0).has("zh_definition"));
    assertFalse(meanings.getJSONObject(0).has("en_definition"));
    assertTrue(meanings.getJSONObject(2).has("en_definition"));
    assertFalse(meanings.getJSONObject(2).has("zh_definition"));
    assertFalse(meanings.getJSONObject(2).getJSONObject("field_meta").getJSONObject("en_definition").getBoolean("is_ai_generated"));
  }

  @Test public void emptyFieldsAndPhraseAreValid() throws Exception {
    JSONObject result = OfflineDictionary.toResult("look up", "", "", "查阅");
    assertEquals("phrase", result.getString("type"));
    assertFalse(result.getJSONObject("field_meta").has("phonetic"));
    assertEquals(1, result.getJSONArray("meanings").length());
    assertEquals("查阅", result.getJSONArray("meanings").getJSONObject(0).getString("zh_definition"));
  }
}
