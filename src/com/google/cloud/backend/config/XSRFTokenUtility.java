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

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import org.apache.geronimo.mail.util.Base64Encoder;

/**
 * Utility class for preventing XSRF attacks. The class is NOT general purposes,
 * but rather tailored to this sample. For example it assumes that it is called
 * from code that is hosted on App Engine and there is a user signed in.
 * 
 */
class XSRFTokenUtility {
  private static final long DEFAULT_MAX_TIME_DIFF = 1000 * 60 * 10;
  private static final long MAX_TIME_DIFF_BACK = 1000 * 60;
  private static final String DELIM = ":";

  /**
   * Gets a token that can be used to prevent XSRF attacks.
   * 
   * @param action
   *          Identifies the action that the token can be used for (e.g.,
   *          reading configuration).
   * @param secretKey
   *          SecretKey unique to this deployment.
   * @return token
   * @throws NoSuchAlgorithmException
   *           if SHA1 algorithm is not available.
   * @throws IOException
   *           if encoding the token failed.
   */
  static String getToken(String secretKey, String action) throws NoSuchAlgorithmException,
      IOException {
    if (action == null || action.isEmpty()) {
      throw new IllegalArgumentException("'action' argument cannot be empty");
    }

    if (secretKey == null || secretKey.length() < 20) {
      throw new IllegalArgumentException("'secretKey' should be at least 20 characters");
    }

    String time = String.valueOf(System.currentTimeMillis());
    String clearString = buildTokenString(secretKey, action, time);
    return encodeTokenString(clearString) + DELIM + time;
  }

  /**
   * Verifies a token to prevent XSRF attacks.
   * 
   * @param action
   *          Identifies the action that the token is needed for (e.g., reading
   *          configuration)
   * @param secretKey
   *          SecretKey unique to this deployment.
   * @param token
   *          Token passed by the caller.
   * @return true if token is valid; false otherwise.
   * @throws NoSuchAlgorithmException
   *           if SHA1 algorithm is not available.
   * @throws IOException
   *           if encoding the token failed.
   */
  static boolean verifyToken(String secretKey, String action, String token)
      throws NoSuchAlgorithmException, IOException {
    if (action == null || action.isEmpty()) {
      throw new IllegalArgumentException("'action' argument cannot be empty");
    }

    if (secretKey == null || secretKey.length() < 20) {
      throw new IllegalArgumentException("'secretKey' should be at least 20 characters");
    }

    if (token == null || token.isEmpty()) {
      return false;
    }

    String[] tokenParts = token.split(DELIM);
    if (tokenParts.length != 2) {
      return false;
    }

    long currentTime = System.currentTimeMillis();
    long postTime;
    try {
      postTime = Long.valueOf(tokenParts[1]);
    } catch (NumberFormatException e) {
      return false;
    }

    if (currentTime < (postTime - MAX_TIME_DIFF_BACK)
        || currentTime > (postTime + DEFAULT_MAX_TIME_DIFF)) {
      return false;
    }

    String expectedTokenString = buildTokenString(secretKey, action, tokenParts[1]);

    if (!verifySignature(tokenParts[0], expectedTokenString)) {
      // Request is suspicious. The signature didn't match even though the time
      // stamp was OK.
      try {
        Thread.sleep(new Random().nextInt(2000));
      } catch (InterruptedException e) {
        // ignore
      }
      return false;
    }

    return true;
  }

  private static String buildTokenString(String secretKey, String action, String time) {
    UserService userService = UserServiceFactory.getUserService();
    User user = userService.getCurrentUser();

    return buildTokenString(secretKey, user.getEmail(), action, time);
  }

  private static String buildTokenString(String secretKey, String user, String action, String time) {
    return secretKey + DELIM + user.replaceAll(DELIM, "_") + DELIM + action.replaceAll(DELIM, "_")
        + DELIM + time.replaceAll(DELIM, "_");
  }

  private static String encodeTokenString(String tokenString) throws NoSuchAlgorithmException,
      IOException {
    MessageDigest tokenSigner = MessageDigest.getInstance("SHA-1");
    byte[] token = tokenSigner.digest(tokenString.getBytes("UTF-8"));
    Base64Encoder encoder = new Base64Encoder();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    encoder.encode(token, 0, token.length, out);
    return out.toString();
  }

  private static boolean verifySignature(String receivedToken, String expectedTokenString)
      throws NoSuchAlgorithmException, IOException {
    String expectedToken = encodeTokenString(expectedTokenString);
    return expectedToken.equals(receivedToken);
  }
}