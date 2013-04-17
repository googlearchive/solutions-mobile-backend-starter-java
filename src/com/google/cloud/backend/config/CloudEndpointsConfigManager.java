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
package com.google.cloud.backend.config;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.cloud.backend.spi.EndpointV1;

import java.util.List;

/**
 * Class that manages the Cloud Endpoints configuration stored in App Engine
 * Datastore. By using the special kind GoogleCloudEndpointConfiguration, it can
 * control Endpoints config parameters incl. audiences and clientIds without
 * redeploying the app. Developer can control these parameters from the Admin
 * UI.
 */
public class CloudEndpointsConfigManager {
  private final DatastoreService datastoreService;
  private final MemcacheService memcacheService;

  /**
   * Kind name for storing endpoint configuration.
   */
  public static final String ENDPOINT_CONFIGURATION_KIND = "GoogleCloudEndpointConfiguration";

  private static final String AUDIENCES = "audiences";
  private static final String CLIENT_IDS = "clientIds";

  public CloudEndpointsConfigManager() {
    this(DatastoreServiceFactory.getDatastoreService(),
        MemcacheServiceFactory.getMemcacheService());
  }

  public CloudEndpointsConfigManager(DatastoreService datastoreService, 
      MemcacheService memcacheService) {
    this.datastoreService = datastoreService;
    this.memcacheService = memcacheService;
  }

  /**
   * Configures the Cloud Endpoints exposed from this backend. Specifically, it
   * configures {@link EndpointV1} endpoints. This call overwrites any
   * previously specified settings.
   * 
   * @param clientIds
   *          list of clientIds that will be allowed to make authenticated calls
   *          to this backend.
   * @param audiences
   *          list of audiences that will be allowed to make authenticated calls
   *          to this backend.
   */
  public void setAuthenticationInfo(List<String> clientIds,
      List<String> audiences) {
    Entity config = getEndpointEntity(EndpointV1.class);
    config.setProperty(CLIENT_IDS, clientIds);
    config.setProperty(AUDIENCES, audiences);
    datastoreService.put(config);

    // Google Cloud Endpoints infrastructure caches the configuration in Memcache.
    // In order for the changes to be applied without restart/redeployment
    // they need to be updated not only in Datastore, but also in Memcache.
    memcacheService.put(ENDPOINT_CONFIGURATION_KIND + "." + EndpointV1.class.getName(), config);
  }

  private Entity getEndpointEntity(Class<?> endpointClass) {
    Key key = KeyFactory.createKey(ENDPOINT_CONFIGURATION_KIND,
        endpointClass.getSimpleName());
    try {
      return datastoreService.get(key);
    } catch (EntityNotFoundException e) {
      return new Entity(key);
    }
  }
}