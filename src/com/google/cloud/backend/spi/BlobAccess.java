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

/**
 * Resource representing data for accessing blob objects.
 */
public class BlobAccess {
  private String shortLivedUrl;
  private String accessUrl;
  private String mandatoryHeaders;

  BlobAccess(String shortLivedUrl) {
    this(shortLivedUrl, null, null);
  }

  BlobAccess(String shortLivedUrl, String accessUrl, String mandatoryHeaders) {
    this.shortLivedUrl = shortLivedUrl;
    this.accessUrl = accessUrl;
    this.mandatoryHeaders = mandatoryHeaders;
  }

  /**
   * Gets a time bound URL for the requested operation.
   */
  public String getShortLivedUrl() {
    return shortLivedUrl;
  }

  /**
   * Gets URL that can be used to access public objects or null if the object is not public.
   */
  public String getAccessUrl() {
    return accessUrl;
  }

  /**
   * Gets HTTP headers that need to be included in the HTTP request or null if there are no
   * mandatory headers.
   */
  public String getMandatoryHeaders() {
    return mandatoryHeaders;
  }
}
