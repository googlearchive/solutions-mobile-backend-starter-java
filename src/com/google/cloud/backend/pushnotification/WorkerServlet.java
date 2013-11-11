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
import com.google.appengine.api.ThreadManager;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.apphosting.api.ApiProxy;

import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HttpServlet for sending pending notifications.
 *
 * It is intended to be hosted on a backend.
 */
public class WorkerServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = Logger.getLogger(WorkerServlet.class.getName());
  private static final int NUMBER_OF_WORKERS = 8;
  private static final int MILLISECONDS_TO_WAIT_WHEN_NO_TASKS_LEASED = 2500;

  /**
   * Create App Engine threads that will poll and the process the tasks.
   */
  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse res) {
    for (int workerNo = 0; workerNo < NUMBER_OF_WORKERS; workerNo++) {
      Thread thread = ThreadManager.createBackgroundThread(new Runnable() {
        @Override
        public void run() {
          doPolling();
        }
      });
      thread.start();
    }
  }

  private void doPolling() {
    Queue notificationQueue = QueueFactory.getQueue("notification-delivery");

    Worker worker = new Worker(notificationQueue);
    while (!LifecycleManager.getInstance().isShuttingDown()) {
      boolean tasksProcessed = worker.processBatchOfTasks();
      ApiProxy.flushLogs();

      if (!tasksProcessed) {
        // Wait before trying to lease tasks again.
        try {
          Thread.sleep(MILLISECONDS_TO_WAIT_WHEN_NO_TASKS_LEASED);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    log.info("Instance is shutting down");
  }
}
