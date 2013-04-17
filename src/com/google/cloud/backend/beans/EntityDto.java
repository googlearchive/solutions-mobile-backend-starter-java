/*
 * Copyright (c) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.backend.beans;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.api.users.User;
import com.google.cloud.backend.spi.SecurityChecker;
import com.google.gson.Gson;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A JavaBeans container for CloudEntity.
 *
 */
public class EntityDto {

  private static final Gson GSON = new Gson();

  public static final String PROP_UNINDEXED_PREFIX_MAP = "_map_";

  public static final String PROP_UNINDEXED_PREFIX_LIST = "_list_";

  public static final String PROP_UPDATED_BY = "_updatedBy";

  public static final String PROP_UPDATED_AT = "_updatedAt";

  public static final String PROP_CREATED_BY = "_createdBy";

  public static final String PROP_CREATED_AT = "_createdAt";

  public static final String PROP_KIND_NAME = "_kindName";

  public static final String PROP_OWNER = "_owner";

  private String id;

  private Date createdAt;

  private Date updatedAt;

  private String createdBy;

  private String updatedBy;

  private String kindName;

  private Object properties;

  private String owner;

  /**
   * Creates {@link EntityDto} from Datastore {@link Entity}.
   *
   * @param e
   *          {@link Entity} which the EntityDto will be created from.
   * @return {@link EntityDto} created from the Entity.
   */
  public static EntityDto createFromEntity(Entity e) {

    // create EntityDto instance and set kindName and id
    EntityDto cd = new EntityDto();
    cd.setId(e.getKey().getName());

    // set other metadata
    cd.setKindName(e.getKind());
    cd.setCreatedAt((Date) e.getProperty(PROP_CREATED_AT));
    cd.setCreatedBy((String) e.getProperty(PROP_CREATED_BY));
    cd.setUpdatedAt((Date) e.getProperty(PROP_UPDATED_AT));
    cd.setUpdatedBy((String) e.getProperty(PROP_UPDATED_BY));
    cd.setOwner((String) e.getProperty(PROP_OWNER));

    // set properties
    Map<String, Object> values = new HashMap<String, Object>();
    Map<String, Object> props = e.getProperties();
    for (String propName : props.keySet()) {

      // if the propName starts with "_map:", decode it as a Map
      if (propName.startsWith(PROP_UNINDEXED_PREFIX_MAP)) {
        Text t = (Text) props.get(propName);
        Object v = GSON.fromJson(t.getValue(), Map.class);
        String origPropName = propName.replaceAll(PROP_UNINDEXED_PREFIX_MAP, "");
        values.put(origPropName, v);
        continue;
      }

      // if the propName starts with "_list:", decode it as a List
      if (propName.startsWith(PROP_UNINDEXED_PREFIX_LIST)) {
        Text t = (Text) props.get(propName);
        Object v = GSON.fromJson(t.getValue(), List.class);
        String origPropName = propName.replaceAll(PROP_UNINDEXED_PREFIX_LIST, "");
        values.put(origPropName, v);
        continue;
      }

      // if it's an user prop (does not start with "_"), add it to the map
      if (!propName.startsWith("_")) {
        values.put(propName, props.get(propName));
      }
    }
    cd.setProperties(values);
    return cd;
  }

  /**
   * Returns a {@link Key} of the entity for this CloudEntity.
   *
   * @return {@link Key} of the entity for this CloudEntity.
   */
  public Key readEntityKey(User user) {

    // check if id is not null
    if (this.getId() == null) {
      throw new IllegalStateException("getEntityKey: id is null");
    }

    // create a Key from id and kindName
    return SecurityChecker.getInstance().createKeyWithNamespace(kindName, id, user);
  }

  /**
   * Copies all the property values from this {@link EntityDto} to the specified
   * {@link Entity}.
   *
   * @param e
   */
  @SuppressWarnings("rawtypes")
  public void copyPropValuesToEntity(Entity e) {

    // set meta data
    e.setProperty(PROP_CREATED_AT, this.getCreatedAt());
    e.setProperty(PROP_CREATED_BY, this.getCreatedBy());
    e.setProperty(PROP_UPDATED_AT, this.getUpdatedAt());
    e.setProperty(PROP_UPDATED_BY, this.getUpdatedBy());
    e.setProperty(PROP_KIND_NAME, this.kindName); // used for pros search
    e.setProperty(PROP_OWNER, this.getOwner());

    // set property properties
    Map values = (Map) this.getProperties();
    for (Object key : values.keySet()) {

      // get property name and value
      String propName = (String) key;
      Object val = values.get(key);

      // if the value is List/Map, encode it to JSON and store as Text
      // Otherwise, store it as is
      if (val instanceof Map) {
        Text t = new Text(GSON.toJson(val));
        e.setUnindexedProperty(PROP_UNINDEXED_PREFIX_MAP + propName, t);
      } else if (val instanceof List) {
        Text t = new Text(GSON.toJson(val));
        e.setUnindexedProperty(PROP_UNINDEXED_PREFIX_LIST + propName, t);
      } else {
        e.setProperty(propName, val);
      }
    }
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Date getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Date createdAt) {
    this.createdAt = createdAt;
  }

  public Date getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Date updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public String getKindName() {
    return kindName;
  }

  public void setKindName(String kindName) {
    this.kindName = kindName;
  }

  public Object getProperties() {
    return properties;
  }

  public void setProperties(Object values) {
    this.properties = values;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  @Override
  public String toString() {
    return "EntityDto(" + this.getKindName() + "/" + this.getId() + "): " + properties;
  }

  @Override
  public int hashCode() {
    String s = "" + this.id + this.kindName + this.createdAt + this.createdBy + this.updatedAt
        + this.updatedBy + this.properties.toString() + this.owner;
    return s.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj.hashCode() == this.hashCode();
  }

}
