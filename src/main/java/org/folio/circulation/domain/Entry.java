package org.folio.circulation.domain;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

public class Entry {

  private JsonObject entryRepresentation;

  private UUID id;
  private String module;
  private String configName;
  private String code;
  private String description;
  private boolean isDefault;
  private boolean isEnabled;
  private JsonObject value;
  private UUID userId;

  public Entry(JsonObject entryRepresentation, UUID id, String module, String configName, String code, String description, boolean isDefault, boolean isEnabled, JsonObject value, UUID userId) {
    this.entryRepresentation = entryRepresentation;
    this.id = id;
    this.module = module;
    this.configName = configName;
    this.code = code;
    this.description = description;
    this.isDefault = isDefault;
    this.isEnabled = isEnabled;
    this.value = value;
    this.userId = userId;
  }

  public Entry() {

  }


  public JsonObject getValue() {
    return value;
  }

  public static Entry from(JsonObject representation) {
    return new Entry(representation, null,null, null, null, null, false, false, null, null);
  }

  public JsonObject extendedEntry(Entry entry){
    if(entry == null) {
      return null;
    }

    return entry.asJson();
  }

  public JsonObject asJson() {
    return entryRepresentation.copy();
  }
}
