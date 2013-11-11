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
package com.google.cloud.backend.pushnotification;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.gson.Gson;

import java.util.List;

/**
 * Push Notification coordinator. It uses Task Queues for push notification tasks, pre-processing
 * tasks and clean up of invalid or inactive device tokens.
 *
 *  It can be used from either front-end instances or back-end instances. In particular it can be
 * called from user request handlers in front-ends and backends instances.
 */
public class Utility {

  /**
   * @constructor Private constructor as this is a utility class
   */
  private Utility() {}

  /**
   * Enqueues specified push alert to one device
   *
   * @param alertMessage alert message to be sent
   * @param deviceToken device token to which the alert is addressed
   */
  public static void enqueuePushAlert(String alertMessage, String deviceToken) {
    if (deviceToken == null) {
      throw new IllegalArgumentException("devicetoken cannot be null");
    }
    enqueuePushAlert(alertMessage, new String[] {deviceToken});
  }

  /**
   * Enqueues specified push alert to an array of devices
   *
   * @param alertMessage alert message to be sent
   * @param deviceTokens array of device tokens to which the alert is addressed
   */
  public static void enqueuePushAlert(String alertMessage, String[] deviceTokens) {
    if (alertMessage == null) {
      throw new IllegalArgumentException("alertMessage cannot be null");
    }

    if (deviceTokens == null) {
      throw new IllegalArgumentException("deviceTokens cannot be null");
    }

    enqueuePushAlertToDevices(alertMessage, new Gson().toJson(deviceTokens));
  }

  /**
   * Enqueues specified push alert to a list of devices
   *
   * @param alertMessage alert message to be sent
   * @param deviceTokens list of device tokens to which the alert is addressed
   */
  public static void enqueuePushAlert(String alertMessage, List<String> deviceTokens) {
    if (alertMessage == null) {
      throw new IllegalArgumentException("alertMessage cannot be null");
    }

    if (deviceTokens == null) {
      throw new IllegalArgumentException("deviceTokens cannot be null");
    }

    enqueuePushAlertToDevices(alertMessage, new Gson().toJson(deviceTokens));
  }

  private static void enqueuePushAlertToDevices(String alertMessage, String devicesAsJson) {
    Queue notificationQueue = QueueFactory.getQueue("notification-delivery");
    notificationQueue.add(TaskOptions.Builder.withMethod(TaskOptions.Method.PULL)
        .param("alert", alertMessage)
        .param("devices", devicesAsJson));
  }

  static void enqueueRemovingDeviceTokens(List<String> deviceTokens) {
    Queue deviceTokenCleanupQueue = QueueFactory.getQueue("notification-device-token-cleanup");
    deviceTokenCleanupQueue.add(TaskOptions.Builder.withMethod(TaskOptions.Method.POST)
        .url("/admin/push/device/cleanup")
        .param("devices", new Gson().toJson(deviceTokens)));
  }
}
