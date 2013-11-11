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

import com.google.cloud.backend.config.BackendConfigManager;

import javapns.Push;
import javapns.communication.exceptions.CommunicationException;
import javapns.communication.exceptions.KeystoreException;
import javapns.devices.Device;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HttpServlet for querying APNS Feedback service and retrieving the list of inactive devices and
 * initiating removal of the corresponding DeviceRegistration records.
 *
 * It is intended to be called by Push Task Queue or Cron
 */
public class PushFeedbackProcessingServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log =
      Logger.getLogger(PushFeedbackProcessingServlet.class.getCanonicalName());
  private static final BackendConfigManager backendConfigManager = new BackendConfigManager();

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    try {
      // Retrieving the list of inactive devices from the Feedback service
      List<Device> inactiveDevices = Push.feedback(
          backendConfigManager.getPushNotificationCertificate(),
          backendConfigManager.getPushCertPassword(),
          Configuration.USE_PRODUCTION_APNS_SERVICE);

      if (inactiveDevices.size() > 0) {
        log.info("Number of inactive devices: " + inactiveDevices.size());
        // get the list of inactive device tokens
        List<String> inactiveDeviceTokens = new ArrayList<String>();
        for (Device device : inactiveDevices) {
          inactiveDeviceTokens.add(device.getToken());
        }
        Utility.enqueueRemovingDeviceTokens(inactiveDeviceTokens);
      }
    } catch (CommunicationException e) {
      log.log(Level.WARNING,
          "Retrieving the list of inactive devives failed with CommunicationException:"
          + e.toString(), e);
    } catch (KeystoreException e) {
      log.log(Level.WARNING,
          "Retrieving the list of inactive devives failed with KeystoreException:" + e.toString(),
          e);
    }
  }
}
