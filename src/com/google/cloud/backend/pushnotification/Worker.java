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
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.memcache.ErrorHandlers;
import com.google.appengine.api.memcache.Expiration;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.ApiDeadlineExceededException;
import com.google.cloud.backend.config.BackendConfigManager;
import com.google.gson.Gson;

import javapns.communication.exceptions.CommunicationException;
import javapns.communication.exceptions.KeystoreException;
import javapns.notification.PushNotificationPayload;
import javapns.notification.PushedNotification;
import javapns.notification.PushedNotifications;
import javapns.notification.ResponsePacket;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker that processes a batch of tasks from a queue. It should not be called from a front end
 * thread handling user requests, but rather from a resident backend or a background thread from a
 * dynamic backend.
 */
class Worker {
  static final int BATCH_NOTIFICATION_PROCESS_SIZE = 1000;

  private static final Logger log = Logger.getLogger(Worker.class.getName());
  private static final MemcacheService cache = MemcacheServiceFactory.getMemcacheService();
  private static final DatastoreService dataStore = DatastoreServiceFactory.getDatastoreService();
  private static final BackendConfigManager backendConfigManager = new BackendConfigManager();
  private Sender notificationSender = new Sender(
      backendConfigManager.getPushNotificationCertificateBytes(),
      backendConfigManager.getPushCertPassword(),
      Configuration.USE_PRODUCTION_APNS_SERVICE);
  static final String PROCESSED_NOTIFICATION_TASKS_ENTITY_KIND = "_ProcessedNotificationsTasks";

  private Queue queue;

  /**
   * @constructor
   *
   * @param queue Task queue that needs to be processed
   */
  protected Worker(Queue queue) {
    this.queue = queue;

    cache.setErrorHandler(ErrorHandlers.getConsistentLogAndContinue(Level.INFO));
  }

  /**
   * Process a batch of tasks from the queue.
   * @result true if tasks were processed. False if no tasks were leased.
   */
  protected boolean processBatchOfTasks() {
    List<TaskHandle> tasks = leaseTasks();

    if (tasks == null || tasks.size() == 0) {
      return false;
    }

    processLeasedTasks(tasks);
    return true;
  }

  private List<TaskHandle> leaseTasks() {
    List<TaskHandle> tasks;
    for (int attemptNo = 1; !LifecycleManager.getInstance().isShuttingDown(); attemptNo++) {
      try {
        /*
         * Each task may include many device tokens. For example when a task is enqueued by
         * PushPreprocessingServlet it can contain up to BATCH_NOTIFICATION_PROCESS_SIZE (e.g., 250)
         * tokens.
         *
         * When choosing the number of tasks to lease and the duration of lease, make sure that the
         * total number of notifications (= the_number_of_leased_tasks multiplied by
         * the_number_of_device_tokens_in_one_task) can be sent to APNS in the time shorter than the
         * lease time.
         *
         * Leave some buffer for handling transient errors, e.g., when deleting tasks. Leasing
         * several hundreds of tasks with one call may get a higher throughput than leasing smaller
         * batches of tasks. However, the larger the batch, the longer the lease time needs to be.
         * And the longer the lease time, the longer it takes for the tasks to be processed in case
         * an instance is restarted.
         *
         * Assumption used in the sample: Lease time of 30 minutes is reasonable as per the 
         * discussion above. A single thread should be able to process 100 tasks or 25,000 
         * notifications in that time. 
         * You may need to optimize these values to your scenario.
         */
        tasks = queue.leaseTasks(30, TimeUnit.MINUTES, 100);
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

  private void deleteTasks(List<TaskHandle> tasks) {
    for (int attemptNo = 1;; attemptNo++) {
      try {
        queue.deleteTask(tasks);
        break;
      } catch (TransientFailureException e) {
        log.warning("TransientFailureException when deleting tasks from queue '"
            + queue.getQueueName() + "'. Attempt=" + attemptNo);
      } catch (ApiDeadlineExceededException e) {
        log.warning("ApiDeadlineExceededException when deleting tasks from queue '"
            + queue.getQueueName() + "'. Attempt=" + attemptNo);
      }
      if (!backOff(attemptNo)) {
        break;
      }
    }
  }

  /**
   * Processes a list of tasks with push notifications requests.
   *
   * @param tasks the list of tasks to be processed
   * @result True The list of processed tasks
   */
  private void processLeasedTasks(List<TaskHandle> tasks) {
    Set<String> previouslyProcessedTaskNames = getAlreadyProcessedTaskNames(tasks);

    List<TaskHandle> processedTasks = new ArrayList<TaskHandle>();

    long pushedNotificationCount = 0;
    Map<String, PushedNotifications> pushedNotificationsForTasks =
        new HashMap<String, PushedNotifications>();

    boolean backOff = false;

    for (TaskHandle task : tasks) {
      if (LifecycleManager.getInstance().isShuttingDown()) {
        break;
      }

      processedTasks.add(task);

      if (previouslyProcessedTaskNames.contains(task.getName())) {
        log.info("Ignoring a task " + task.getName() + " that has been already processed "
            + "to avoid sending duplicated notification.");
        continue;
      }

      try {
        PushedNotifications pushedNotifications = processLeasedTask(task);
        pushedNotificationsForTasks.put(task.getName(), pushedNotifications);
        pushedNotificationCount += pushedNotifications.size();
        // Process pushed notifications every BATCH_NOTIFICATION_PROCESS_SIZE notifications or so
        if (pushedNotificationCount >= BATCH_NOTIFICATION_PROCESS_SIZE) {
          pushedNotificationCount = 0;
          processPushedNotifications(pushedNotificationsForTasks);
          pushedNotificationsForTasks.clear();
        }

      } catch (CommunicationException e) {
        log.log(Level.WARNING,
            "Sending push alert failed with CommunicationException:" + e.toString(), e);
        /*
         * This exception may be thrown when socket time out or similar issues occurred a few times
         * in a row. Retrying right away likely won't succeed and will only make another task
         * potentially only partially processed.
         */
        backOff = true;
      } catch (KeystoreException e) {
        log.log(
            Level.WARNING, "Sending push alert failed with KeystoreException:" + e.toString(), e);
        /*
         * It is likely a configuration issue. Retrying right away likely won't succeed and will
         * only make another task potentially only partially processed.
         */
        backOff = true;
      } finally {
        recordTaskProcessed(task);
      }
    }

    deleteTasks(processedTasks);

    // Process any remaining pushed notifications.
    if (pushedNotificationCount > 0) {
      processPushedNotifications(pushedNotificationsForTasks);
    }

    // Now all leased tasks are deleted, so it is safe to pause if appropriate.
    if (backOff) {
      log.log(Level.INFO, "Pausing processing to recover from an exception");
      ApiProxy.flushLogs();

      // Wait 5 minutes, but do it in 10 seconds increments to gracefully handle instance restarts.

      for (int i = 0; i < 30; i++) {
        if (LifecycleManager.getInstance().isShuttingDown()) {
          break;
        }
        try {
          Thread.sleep(10000);
        } catch (InterruptedException e) {
          break;
        }
      }
    }
  }

  /**
   * Processes a task with push notifications requests.
   *
   * @param task the tasks to be processed.
   * @return a collection of pushed notifications or null if no notifications were sent.
   */
  private PushedNotifications processLeasedTask(TaskHandle task)
      throws CommunicationException, KeystoreException {
    String hiddenMessage = null;
    String[] deviceTokens = null;
    List<Entry<String, String>> params = null;
    try {
      params = task.extractParams();
    } catch (UnsupportedEncodingException e) {
      log.warning("Ignoring a task with invalid encoding. This indicates a bug.");
      return null;
    } catch (UnsupportedOperationException e) {
      log.warning("Ignoring a task with invalid payload. This indicates a bug.");
      return null;
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
      log.warning("Ignoring a task with empty alert message");
      return null;
    }

    if (deviceTokens == null || deviceTokens.length == 0) {
      log.warning("Ignoring a task with no device tokens specified");
      return null;
    }

    PushNotificationPayload payload = notificationSender.createPayload("You receive a message", 
        hiddenMessage);

    return notificationSender.sendPayload(payload, deviceTokens);
  }

  private void processPushedNotifications(Map<String, PushedNotifications> pushedNotifications) {
    notificationSender.processedPendingNotificationResponses();

    for (String taskName : pushedNotifications.keySet()) {
      processPushedNotifications(taskName, pushedNotifications.get(taskName));
    }
  }

  private void processPushedNotifications(String taskName, PushedNotifications notifications) {
    List<String> invalidTokens = new ArrayList<String>();

    for (PushedNotification notification : notifications) {

      if (!notification.isSuccessful()) {
        log.log(Level.WARNING,
            "Notification to device " + notification.getDevice().getToken() + 
            " from task " + taskName + " wasn't successful.",
            notification.getException());

        // Check if APNS returned an error-response packet.
        ResponsePacket errorResponse = notification.getResponse();
        if (errorResponse != null) {
          if (errorResponse.getStatus() == 8) {
            String invalidToken = notification.getDevice().getToken();
            invalidTokens.add(invalidToken);
          }
          log.warning("Error response packet: " + errorResponse.getMessage());
        }
      }

      if (invalidTokens.size() > 0) {
        Utility.enqueueRemovingDeviceTokens(invalidTokens);
      }
    }
  }

  private boolean backOff(int attemptNo) {
    // Exponential back off between 2 seconds and 64 seconds with jitter 0..1000 ms.
    attemptNo = Math.min(6, attemptNo);
    int backOffTimeInSeconds = 1 << attemptNo;
    try {
      Thread.sleep(backOffTimeInSeconds * 1000 + (int) (Math.random() * 1000));
    } catch (InterruptedException e) {
      return false;
    }
    return true;
  }

  private void recordTaskProcessed(TaskHandle task) {
    cache.put(task.getName(), 1, Expiration.byDeltaSeconds(60 * 60 * 2));
    Entity entity = new Entity(PROCESSED_NOTIFICATION_TASKS_ENTITY_KIND, task.getName());
    entity.setProperty("processedAt", new Date());
    dataStore.put(entity);
  }

  /**
   * Check for already processed tasks.
   *
   * @param tasks the list of the tasks to be checked.
   * @return the set of task names that have already been processed.
   */
  private Set<String> getAlreadyProcessedTaskNames(List<TaskHandle> tasks) {
    /*
     * To optimize for performance check in memcache first. A value from memcache may have been
     * evicted. Datastore is the authoritative source, so for any task not found in memcache check
     * in Datastore.
     */

    List<String> taskNames = new ArrayList<String>();

    for (TaskHandle task : tasks) {
      taskNames.add(task.getName());
    }

    Map<String, Object> alreadyProcessedTaskNames = cache.getAll(taskNames);

    List<Key> keys = new ArrayList<Key>();

    for (String taskName : taskNames) {
      if (!alreadyProcessedTaskNames.containsKey(taskName)) {
        keys.add(KeyFactory.createKey(PROCESSED_NOTIFICATION_TASKS_ENTITY_KIND, taskName));
      }
    }

    if (keys.size() > 0) {
      Map<Key, Entity> entityMap = dataStore.get(keys);
      for (Key key : entityMap.keySet()) {
        alreadyProcessedTaskNames.put(key.getName(), 1);
      }
    }

    return alreadyProcessedTaskNames.keySet();
  }
}
