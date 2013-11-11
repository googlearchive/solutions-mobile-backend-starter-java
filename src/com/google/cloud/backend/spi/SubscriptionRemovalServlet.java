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
import com.google.cloud.backend.config.BackendConfigManager;
import com.google.cloud.backend.config.StringUtility;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HttpServlet for removing device and Prospective Search API subscription entities.
 *
 * It is intended to be called by Push Task Queue, so the request is retried if it fails.
 */
public class SubscriptionRemovalServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final ProspectiveSearchService prosSearch = ProspectiveSearchServiceFactory
      .getProspectiveSearchService();
  private static final Logger log =
      Logger.getLogger(SubscriptionRemovalServlet.class.getName());
  private static final Gson gson = new Gson();
  private static final DeviceSubscription deviceSubscription = new DeviceSubscription();
  private static final BackendConfigManager backendConfigManager = new BackendConfigManager();

  /**
   * Handles the POST request from Task Queue
   */
  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String type = req.getParameter("type");
    if (StringUtility.isNullOrEmpty(type)) {
      throw new IllegalArgumentException("Parameter 'type' cannot be null or empty.");
    }

    if (SubscriptionUtility.REQUEST_TYPE_DEVICE_SUB.compareTo(type) == 0) {
      removeDeviceSubscription(req);
    } else if (SubscriptionUtility.REQUEST_TYPE_PSI_SUB.compareTo(type) == 0) {
      removePsiSubscription(req);
    } else {
      throw new IllegalArgumentException("Invalid value of parameter 'type'.");
    }
  }

  private void removePsiSubscription(HttpServletRequest req) {
    String subIdsParameter = req.getParameter("subIds");
    if (StringUtility.isNullOrEmpty(subIdsParameter)) {
      log.warning("Missing 'subIds' argument on task queue request. This indicates a bug");
      return;
    }

    String[] subIds;
    try {
      subIds = gson.fromJson(subIdsParameter, String[].class);
    } catch (JsonSyntaxException e) {
      log.warning("Invalid format of 'subIds' argument on task queue request. " +
          "This indicates a bug");
      return;
    }

    for (String subId : subIds) {
      try {
        prosSearch.unsubscribe(QueryOperations.PROS_SEARCH_DEFAULT_TOPIC, subId);
      } catch (IllegalArgumentException e) {
        log.info("Unsubscribe " + subId + " from PSI encounters error, " + e.getMessage());
      }
    }
  }

  /**
   * Remove device subscription entities based on input parameters
   * @param req Http request contains parameters 'cursor' and 'timeStamp'. 'Cursor'
   *            provides hint to last query result position.  'TimeStamp' filters out
   *            entities created after delete method was initiated.
   */
  private void removeDeviceSubscription(HttpServletRequest req) {
    String cursorParameter = req.getParameter("cursor");
    if (cursorParameter == null) {
      log.warning("Missing 'cursor' argument on task queue request. This indicates a bug.");
      return;
    }

    if (cursorParameter.isEmpty()) {
      backendConfigManager.setLastSubscriptionDeleteAllTime(
          Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime());
    }

    String timeParameter = req.getParameter("timeStamp");
    if (StringUtility.isNullOrEmpty(timeParameter)) {
      log.warning("Missing 'timeStamp' argument on task queue request. " +
          "This indicates a bug.");
      return;
    }

    Date time;
    try {
      time = gson.fromJson(timeParameter, Date.class);
    } catch (JsonSyntaxException e) {
      log.warning("Invalid format of 'timeStamp' argument on task queue request. " +
          "This indicates a bug: " + timeParameter);
      return;
    }

    deviceSubscription.deleteAllContinuously(time, cursorParameter);
  }
}
