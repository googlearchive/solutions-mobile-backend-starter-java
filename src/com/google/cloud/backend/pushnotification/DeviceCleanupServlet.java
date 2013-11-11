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

import com.google.appengine.api.datastore.Entity;
import com.google.cloud.backend.spi.DeviceSubscription;
import com.google.cloud.backend.spi.SubscriptionUtility;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HttpServlet for removing inactive or invalid device registration information.
 *
 * It is intended to be called by Push Task Queue, so the request is retried if it fails.
 */
public class DeviceCleanupServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = Logger.getLogger(DeviceCleanupServlet.class.getName());
  /**
   * If a device registration has been recently updated then the request to remove it may be out of
   * date. This constant defines for how many hours the device registration is considered very
   * fresh.
   */
  private static final int FRESH_REGISTRATION_TIME_WINDOW_IN_HOURS = 4;

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!Configuration.isRequestFromTaskQueue(req, resp)) {
      return;
    }

    String devicesJSon = req.getParameter("devices");
    if (devicesJSon == null) {
      log.warning("Missing 'devices' argument on task queue request. This indicates a bug");
      return;
    }
    String[] devices = null;
    try {
      devices = new Gson().fromJson(devicesJSon, String[].class);
    } catch (JsonSyntaxException e) {
      log.warning(
          "Invalid format of 'devices' argument on task queue request. This indicates a bug");
      return;
    }
    removeDevices(devices);
  }

  private void removeDevices(String[] devices) {
    List<String> list = Arrays.asList(devices);
    SubscriptionUtility.clearSubscriptionAndDeviceEntity(list);

    // Skip removing devices that have been registered very recently
    // as the request to remove them may be obsolete
    Calendar removalThreshold = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    removalThreshold.add(Calendar.HOUR, -1 * FRESH_REGISTRATION_TIME_WINDOW_IN_HOURS);
    Date removalThresholdDate = removalThreshold.getTime();

    DeviceSubscription deviceSubscription = new DeviceSubscription();
    for (String deviceToken : devices) {
      Entity deviceEntity = deviceSubscription.get(deviceToken);
      Date timestamp = (Date) deviceEntity.getProperty(DeviceSubscription.PROPERTY_TIMESTAMP);

      // Remove the registration if it hasn't been already removed or recently (re-)registered.
      if (deviceEntity != null && removalThresholdDate.after(timestamp)) {
        deviceSubscription.delete(deviceToken);
      }
    }
  }
}
