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
package com.google.cloud.backend.config;

import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchService;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchServiceFactory;
import com.google.appengine.api.prospectivesearch.Subscription;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.cloud.backend.beans.EntityDto;
import com.google.cloud.backend.beans.EntityListDto;
import com.google.cloud.backend.spi.CrudOperations;
import com.google.cloud.backend.spi.QueryOperations;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet for the Backend Configuration UI.
 */
@SuppressWarnings("serial")
public class ConfigurationServlet extends HttpServlet {

  private static BackendConfigManager configMgr = new BackendConfigManager();

  private static final UserService userService = UserServiceFactory
      .getUserService();

  private static final ProspectiveSearchService prosSearch = ProspectiveSearchServiceFactory
      .getProspectiveSearchService();

  private static final String KIND_NAME_PUSH_MESSAGES = "_CloudMessages";

  private static final String PARAM_PUSHMSG_TOPIC_ID = "topicId";

  private static final String PARAM_PUSHMSG_PROPERTIES = "properties";

  private static final String PARAM_OPERATION = "op";

  private static final String PARAM_OPERATION_READ = "read";

  private static final String PARAM_OPERATION_SAVE = "save";

  private static final String PARAM_OPERATION_PUSHMSG = "pushmsg";

  private static final String PARAM_OPERATION_CLEAR_SUBSCRIPTIONS = "clearsubs";
  
  private static final String PARAM_TOKEN = "token";

  private static final String JSON_RESP_PROP_MESSAGE = "message";

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {

    // process request
    JsonObject jsonResponse = new JsonObject();
    String mode = req.getParameter(PARAM_OPERATION);
    
    try {
      if (!XSRFTokenUtility.verifyToken(configMgr.getSecretKey(), 
          mode, req.getParameter(PARAM_TOKEN))) {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
    } catch (NoSuchAlgorithmException e) {
      resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    if (PARAM_OPERATION_READ.equals(mode)) {
      readConfig(jsonResponse);
    } else if (PARAM_OPERATION_SAVE.equals(mode)) {
      saveConfig(req, jsonResponse);
    } else if (PARAM_OPERATION_PUSHMSG.equals(mode)) {
      sendPushMessage(req, jsonResponse);
    } else if (PARAM_OPERATION_CLEAR_SUBSCRIPTIONS.equals(mode)) {
      clearAllSubscriptions(jsonResponse);
    } else {
      throw new IllegalArgumentException("No such operation: " + mode);
    }

    // write response
    resp.setContentType("application/json");
    resp.setStatus(200);
    resp.getWriter().println(jsonResponse.toString());
    resp.getWriter().flush();
    resp.flushBuffer();
  }

  private void readConfig(JsonObject jsonResponse) {
    Entity config = configMgr.getConfiguration();
    jsonResponse.addProperty(BackendConfigManager.AUTHENTICATION_MODE,
        (String) config.getProperty(BackendConfigManager.AUTHENTICATION_MODE));
    jsonResponse.addProperty(BackendConfigManager.ANDROID_CLIENT_ID,
        (String) config.getProperty(BackendConfigManager.ANDROID_CLIENT_ID));
    jsonResponse.addProperty(BackendConfigManager.IOS_CLIENT_ID,
        (String) config.getProperty(BackendConfigManager.IOS_CLIENT_ID));
    jsonResponse.addProperty(BackendConfigManager.AUDIENCE,
        (String) config.getProperty(BackendConfigManager.AUDIENCE));
    jsonResponse.addProperty(BackendConfigManager.PUSH_ENABLED,
        (Boolean) config.getProperty(BackendConfigManager.PUSH_ENABLED));
    jsonResponse.addProperty(BackendConfigManager.ANDROID_GCM_KEY,
        (String) config.getProperty(BackendConfigManager.ANDROID_GCM_KEY));
  }

  private void saveConfig(HttpServletRequest req, JsonObject jsonResponse) {
    configMgr.setConfiguration(
        req.getParameter(BackendConfigManager.AUTHENTICATION_MODE),
        req.getParameter(BackendConfigManager.ANDROID_CLIENT_ID),
        req.getParameter(BackendConfigManager.IOS_CLIENT_ID),
        req.getParameter(BackendConfigManager.AUDIENCE),
        Boolean.valueOf(req.getParameter(BackendConfigManager.PUSH_ENABLED)),
        req.getParameter(BackendConfigManager.ANDROID_GCM_KEY));
    jsonResponse.addProperty(JSON_RESP_PROP_MESSAGE, "Settings Saved.");
  }

  /**
   * a request with "op=broadcast" sends a broadcast message to all registered
   * devices. The message will contain all key-value pairs specified as
   * parameter.
   * 
   * example: /admin/cconf?op=broadcast&msg=hello&duration=5
   */
  private void sendPushMessage(HttpServletRequest req, JsonObject jsonResp) {

    // decode params and validate
    String topicId = req.getParameter(PARAM_PUSHMSG_TOPIC_ID);
    String props = req.getParameter(PARAM_PUSHMSG_PROPERTIES);
    if (topicId == null || topicId.trim().length() == 0 || props == null
        || props.trim().length() == 0) {
      jsonResp.addProperty(JSON_RESP_PROP_MESSAGE,
          "TopicId or properties are empty.");
      return;
    }

    // decode properties field (comma separated key-value pairs)
    // e.g. foo=bar,hoge=123
    Map<String, Object> params = new HashMap<String, Object>();
    params.put(PARAM_PUSHMSG_TOPIC_ID, topicId);
    for (String prop : props.split(",")) {
      String[] s = prop.split("=");
      params.put(s[0], s[1]);
    }

    // create an entity for _PushMessages from the parameters
    EntityDto cd = new EntityDto();
    cd.setKindName(KIND_NAME_PUSH_MESSAGES);
    cd.setProperties(params);
    EntityListDto cdl = new EntityListDto();
    cdl.add(cd);

    // save the entity to broadcast the toast
    try {
      CrudOperations.getInstance().saveAll(cdl, userService.getCurrentUser());
    } catch (UnauthorizedException e) {
      e.printStackTrace();
    }
    jsonResp.addProperty(JSON_RESP_PROP_MESSAGE, "Broadcast message sent: "
        + params);
  }

  private void clearAllSubscriptions(JsonObject jsonResp) {

    // remove all subscriptions from PSI
    List<Subscription> subs = prosSearch
        .listSubscriptions(QueryOperations.PROS_SEARCH_DEFAULT_TOPIC);
    for (Subscription sub : subs) {
      prosSearch.unsubscribe(QueryOperations.PROS_SEARCH_DEFAULT_TOPIC,
          sub.getId());
    }
    jsonResp.addProperty(JSON_RESP_PROP_MESSAGE, "Cleared all subscriptions.");
  }
  
  /**
   * Gets a token that can be used to prevent XSRF attacks.
   *
   * @param action Identifies the action that the token can be used for (e.g., reading
   *        configuration)
   * @return token
   * @throws NoSuchAlgorithmException if SHA1 algorithm is not available
   * @throws IOException if encoding the token failed
   */
  public static String getToken(String action) throws NoSuchAlgorithmException, IOException {
    if (action == null || action.isEmpty()) {
      throw new IllegalArgumentException("'action' argument cannot be empty");
    }
    return XSRFTokenUtility.getToken(configMgr.getSecretKey(), action);
  }
}