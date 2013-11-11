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

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.QueryResultIterable;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.cloud.backend.config.StringUtility;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Handles the persistence of each device and Perspective Search API subscription.
 */
public class DeviceSubscription {

  private final MemcacheService memcacheService;
  private final DatastoreService datastoreService;
  private final Gson gson;

  static final String PROPERTY_DEVICE_TYPE = "DeviceType";
  static final String PROPERTY_ID = "DeviceID";
  static final String PROPERTY_SUBSCRIPTION_IDS = "SubscriptionIDs";
  static final int BATCH_DELETE_SIZE = 250;

  /**
   * Time stamp property name of the Device Subscription entity.
   */
  public static final String PROPERTY_TIMESTAMP = "TimeStamp";
  private static final Type setType = new TypeToken<Set<String>>() {}.getType();

  /**
   * Device Subscription entity name.
   */
  public static final String SUBSCRIPTION_KIND = "_DeviceSubscription";

  /**
   * Default constructor for DeviceSubscription class.
   */
  public DeviceSubscription() {
    this(DatastoreServiceFactory.getDatastoreService(),
        MemcacheServiceFactory.getMemcacheService());
  }

  /**
   * Constructor for DeviceSubscription class.
   *
   * @param datastoreService AppEngine datastore service
   * @param memcacheService AppEngine memcache service
   */
  public DeviceSubscription(DatastoreService datastoreService, MemcacheService memcacheService) {
    if (datastoreService == null || memcacheService == null) {
      throw new IllegalArgumentException("datastoreService and memcacheService cannot be null.");
    }

    this.datastoreService = datastoreService;
    this.memcacheService = memcacheService;
    this.gson = new Gson();
  }

  /**
   * Returns an entity with device subscription information from memcache or datastore based on the
   *     provided deviceId.
   *
   * @param deviceId A unique device identifier
   * @return an entity with device subscription information; or null when no corresponding
   *         information found
   */
  public Entity get(String deviceId) {
    if (StringUtility.isNullOrEmpty(deviceId)) {
      throw new IllegalArgumentException("DeviceId cannot be null or empty");
    }
    Key key = getKey(deviceId);
    Entity entity = (Entity) this.memcacheService.get(key);

    // Get from datastore if unable to get data from cache
    if (entity == null) {
      try {
        entity = this.datastoreService.get(key);
      } catch (EntityNotFoundException e) {
        return null;
      }
    }

    return entity;
  }

  /**
   * Returns a set of subscriptions subscribed from the device.
   *
   * @param deviceId A unique device identifier 
   */
  public Set<String> getSubscriptionIds(String deviceId) {
    if (StringUtility.isNullOrEmpty(deviceId)) {
      return new HashSet<String>();
    }
    Entity deviceSubscription = get(deviceId);

    if (deviceSubscription == null) {
      return new HashSet<String>();
    }

    String subscriptionString = (String) deviceSubscription.getProperty(PROPERTY_SUBSCRIPTION_IDS);
    if (StringUtility.isNullOrEmpty(subscriptionString)) {
      return new HashSet<String>();
    }

    return this.gson.fromJson(subscriptionString, setType);
  }

  /**
   * Creates an entity to persist a subscriptionID subscribed by a specific device.
   *
   * @param deviceType device type according to platform
   * @param deviceId unique device identifier
   * @param subscriptionId subscription identifier subscribed by this specific device
   * @return a datastore entity
   */
  public Entity create(SubscriptionUtility.MobileType deviceType, String deviceId,
      String subscriptionId) {
    if (StringUtility.isNullOrEmpty(deviceId) || StringUtility.isNullOrEmpty(subscriptionId)) {
      return null;
    }

    Key key;
    String newDeviceId = SubscriptionUtility.extractRegId(deviceId);
    Entity deviceSubscription = get(newDeviceId);
    // Subscriptions is a "set" instead of a "list" to ensure uniqueness of each subscriptionId
    // for a device
    Set<String> subscriptions = new HashSet<String>();

    if (deviceSubscription == null) {
      // Create a brand new one
      key = getKey(newDeviceId);
      deviceSubscription = new Entity(key);
      deviceSubscription.setProperty(PROPERTY_ID, newDeviceId);
      deviceSubscription.setProperty(PROPERTY_DEVICE_TYPE, deviceType.toString());
    } else {
      key = deviceSubscription.getKey();

      // Update the existing subscription list
      String ids = (String) deviceSubscription.getProperty(PROPERTY_SUBSCRIPTION_IDS);
      if (!StringUtility.isNullOrEmpty(ids)) {
        subscriptions = this.gson.fromJson(ids, setType);
      }
    }

    // Update entity subscription property and save only when subscriptionId has successfully added
    // to the subscriptions "set".  If a subscriptionId is a duplicate of an existing subscription
    // in the set, we don't save this duplicated value into the entity.
    if (subscriptions.add(subscriptionId)) {
      deviceSubscription.setProperty(PROPERTY_SUBSCRIPTION_IDS, this.gson.toJson(subscriptions));
      Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
      deviceSubscription.setProperty(PROPERTY_TIMESTAMP, time.getTime());

      this.datastoreService.put(deviceSubscription);
      this.memcacheService.put(key, deviceSubscription);
    }

    return deviceSubscription;
  }

  /**
   * Deletes an entity corresponding to the provided deviceId.
   *
   * @param deviceId the device id for which all subscription information are to be deleted
   */
  public void delete(String deviceId) {
    if (StringUtility.isNullOrEmpty(deviceId)) {
      throw new IllegalArgumentException("deviceId cannot be null or empty.");
    }

    Key key = getKey(deviceId);
    this.datastoreService.delete(key);
    this.memcacheService.delete(key);
  }

  private void deleteInBatch(List<Key> keys) {
    this.memcacheService.deleteAll(keys);
    this.datastoreService.delete(keys);
  }

  /**
   * Deletes all device subscription entities continuously using task push queue.
   *
   * @param time Threshold time before which entities created will be deleted. If time is null,
   *             current time is used and set as Threshold time.
   * @param cursor Query cursor indicates last query result set position
   */
  protected void deleteAllContinuously(Date time, String cursor) {
    if (time == null) {
      time = Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime();
    }

    Query.FilterPredicate timeFilter = new Query.FilterPredicate(PROPERTY_TIMESTAMP,
        FilterOperator.LESS_THAN_OR_EQUAL, time);
    QueryResultIterable<Entity> entities;
    List<Key> keys = new ArrayList<Key> ();
    List<String> subIds = new ArrayList<String> ();
    Query queryAll;

    queryAll = new Query(DeviceSubscription.SUBSCRIPTION_KIND).setFilter(timeFilter);
    FetchOptions options = FetchOptions.Builder.withLimit(BATCH_DELETE_SIZE);
    if (!StringUtility.isNullOrEmpty(cursor)) {
      options.startCursor(Cursor.fromWebSafeString(cursor));
    }

    entities = this.datastoreService.prepare(queryAll).asQueryResultIterable(options);
    if (entities != null && entities.iterator() != null) {
      for (Entity entity : entities) {
        keys.add(entity.getKey());
        String[] ids = new Gson().fromJson((String) entity.getProperty(PROPERTY_SUBSCRIPTION_IDS),
            String[].class);
        subIds.addAll(Arrays.asList(ids));
      }
    }

    if (keys.size() > 0) {
      deleteInBatch(keys);
      enqueueDeleteDeviceSubscription(time, entities.iterator().getCursor().toWebSafeString());
    }
    if (subIds.size() > 0) {
      deletePsiSubscriptions(subIds);
    }
  }

  /**
   * Delete Prospective Search Api subscriptions in batches.
   *
   * @param subIds A list of Prospective Search Api subscription ids to be deleted.
   */
  private void deletePsiSubscriptions(List<String> subIds) {
    int size;
    int current = 0;
    do {
      size = Math.min(subIds.size() - current, BATCH_DELETE_SIZE);
      List<String> newList = new ArrayList<String>(subIds.subList(current, current + size));
      current += size;
      SubscriptionUtility.enqueueDeletePsiSubscription(newList.toArray(new String[size]));
    } while (subIds.size() > current);
  }

  /**
   * Enqueues device subscription entity to be deleted.
   *
   * @param time Threshold time before which entities created will be deleted
   * @param cursor Query cursor indicates query result position
   */
  protected void enqueueDeleteDeviceSubscription(Date time, String cursor) {
    Queue deviceTokenCleanupQueue = QueueFactory.getQueue("subscription-removal");
    deviceTokenCleanupQueue.add(TaskOptions.Builder.withMethod(TaskOptions.Method.POST)
        .url("/admin/push/devicesubscription/delete")
        .param("timeStamp", new Gson().toJson(time, Date.class))
        .param("cursor", cursor)
        .param("type", SubscriptionUtility.REQUEST_TYPE_DEVICE_SUB));
  }

  /**
   * Enqueues device subscription entity to be deleted with initializing arguments.
   *
   * @param time Threshold time before which entities created will be deleted
   * @param cursor Query cursor indicates query result position
   */
  protected void enqueueDeleteDeviceSubscription() {
    enqueueDeleteDeviceSubscription(null, "");
  }

  /**
   * Gets a key for subscription kind entity based on device id.
   *
   * @param deviceId A unique device identifier
   */
  protected Key getKey(String deviceId) {
    if (StringUtility.isNullOrEmpty(deviceId)) {
      throw new IllegalArgumentException("deviceId cannot be null or empty");
    } else {
      return KeyFactory.createKey(SUBSCRIPTION_KIND, deviceId);
    }
  }
}
