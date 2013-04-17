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

import com.google.android.gcm.server.Message;
import com.google.android.gcm.server.Result;
import com.google.android.gcm.server.Sender;
import com.google.cloud.backend.config.BackendConfigManager;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A servlet class for handling Prospective Search API tasks.
 *
 */
@SuppressWarnings("serial")
public class ProspectiveSearchServlet extends HttpServlet {

  private static final int GCM_SEND_RETRIES = 3;

  private static final Logger log = Logger.getLogger(ProspectiveSearchServlet.class.getName());

  protected static final String GCM_KEY_SUBID = "subId";

  private final BackendConfigManager backendConfigManager = new BackendConfigManager();

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {

    // get GCM configs
    if (!backendConfigManager.isGcmEnabled()) {
      log.info("ProspectiveSearchServlet: couldn't send GCM because GCM is disabled.");
      return;
    }
    String gcmKey = backendConfigManager.getGcmKey();
    if (gcmKey == null || gcmKey.trim().length() == 0) {
      log.info("ProspectiveSearchServlet: couldn't send GCM because GCM key is empty.");
      return;
    }

    // dispatch GCM messages to each subscribers
    String[] subIds = req.getParameterValues("id");
    for (String subId : subIds) {
      String[] tokens = subId.split(":");
      String regId = tokens[0];
      Sender sender = new Sender(gcmKey);
      Message message = new Message.Builder().addData(GCM_KEY_SUBID, subId).build();
      Result r = sender.send(message, regId, GCM_SEND_RETRIES);
      if (r.getMessageId() != null) {
        log.info("ProspectiveSearchServlet: GCM sent: subId: " + subId);
      } else {
        log.warning("ProspectiveSearchServlet: GCM error for subId: " + subId + ", senderId: "
            + gcmKey + ", error: " + r.getErrorCodeName());
      }
    }
  }
}
