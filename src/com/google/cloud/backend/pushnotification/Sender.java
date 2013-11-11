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

import com.google.cloud.backend.config.StringUtility;

import javapns.communication.exceptions.CommunicationException;
import javapns.communication.exceptions.KeystoreException;
import javapns.devices.Device;
import javapns.devices.Devices;
import javapns.devices.exceptions.InvalidDeviceTokenFormatException;
import javapns.devices.implementations.basic.BasicDevice;
import javapns.notification.AppleNotificationServer;
import javapns.notification.AppleNotificationServerBasicImpl;
import javapns.notification.Payload;
import javapns.notification.PushNotificationManager;
import javapns.notification.PushNotificationPayload;
import javapns.notification.PushedNotification;
import javapns.notification.PushedNotifications;

import org.json.JSONException;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sender encapsulates sending push notifications.
 */
class Sender {
  private static final Logger log = Logger.getLogger(Sender.class.getName());
  private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
  private PushNotificationManager pushManager = new PushNotificationManager();
  private Object keystore;
  private String password;
  private boolean production;
  private boolean isConnected = false;
  Method processedFailedNotificationsMethod = null;

  /**
   * @constructor
   *
   * @param keystore a keystore containing a private key and the APNS certificate
   * @param password the keystore's password.
   * @param production true to use Apple's production servers, false to use the sandbox servers.
   */
  public Sender(byte[] keystore, String password, boolean production) {
    this.keystore = keystore;
    this.password = password;
    this.production = production;

    try {
      processedFailedNotificationsMethod =
          pushManager.getClass().getDeclaredMethod("processedFailedNotifications");
      processedFailedNotificationsMethod.setAccessible(true);
    } catch (NoSuchMethodException e) {
      log.log(Level.SEVERE, "Incompatible JavaPNS library.", e);
    } catch (SecurityException e) {
      log.log(Level.SEVERE, "This code requires reflection permission.", e);
    }
  }

  private void initializeConnection() throws KeystoreException, CommunicationException {
    AppleNotificationServer server = new AppleNotificationServerBasicImpl(
        keystore, password, production);

    pushManager.initializeConnection(server);
    isConnected = true;
  }

  /**
   * Stop connection and closes the socket
   *
   * @throws CommunicationException thrown if an error occurred while communicating with the target
   *         server even after a few retries.
   * @throws KeystoreException thrown if an error occurs with using the certificate.
   */
  public void stopConnection() throws CommunicationException, KeystoreException {
    pushManager.stopConnection();
    isConnected = false;
  }

  /**
   * Sends an alert notification to a list of devices.
   *
   * @param alertMessage alert to be sent as a push notification
   * @param deviceTokens the list of tokens for devices to which the notifications should be sent 
   * @return a list of pushed notifications that contain details on transmission results.
   * @throws CommunicationException thrown if an error occurred while communicating with the target
   *         server even after a few retries.
   * @throws KeystoreException thrown if an error occurs with using the certificate.
   */
  public PushedNotifications sendAlert(String alertMessage, String[] deviceTokens)
      throws CommunicationException, KeystoreException {

    log.info("Sending alert='" + alertMessage + "' to " + deviceTokens.length
        + " devices started at " + dateFormat.format(new Date()) + ".");
    PushedNotifications notifications =
        sendPayload(PushNotificationPayload.alert(alertMessage), deviceTokens);

    log.info("Sending alert='" + alertMessage + "' to " + deviceTokens.length
        + " devices completed at " + dateFormat.format(new Date()) + ".");

    return notifications;
  }

  /**
   * Sends a payload to a list of devices. 
   *
   * @param payload preformatted payload to be sent as a push notification
   * @param deviceTokens the list of tokens for devices to which the notifications should be sent 
   * @return a list of pushed notifications that contain details on transmission results.
   * @throws CommunicationException thrown if an error occurred while communicating with the target
   *         server even after a few retries.
   * @throws KeystoreException thrown if an error occurs with using the certificate.
   */
  public PushedNotifications sendPayload(Payload payload, String[] deviceTokens)
      throws CommunicationException, KeystoreException {
    PushedNotifications notifications = new PushedNotifications();

    if (payload == null) {
      return notifications;
    }

    try {
      if (!isConnected) {
        initializeConnection();
      }

      List<Device> deviceList = Devices.asDevices(deviceTokens);
      notifications.setMaxRetained(deviceList.size());
      for (Device device : deviceList) {
        try {
          BasicDevice.validateTokenFormat(device.getToken());
          PushedNotification notification = pushManager.sendNotification(device, payload, false);
          notifications.add(notification);
        } catch (InvalidDeviceTokenFormatException e) {
          notifications.add(new PushedNotification(device, payload, e));
        }
      }
    } catch (CommunicationException e) {
      stopConnection();
      throw e;
    }
    return notifications;
  }

  /**
   * Create a payload to be sent to client device.
   *
   * @param alertMessage Message that is visible to end user
   * @param hiddenMessage Message that is invisible to end user
   * @return a push notification payload to send to client device
   */
  public PushNotificationPayload createPayload(String alertMessage,
      String hiddenMessage) {
    if (StringUtility.isNullOrEmpty(alertMessage) || StringUtility.isNullOrEmpty(hiddenMessage)) {
      throw new IllegalArgumentException("Input arguments cannot be a null or an empty String");
    }

    PushNotificationPayload payload = new PushNotificationPayload();

    try {
      payload.addAlert(alertMessage);
      payload.addCustomDictionary("hiddenMessage", hiddenMessage);
    } catch (JSONException e) {
      log.warning(e.getClass().toString() + " " + e.getMessage());
    }

    return payload;
  }

  /**
   * Read and process any pending error-responses.
   */
  public void processedPendingNotificationResponses() {
    log.log(Level.INFO, "Processing sent notifications.");

    if (processedFailedNotificationsMethod == null) {
      return;
    }

    try {
      processedFailedNotificationsMethod.invoke(pushManager);
    } catch (Exception e) {
      // Catching all exception as the method requires handling 3+ reflection related exceptions
      // and 2+ JavaPNS exceptions. And there is nothing much that can be done when any of them
      // happens other than logging the exception.
      log.log(Level.WARNING, "Processing failed notifications failed", e);
    }
  }
}
