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

import com.google.appengine.api.prospectivesearch.ProspectiveSearchService;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchServiceFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.cloud.backend.config.StringUtility;
import com.google.gson.Gson;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Utility class with helper functions to decode subscription information and handle
 * Prospective Search API.
 */
public class SubscriptionUtility {

  protected static final String IOS_DEVICE_PREFIX = "ios_";
  protected static final String GCM_KEY_SUBID = "subId";
  protected static final String REQUEST_TYPE_DEVICE_SUB = "deviceSubscriptionRequest";
  protected static final String REQUEST_TYPE_PSI_SUB = "PSISubscriptionRequest";

  /**
   * A key word to indicate "query" type in Prospective Search API subscription id.
   */
  public static final String GCM_TYPEID_QUERY = "query";
  private static final ProspectiveSearchService prosSearch = ProspectiveSearchServiceFactory
      .getProspectiveSearchService();
  private static final Logger log = Logger.getLogger(SubscriptionUtility.class.getName());

  /**
   * An enumeration of supported mobile device type.
   */
  public enum MobileType {
    ANDROID,
    IOS
  }

  /**
   * Gets the mobile type based on the subscription id provided by client.
   *
   * Subscription id is in format of <device token>:GCM_TYPEID_QUERY:<topic>.  Clients prefix
   * device token with "ios_" if the mobile type is "iOS", while Android device
   * token does not have any prefix.
   *
   * @param subId Subscription ID sent by client during query subscription
   * @return Mobile type
   */
  public static MobileType getMobileType(String subId) {
    return (subId.startsWith(IOS_DEVICE_PREFIX)) ? MobileType.IOS :
        MobileType.ANDROID;
  }

  /**
   * Extracts device registration id from subscription id.
   *
   * @param subId Subscription id sent by client during query subscription
   * @return Device registration id
   */
  public static String extractRegId(String subId) {
    if (StringUtility.isNullOrEmpty(subId)) {
      throw new IllegalArgumentException("subId cannot be null or empty");
    }
    String[] tokens = subId.split(":");
    return tokens[0].replaceFirst(IOS_DEVICE_PREFIX, "");
  }

  /**
   * Extracts device registration id from subscription id.
   *
   * @param subId Subscription id sent by client during query subscription
   * @return Device registration id as List
   */
  public static List<String> extractRegIdAsList(String subId) {
    return Arrays.asList(extractRegId(subId));
  }

  /**
   * Constructs subscription id based on registration id and query id.
   *
   * @param regId Registration id provided by the client
   * @param queryId Query id provided by the client
   * @return
   */
  public static String constructSubId(String regId, String queryId) {
    if (StringUtility.isNullOrEmpty(regId) || StringUtility.isNullOrEmpty(queryId)) {
      throw new IllegalArgumentException("regId and queryId cannot be null or empty");
    }

    // ProsSearch subId = <regId>:query:<clientSubId>
    return regId + ":" + GCM_TYPEID_QUERY + ":" + queryId;
  }

  /**
   * Clears Prospective Search API subscription and device subscription entity for listed devices.
   *
   * @param deviceIds A list of device ids for which subscriptions are to be removed
   */
  public static void clearSubscriptionAndDeviceEntity(List<String> deviceIds) {
    DeviceSubscription deviceSubscription = new DeviceSubscription();

    for (String deviceId : deviceIds) {
      Set<String> subIds = deviceSubscription.getSubscriptionIds(deviceId);

      // Delete all subscriptions for the device from Prospective Search API
      for (String subId : subIds) {
        try {
          prosSearch.unsubscribe(QueryOperations.PROS_SEARCH_DEFAULT_TOPIC, subId);
        } catch (IllegalArgumentException e) {
          log.warning("Unsubscribe " + subId + " from PSI encounters error, " + e.getMessage());
        }
      }

      // Remove device from datastore
      deviceSubscription.delete(deviceId);
    }
  }

  /**
   * Clears Prospective Search API subscription and removes device entity for all devices.
   */
  public static void clearAllSubscriptionAndDeviceEntity() {
    // Remove all device subscription and psi subscription
    DeviceSubscription deviceSubscription = new DeviceSubscription();
    deviceSubscription.enqueueDeleteDeviceSubscription();
  }

  /**
   * Enqueues subscription ids in task queue for deletion.
   *
   * @param subIds Psi subscription ids to be deleted
   */
  protected static void enqueueDeletePsiSubscription(String[] subIds) {
    Queue deviceTokenCleanupQueue = QueueFactory.getQueue("subscription-removal");
    deviceTokenCleanupQueue.add(TaskOptions.Builder.withMethod(TaskOptions.Method.POST)
        .url("/admin/push/devicesubscription/delete")
        .param("subIds", new Gson().toJson(subIds, String[].class))
        .param("type", SubscriptionUtility.REQUEST_TYPE_PSI_SUB));
  }
}
