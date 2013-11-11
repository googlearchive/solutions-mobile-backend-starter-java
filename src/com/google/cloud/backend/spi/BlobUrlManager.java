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

import com.google.appengine.api.appidentity.AppIdentityService;
import com.google.appengine.api.appidentity.AppIdentityServiceFactory;

import org.apache.commons.codec.binary.Base64;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal utility class for obtaining signed URLs and access URLs.
 */
class BlobUrlManager {
  private static final int DOWNLOAD_URL_EXPIRATION_IN_MINUTES = 10;
  private static final int UPLOAD_URL_EXPIRATION_IN_MINUTES = 15;
  /*
   * Signed URL for DELETE operation is used right away and the request need to complete within 60
   * seconds, so 1 minute expiration is sufficient.
   */
  private static final int DELETE_URL_EXPIRATION_IN_MINUTES = 1;
  private static final String BASE_URL = "https://storage.googleapis.com";
  private static final Logger logger = Logger.getLogger(BlobUrlManager.class.getSimpleName());
  private static final AppIdentityService appIdentityService =
      AppIdentityServiceFactory.getAppIdentityService();


  static String getDownloadUrl(String bucketName, String objectPath) {
    return getSignedUrl(
        bucketName, objectPath, DOWNLOAD_URL_EXPIRATION_IN_MINUTES, "GET");
  }

  static String getUploadUrl(
      String bucketName, String objectPath, String contentType, boolean publicRead) {
    return getSignedUrl(bucketName,
        objectPath,
        UPLOAD_URL_EXPIRATION_IN_MINUTES,
        "PUT",
        contentType,
        getMandatoryHeaders(publicRead));
  }

  static String getDeleteUrl(String bucketName, String objectPath) {

    return getSignedUrl(
        bucketName, objectPath, DELETE_URL_EXPIRATION_IN_MINUTES, "DELETE");
  }

  /**
   * Gets a signed Url for a specified HTTP operation without using canonicalized extensions
   * headers.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   * @param expirationInMinutes expiration in minutes for the signed URL.
   * @param httpVerb HTTP operation that this URL will be used with.
   */
  private static String getSignedUrl(
      String bucketName, String objectPath, int expirationInMinutes, String httpVerb) {
    String contentType = "";
    String canonicalizedExtensionHeaders = "";
    return getSignedUrl(bucketName,
        objectPath,
        expirationInMinutes,
        httpVerb,
        contentType,
        canonicalizedExtensionHeaders);
  }

  /**
   * Gets a signed Url for a specified HTTP operation, contentType and canonicalized extensions
   * headers.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   * @param expirationInMinutes expiration in minutes for the signed URL.
   * @param httpVerb HTTP operation that this URL will be used with.
   * @param contentType the MIME type of the object.
   * @param canonicalizedExtensionHeaders extension headers canonicalized as specified at
   *        http://developers.google.com/storage/docs/accesscontrol#About-CanonicalExtensionHeaders
   */
  private static String getSignedUrl(String bucketName,
      String objectPath,
      int expirationInMinutes,
      String httpVerb,
      String contentType,
      String canonicalizedExtensionHeaders) {

    String canonicalizedResource = "/" + bucketName + "/" + objectPath;

    long expirationInSecondsSinceEpoch = getExpiration(expirationInMinutes);

    String stringToSign = getStringToSign(
        canonicalizedResource, httpVerb, contentType, canonicalizedExtensionHeaders,
        expirationInSecondsSinceEpoch);

    String encodedSignature = signAndEncode(stringToSign);

    return assembleSignedUrl(
        canonicalizedResource, encodedSignature, expirationInSecondsSinceEpoch);
  }

  /**
   * Gets URL that can be used to access the object.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   */
  static String getAccessUrl(String bucketName, String objectPath) {
    return BASE_URL + "/" + bucketName + "/" + objectPath;
  }

  static String getMandatoryHeaders(boolean publicRead) {
    return publicRead ? "x-goog-acl:public-read\n" : "";
  }

  /**
   * Signs and encodes a string using app identity service.
   */
  private static String signAndEncode(String stringToSign) {
    byte[] signature = appIdentityService.signForApp(stringToSign.getBytes()).getSignature();

    try {
      String base64EncodedSignature = new String(Base64.encodeBase64(signature, false), "UTF-8");
      return URLEncoder.encode(base64EncodedSignature, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      // This should not happen.
      logger.log(Level.SEVERE, "Encoding a signature failed", e);
      return "";
    }
  }

  private static String assembleSignedUrl(
      String canonicalizedResource, String encodedSignature, long expirationInSecondsSinceEpoch) {
    return BASE_URL + canonicalizedResource + "?GoogleAccessId=" + getServiceAccountName()
        + "&Expires=" + expirationInSecondsSinceEpoch + "&Signature=" + encodedSignature;
  }

  private static String getServiceAccountName() {
    return appIdentityService.getServiceAccountName();
  }

  /**
   * Constructs a string to be signed as per Google Cloud Storage specification.
   */
  private static String getStringToSign(String canonicalizedResource, String httpVerb,
      String contentType, String canonicalizedExtensionHeaders,
      long expirationInSecondsSinceEpoch) {
    String contentMD5 = "";
    return httpVerb + "\n" + contentMD5 + "\n" + contentType + "\n" + expirationInSecondsSinceEpoch
        + "\n" + canonicalizedExtensionHeaders + canonicalizedResource;
  }

  private static long getExpiration(int minutesFromNow) {
    Calendar calendar = Calendar.getInstance();
    calendar.add(Calendar.MINUTE, minutesFromNow);
    long expiration = calendar.getTimeInMillis() / 1000L;
    return expiration;
  }
}
