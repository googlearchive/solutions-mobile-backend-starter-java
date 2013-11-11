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

import com.google.api.server.spi.config.AnnotationBoolean;
import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;
import com.google.api.server.spi.config.ApiMethod.HttpMethod;
import com.google.api.server.spi.config.ApiNamespace;
import com.google.api.server.spi.response.BadRequestException;
import com.google.api.server.spi.response.InternalServerErrorException;
import com.google.api.server.spi.response.NotFoundException;
import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.users.User;
import com.google.cloud.backend.config.StringUtility;

import javax.annotation.Nullable;
import javax.inject.Named;

/**
 * Endpoint for managing blobs.
 */
@Api(name = "mobilebackend", namespace = @ApiNamespace(ownerDomain = "google.com",
    ownerName = "google.com", packagePath = "cloud.backend.android"),
    useDatastoreForAdditionalConfig = AnnotationBoolean.TRUE)
public class BlobEndpoint {
  /**
   * Pseudo user id used when Mobile Backend Starter is configured in Open mode and the requests are
   * unauthenticated. The string must be guaranteed to be different than any valid user account.
   * Otherwise if a user signs in to the mobile app with a Google account with the same email
   * address that user will have access to all blobs created in Open mode.
   */
  private static final String OPEN_MODE_USER_ID = "open.mode.owner@mobile.backend.google.com";

  /**
   * Access mode for uploaded blobs.
   */
  public enum BlobAccessMode {
    /**
     * The blob is public and can be downloaded by any one.
     */
    PUBLIC_READ,
    /**
     * Any user of the app with a valid client ID can obtain URL to download this blob.
     */
    PUBLIC_READ_FOR_APP_USERS,
    /**
     * The blob is private and only the user who uploaded it can download it using the app.
     */
    PRIVATE;
  }

  /**
   * Gets a signed URL that can be used to upload a blob.
   *
   * @param bucketName Google Cloud Storage bucket to use for upload.
   * @param objectPath path to the object in the bucket.
   * @param accessMode controls how the uploaded blob can be accessed.
   * @param contentType the MIME type of the object of be uploaded. Can be null.
   * @param user the user making the request.
   * @throws UnauthorizedException if the user is not authorized.
   * @throws BadRequestException if the bucketName or objectPath are not valid.
   */
  @ApiMethod(httpMethod = HttpMethod.GET, path = "blobs/uploads/{bucketName}/{objectPath}")
  public BlobAccess getUploadUrl(@Named("bucketName") String bucketName,
      @Named("objectPath") String objectPath, @Named("accessMode") BlobAccessMode accessMode,
      @Nullable @Named("contentType") String contentType, User user)
      throws UnauthorizedException, BadRequestException {
    validateUser(user);

    validateBucketAndObjectPath(bucketName, objectPath);

    if (!reserveNameIfAvailable(bucketName, objectPath, accessMode, user)) {
      throw new UnauthorizedException("You don't have permissions to upload this object");
    }

    return getBlobUrlForUpload(
        bucketName, objectPath, accessMode, contentType != null ? contentType : "");
  }

  /**
   * Gets a signed URL that can be used to download a blob.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   * @param user the user making the request.
   * @throws UnauthorizedException if the user is not authorized.
   * @throws BadRequestException if the bucketName or objectPath are not valid.
   * @throws NotFoundException if the object doesn't exist.
   */
  @ApiMethod(httpMethod = HttpMethod.GET, path = "blobs/downloads/{bucketName}/{objectPath}")
  public BlobAccess getDownloadUrl(
      @Named("bucketName") String bucketName, @Named("objectPath") String objectPath, User user)
      throws UnauthorizedException, BadRequestException, NotFoundException {
    validateUser(user);

    validateBucketAndObjectPath(bucketName, objectPath);

    checkReadObjectPermissions(bucketName, objectPath, user);

    return getBlobUrlForDownload(bucketName, objectPath);
  }

  /**
   * Deletes a blob.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   * @param user the user making the request.
   * @throws UnauthorizedException if the user is not authorized.
   * @throws BadRequestException if the bucketName or objectPath are not valid.
   * @throws InternalServerErrorException when the operation failed.
   */
  @ApiMethod(httpMethod = HttpMethod.DELETE, path = "blobs/{bucketName}/{objectPath}")
  public void deleteBlob(
      @Named("bucketName") String bucketName, @Named("objectPath") String objectPath, User user)
      throws UnauthorizedException, BadRequestException, InternalServerErrorException {
    validateUser(user);

    validateBucketAndObjectPath(bucketName, objectPath);

    boolean blobExists = checkDeletePermissions(bucketName, objectPath, user);

    if (!blobExists) {
      // DELETE operation is idempotent. The object doesn't exist, so there is no more work to do.
      return;
    }

    if (!deleteAllBlobInformation(bucketName, objectPath)) {
      throw new InternalServerErrorException("Deleting blob failed. You can retry.");
    }
  }

  private BlobAccess getBlobUrlForUpload(
      String bucketName, String objectPath, BlobAccessMode accessMode, String contentType) {
    String signedUrl = BlobUrlManager.getUploadUrl(
        bucketName, objectPath, contentType, accessMode == BlobAccessMode.PUBLIC_READ);

    if (accessMode == BlobAccessMode.PUBLIC_READ) {
      return new BlobAccess(signedUrl, BlobUrlManager.getAccessUrl(bucketName, objectPath),
          BlobUrlManager.getMandatoryHeaders(true));
    } else {
      return new BlobAccess(signedUrl);
    }
  }

  private BlobAccess getBlobUrlForDownload(String bucketName, String objectPath) {
    String signedUrl = BlobUrlManager.getDownloadUrl(bucketName, objectPath);
    return new BlobAccess(signedUrl);
  }

  private void validateUser(User user) throws UnauthorizedException {
    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
  }

  private void validateBucketAndObjectPath(String bucketName, String objectPath)
      throws BadRequestException {
    if (StringUtility.isNullOrEmpty(bucketName) || StringUtility.isNullOrEmpty(objectPath)) {
      throw new BadRequestException("BucketName and objectPath cannot be null");
    }

    if (bucketName.contains("/")) {
      throw new BadRequestException("Invalid bucketName");
    }

    if (objectPath.startsWith("/") || objectPath.endsWith("/")) {
      // The client library should have validated/normalized the object path before calling the
      // backend.
      throw new BadRequestException("Invalid objectPath");
    }
  }

  /**
   * Reserves the blob name for given user and stores the access mode for this blob if the name (the
   * combination of bucketName and objectPath) is available or is owned by this user.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   * @param accessMode controls how the uploaded blob can be accessed.
   * @param user the user making the request.
   * @return false if the object already exists and is owned by a different user; true otherwise.
   */
  private boolean reserveNameIfAvailable(
      String bucketName, String objectPath, BlobAccessMode accessMode, User user) {

    return BlobManager.tryStoreBlobMetadata(bucketName, objectPath, accessMode, getUserId(user));
  }

  /**
   * Checks user's permissions to read a blob and throws an exception if user doesn't have
   * permissions.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   * @param user the user making the request.
   * @throws UnauthorizedException if the user is not authorized.
   * @throws NotFoundException if the object doesn't exist.
   */
  private void checkReadObjectPermissions(String bucketName, String objectPath, User user)
      throws UnauthorizedException, NotFoundException {
    BlobMetadata metadata = BlobManager.getBlobMetadata(bucketName, objectPath);
    if (metadata == null) {
      throw new NotFoundException("Blob doesn't exist.");
    }

    if (getUserId(user).equals(metadata.getOwnerId())) {
      // User is the owner so the read operation is allowed regardless of the access mode.
      return;
    }

    if (metadata.getAccessMode() != BlobAccessMode.PUBLIC_READ
        && metadata.getAccessMode() != BlobAccessMode.PUBLIC_READ_FOR_APP_USERS) {
      throw new UnauthorizedException("You don't have permissions to download this object");
    }
  }

  /**
   * Checks user's permissions to delete a blob and throws an exception if user doesn't have
   * permissions.
   *
   * @param bucketName Google Cloud Storage bucket where the object was uploaded.
   * @param objectPath path to the object in the bucket.
   * @param user the user making the request.
   * @return true if the object may exist and delete operation should proceed; false otherwise.
   * @throws UnauthorizedException if the user is not authorized.
   */
  private boolean checkDeletePermissions(String bucketName, String objectPath, User user)
      throws UnauthorizedException {
    BlobMetadata metadata = BlobManager.getBlobMetadata(bucketName, objectPath);
    if (metadata == null) {
      return false;
    }

    if (getUserId(user).equals(metadata.getOwnerId())) {
      // User is the owner.
      return true;
    }

    throw new UnauthorizedException("You don't have permissions to delete this object");
  }

  /**
   * Deletes all blob information.
   *
   * @param bucketName Google Cloud Storage bucket for this blob.
   * @param objectPath path to the object in the bucket.
   * @return true if the operation succeeded; false otherwise.
   */
  private boolean deleteAllBlobInformation(String bucketName, String objectPath) {
    return BlobManager.deleteBlob(bucketName, objectPath);
  }

  private String getUserId(User user) {
    if (user == null) {
      // Mobile Backend is configured in Open Mode and the requests are unauthenticated.
      return OPEN_MODE_USER_ID;
    } else {
      return user.getEmail().toLowerCase();
    }
  }
}
