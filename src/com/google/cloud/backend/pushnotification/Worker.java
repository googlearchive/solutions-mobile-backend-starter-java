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

import com.google.appengine.api.LifecycleManager;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.apphosting.api.ApiProxy.ApiDeadlineExceededException;
import com.google.cloud.backend.config.BackendConfigManager;
import com.google.gson.Gson;

import javapns.Push;
import javapns.communication.exceptions.CommunicationException;
import javapns.communication.exceptions.KeystoreException;
import javapns.notification.PushNotificationPayload;
import javapns.notification.PushedNotification;
import javapns.notification.PushedNotifications;
import javapns.notification.ResponsePacket;

import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker that processes a batch of tasks from a queue. It should not be called from a front end
 * thread handling user requests, but rather from a resident backend or a background thread from a
 * dynamic backend.
 *
 */
class Worker {
  private static final Logger log = Logger.getLogger(Worker.class.getName());

  private Queue queue;
  private BackendConfigManager backendConfigManager;

  /**
   * @constructor
   *
   * @param queue Task queue that needs to be processed
   */
  protected Worker(Queue queue) {
    this.queue = queue;
    this.backendConfigManager = new BackendConfigManager();
  }

  /**
   * Process a batch of tasks from the queue.
   *
   * @return false if leasing tasks didn't return any tasks or if the instance is shutting down;
   *         true if otherwise
   */
  protected boolean processBatchOfTasks() {
    List<TaskHandle> tasks = leaseTasks();
    if (tasks == null || tasks.size() == 0) {
      return false;
    }

    processTasks(tasks);

    return deleteTasks(tasks);
  }

  private List<TaskHandle> leaseTasks() {
    List<TaskHandle> tasks;
    for (int attemptNo = 1; !LifecycleManager.getInstance().isShuttingDown(); attemptNo++) {
      try {
        tasks = queue.leaseTasks(10, TimeUnit.MINUTES, 1000);
        return tasks;
      } catch (TransientFailureException e) {
        log.warning("TransientFailureException when leasing tasks from queue '"
            + queue.getQueueName() + "'");
      } catch (ApiDeadlineExceededException e) {
        log.warning("ApiDeadlineExceededException when when leasing tasks from queue '"
            + queue.getQueueName() + "'");
      }
      if (!backOff(attemptNo)) {
        return null;
      }
    }
    return null;
  }

  private boolean deleteTasks(List<TaskHandle> tasks) {
    for (int attemptNo = 1;; attemptNo++) {
      try {
        queue.deleteTask(tasks);
        break;
      } catch (TransientFailureException e) {
        log.warning("TransientFailureException when deleting tasks from queue '"
            + queue.getQueueName() + "'");
      } catch (ApiDeadlineExceededException e) {
        log.warning("ApiDeadlineExceededException when deleting tasks from queue '"
            + queue.getQueueName() + "'");
      }
      if (!backOff(attemptNo)) {
        return false;
      }
    }
    return true;
  }

  private void processTasks(List<TaskHandle> tasks) {
    for (TaskHandle task : tasks) {
      String hiddenMessage = null;
      String[] deviceTokens = null;
      List<Entry<String, String>> params = null;
      try {
        params = task.extractParams();
      } catch (UnsupportedEncodingException e) {
        log.warning("Ignoring a task with invalid encoding. This indicates a bug.");
      } catch (UnsupportedOperationException e) {
        log.warning("Ignoring a task with invalid payload. This indicates a bug.");
      }
      for (Entry<String, String> param : params) {
        String paramKey = param.getKey();
        String paramVal = param.getValue();
        if (paramKey.equals("alert")) {
          hiddenMessage = paramVal;
        } else if (paramKey.equals("devices")) {
          deviceTokens = new Gson().fromJson(paramVal, String[].class);
        }
      }

      if (hiddenMessage == null || hiddenMessage.isEmpty()) {
        log.warning("Ignoring task with empty alert message");
        continue;
      }

      if (deviceTokens == null || deviceTokens.length == 0) {
        log.warning("Ignoring task with no device tokens specified");
        continue;
      }

      sendAlert("You receive a message.", hiddenMessage, deviceTokens);
    }
  }

  private PushNotificationPayload createPayload(String alertMessage,
                                                String hiddenMessage) {
    PushNotificationPayload payload = new PushNotificationPayload();

    try {
      payload.addAlert(alertMessage);
      payload.addCustomDictionary("hiddenMessage", hiddenMessage);
    } catch (JSONException e) {
      log.warning(e.getClass().toString() + " " + e.getMessage());
    }

    return payload;
  }

  private void sendAlert(String alertMessage, String hiddenMessage, String[] deviceTokens) {
    InputStream cert = null;
    try {
      log.info("Sending push alert");
      cert = this.backendConfigManager.getPushNotificationCertificate();
      if (cert == null) {
        log.warning("APNS certification is not available for sending push notification.");
        return;
      }

      PushNotificationPayload payload = createPayload(alertMessage, hiddenMessage);
      PushedNotifications notifications = Push.payload(payload, cert,
        this.backendConfigManager.getPushCertPassword(),
        Configuration.USE_PRODUCTION_APNS_SERVICE, deviceTokens);

      List<String> invalidTokens = new ArrayList<String>();

      for (PushedNotification notification : notifications) {

        if (notification.isSuccessful()) {
          log.info("Notification sent successfully to APNS for device: "
              + notification.getDevice().getToken());
        } else {
          // Log information about the problem
          log.log(Level.WARNING, "Notification wasn't successful.", notification.getException());

          // check if there is an error-response packet returned by
          // APNS
          ResponsePacket theErrorResponse = notification.getResponse();
          if (theErrorResponse != null) {
            if (theErrorResponse.getStatus() == 8) {
              String invalidToken = notification.getDevice().getToken();
              invalidTokens.add(invalidToken);
            }
            log.warning("Error response packet: " + theErrorResponse.getMessage());
          }
        }

        if (invalidTokens.size() > 0) {
          Utility.enqueueRemovingDeviceTokens(invalidTokens);
        }
      }
      log.info("Push alert sent successfully");
    } catch (CommunicationException e) {
      log.log(
          Level.WARNING, "Sening push alert failed with CommunicationException:" + e.toString(), e);
    } catch (KeystoreException e) {
      log.log(Level.WARNING, "Sening push alert failed with KeystoreException:" + e.toString(), e);
    }
    finally {
      if (cert != null) {
        try {
          cert.close();
        } catch (IOException e) {
          log.warning(e.getClass().toString() + " " + e.getMessage());
        }
      }
    }
  }

  private boolean backOff(int attemptNo) {
    // exponential back off between 2 seconds and 64 seconds with jitter 0..1000 ms
    attemptNo = Math.min(6, attemptNo);
    int backOffTimeInSeconds = 1 << attemptNo;
    try {
      Thread.sleep(backOffTimeInSeconds * 1000 + (int) (Math.random() * 1000));
    } catch (InterruptedException e) {
      return false;
    }
    return true;
  }
}
