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

package com.google.cloud.backend.spi;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.cloud.backend.spi.BlobEndpoint.BlobAccessMode;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encapsulates a Datastore entity that represents blob's metadata, such as the ownerId and
 * accessMode.
 */
class BlobMetadata {
  private static final Logger logger = Logger.getLogger(BlobMetadata.class.getSimpleName());
  public static final String ENTITY_KIND = "ObjectsInGCS";
  private static final String ENTITY_OWNER_PROPERTY = "Owner";
  private static final String ENTITY_ACCESS_MODE_PROPERTY = "AccessMode";
  private Entity entity = null;

  /**
   * Constructs a BlobMetadata wrapper for an existing metadata entity.
   *
   * @param metadataEntity Datastore entity representing blob's metadata.
   */
  public BlobMetadata(Entity metadataEntity) {
    if (metadataEntity == null) {
      throw new IllegalArgumentException("metadataEntity cannot be null");
    }
    entity = metadataEntity;
  }

  /**
   * Constructs a BlobMetadata for a new blob.
   *
   * @param gcsCanonicalizedResourcePath canonicalized resource path for this blob, e.g.,
   *        "/bucket/path/objectName".
   * @param accessMode access mode for this blob.
   * @param ownerId owner of this blob.
   */
  public BlobMetadata(
      String gcsCanonicalizedResourcePath, BlobAccessMode accessMode, String ownerId) {
    entity = new Entity(ENTITY_KIND, gcsCanonicalizedResourcePath);
    entity.setProperty(ENTITY_OWNER_PROPERTY, ownerId);
    entity.setUnindexedProperty(ENTITY_ACCESS_MODE_PROPERTY, (long) accessMode.ordinal());
  }

  /**
   * Gets the underlying Datastore entity.
   */
  public Entity getEntity() {
    return entity;
  }

  /**
   * Gets Datastore entity key for a given canonicalizedResource.
   */
  public static Key getKey(String canonicalizedResource) {
    return KeyFactory.createKey(ENTITY_KIND, canonicalizedResource);
  }

  /**
   * Gets the owner id for this blob.
   */
  public String getOwnerId() {
    return (String) entity.getProperty(ENTITY_OWNER_PROPERTY);
  }

  /**
   * Gets the owner id for this blob.
   */
  public BlobAccessMode getAccessMode() {
    Long accessMode = (Long) entity.getProperty(ENTITY_ACCESS_MODE_PROPERTY);
    if (accessMode == null || accessMode < 0 || accessMode >= BlobAccessMode.values().length) {
      logger.log(Level.WARNING, "Invalid value of accessMode:" + accessMode);
      // Default to private.
      return BlobAccessMode.PRIVATE;
    } else {
      return BlobAccessMode.values()[accessMode.intValue()];
    }
  }
}
