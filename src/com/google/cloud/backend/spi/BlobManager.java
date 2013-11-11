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

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Transaction;
import com.google.appengine.api.datastore.TransactionOptions;
import com.google.cloud.backend.spi.BlobEndpoint.BlobAccessMode;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ConcurrentModificationException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal utility class for blobs stored in Google Cloud Storage and their metadata and
 * permissions.
 */
class BlobManager {
  private static final Logger logger = Logger.getLogger(BlobManager.class.getSimpleName());
  private static final DatastoreService dataStore = DatastoreServiceFactory.getDatastoreService();

  /**
   * Gets blob metadata.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   * @return blob metadata or null if there is no object for this objectPath and bucketName.
   */
  public static BlobMetadata getBlobMetadata(String bucketName, String objectPath) {
    try {
      Entity metadataEntity =
          dataStore.get(BlobMetadata.getKey(getCanonicalizedResource(bucketName, objectPath)));
      return new BlobMetadata(metadataEntity);
    } catch (EntityNotFoundException e) {
      return null;
    }
  }

  /**
   * Stores metadata if this is a new blob or existing blob owned by this user.
   *
   * @param bucketName Google Cloud Storage bucket for this blob.
   * @param objectPath path to the object in the bucket.
   * @param accessMode controls how the blob can be accessed.
   * @param ownerId the id of the owner.
   * @return true if metadata was stored; false if the blob already exists but has a different
   *         owner.
   */
  public static boolean tryStoreBlobMetadata(
      String bucketName, String objectPath, BlobAccessMode accessMode, String ownerId) {

    Transaction tx = dataStore.beginTransaction(TransactionOptions.Builder.withXG(true));
    try {
      BlobMetadata metadata = getBlobMetadata(bucketName, objectPath);

      if (metadata != null) {
        if (!ownerId.equalsIgnoreCase(metadata.getOwnerId())) {
          // Object exists and is owned by a different owner.
          return false;
        } else if (accessMode == metadata.getAccessMode()) {
          // The new metadata is the same as the existing one. No need to update anything.
          return true;
        }
      }

      metadata =
          new BlobMetadata(getCanonicalizedResource(bucketName, objectPath), accessMode, ownerId);
      dataStore.put(metadata.getEntity());
      tx.commit();
      return true;
    } catch (ConcurrentModificationException e) {
      return false;
    } finally {
      if (tx != null && tx.isActive()) {
        tx.rollback();
      }
    }
  }

  /**
   * Deletes a blob from Google Cloud Storage and the associated metadata from Datastore.
   *
   * @param bucketName Google Cloud Storage bucket for this blob.
   * @param objectPath path to the object in the bucket.
   * @return true if the operation succeeded; false otherwise.
   */
  public static boolean deleteBlob(String bucketName, String objectPath) {
    return deleteBlobFromGcs(bucketName, objectPath) && deleteBlobMetadata(bucketName, objectPath);
  }

  /**
   * Deletes a blob from Google Cloud Storage and associated metadata.
   *
   * @param bucketName Google Cloud Storage bucket for this blob.
   * @param objectPath path to the object in the bucket.
   * @return true if the operation succeeded; false otherwise.
   */
  private static boolean deleteBlobFromGcs(String bucketName, String objectPath) {
    /*
     * Use a signed URL to authenticate with Google Cloud Storage.
     */
    String signedUrl = BlobUrlManager.getDeleteUrl(bucketName, objectPath);

    /*
     * If an attempt to delete a blob from GCS fails with a transient error, let's retry it using
     * exponential back-off (2, 4, 8 ... seconds) with jitter (0..1000 milliseconds). Since the
     * request needs to complete within 60 seconds, there is enough time to do no more than 5
     * attempts.
     */
    for (int attemptNo = 1; attemptNo <= 5; attemptNo++) {
      try {
        URL gcsRequestUrl = new URL(signedUrl);
        HttpURLConnection connection = (HttpURLConnection) gcsRequestUrl.openConnection();
        connection.setRequestMethod("DELETE");

        int status = connection.getResponseCode();

        if (status >= HttpURLConnection.HTTP_OK && status < HttpURLConnection.HTTP_MULT_CHOICE) {
          // HTTP 2xx response => success.
          return true;
        }

        if (status < HttpURLConnection.HTTP_INTERNAL_ERROR) {
          // HTTP 3xx or 4xxx => log, do not retry.
          logger.log(Level.WARNING,
              "Deleting " + objectPath + " from " + bucketName + " returned " + status);
          return false;
        }

        logger.log(Level.INFO, "Deleting " + objectPath + " from " + bucketName
            + " failed (attemptNo: " + attemptNo + ") with status code: " + status);

      } catch (MalformedURLException e) {
        // This shouldn't happen.
        logger.log(Level.SEVERE, "URL from BlobUrlManager.getSignedUrl was malformed", e);
        return false;
      } catch (IOException e) {
        logger.log(Level.INFO, "Deleting " + objectPath + " from " + bucketName
            + " failed (attemptNo: " + attemptNo + ") with " + e.getMessage(), e);
      }

      try {
        Thread.sleep(1000 * (1 << attemptNo) + (int) (Math.random() * 1000));
      } catch (InterruptedException e) {
        return false;
      }
    }

    logger.log(Level.WARNING, "Deleting blob " + objectPath + " from " + bucketName + " failed.");
    return false;
  }

  /**
   * Deletes blob metadata.
   *
   * @param bucketName Google Cloud Storage bucket for this blob.
   * @param objectPath path to the object in the bucket.
   * @return true if the operation succeeded; false otherwise.
   */
  private static boolean deleteBlobMetadata(String bucketName, String objectPath) {
    /*
     * If an attempt to delete blob metadata fails with a transient error, let's retry it using
     * exponential back-off (2, 4, 8 ... seconds) with jitter (0..1000 milliseconds). Since the
     * request needs to complete within 60 seconds, there is enough time to do no more than 5
     * attempts.
     */
    for (int attemptNo = 1; attemptNo <= 5; attemptNo++) {
      try {
        dataStore.delete(BlobMetadata.getKey(getCanonicalizedResource(bucketName, objectPath)));
        return true;
      } catch (ConcurrentModificationException concurrentModificationException) {
        logger.log(Level.INFO, "Deleting metadata for " + objectPath + " in " + bucketName
            + " failed with ConcurrentModificationException. Attempt: " + attemptNo);
      }
      try {
        Thread.sleep(1000 * (1 << attemptNo) + (int) (Math.random() * 1000));
      } catch (InterruptedException e) {
        return false;
      }
    }

    logger.log(Level.WARNING,
        "Deleting blob metadata for " + objectPath + " from " + bucketName + " failed.");

    return false;
  }

  private static String getCanonicalizedResource(String bucketName, String objectPath) {
    return bucketName + "/" + objectPath;
  }
}
