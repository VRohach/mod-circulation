package org.folio.circulation.domain;

import io.vertx.core.json.JsonObject;

public class TimeZoneConfig {
  private static final String VALUE_KEY = "value";

  private String value;

  TimeZoneConfig(JsonObject jsonObject) {
    value = jsonObject.getString(VALUE_KEY);
  }

  public String getValue() {
    return value;
  }
}
