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

import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.NamespaceManager;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.users.User;
import com.google.apphosting.api.ApiProxy;
import com.google.cloud.backend.beans.EntityDto;
import com.google.cloud.backend.config.BackendConfigManager;
import com.google.cloud.backend.config.BackendConfigManager.AuthMode;
import com.google.cloud.backend.config.CloudEndpointsConfigManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manager class that provides utility methods for access control on
 * CloudEntities.
 *
 */
public class SecurityChecker {

  public static final String KIND_NAME_USERS = "_Users";

  public static final String USERS_PROP_USERID = "userId";

  public static final String USER_ID_PREFIX = "USER:";

  public static final String USER_ID_FOR_ANONYMOUS = USER_ID_PREFIX + "<anonymous>";

  public static final String NAMESPACE_DEFAULT = ""; // empty namespace

  public static final String KIND_PREFIX_PRIVATE = "[private]";

  public static final String KIND_PREFIX_PUBLIC = "[public]";

  private static final SecurityChecker _instance = new SecurityChecker();

  /**
   * Returns the Singleton instance.
   */
  public static SecurityChecker getInstance() {
    return _instance;
  }

  private static final DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

  private static final MemcacheService memcache = MemcacheServiceFactory.getMemcacheService();

  private static final BackendConfigManager backendConfigManager = new BackendConfigManager();

  private static final Map<String, String> userIdCache = new HashMap<String, String>();

  private SecurityChecker() {
  }

  /**
   * Checks if the user is allowed to use the backend. The method throws
   * {@link UnauthorizedException} if the backend is locked down or if the user
   * is null and the authentication through Client IDs is enabled.
   *
   * @param user
   *          {@link User} on behalf of which the call is made from the client.
   * @throws UnauthorizedException
   *           if the call is not authenticated because of the status of the
   *           authMode or the User.
   */
  protected void checkIfUserIsAvailable(User user) throws UnauthorizedException {

    AuthMode authMode = backendConfigManager.getAuthMode();
    switch (authMode) {
    case OPEN: // no check
      return;
    case CLIENT_ID: // error if User is null
      if (user == null) {
        throw new UnauthorizedException("Unauthenticated calls are not allowed");
      } else {
        return;
      }
    case LOCKED: // always error
    default:
      throw new UnauthorizedException("The backend is locked down. The administrator can change "
          + "the authentication/authorization settings on https://" + getHostname() + "/");
    }
  }

  private String getHostname() {
    return (String) ApiProxy.getCurrentEnvironment().getAttributes()
        .get("com.google.appengine.runtime.default_version_hostname");
  }

  /**
   * Checks ACL of the specified CloudEntity to see if the specified user can
   * write on it.
   *
   * @param e
   *          {@link Entity} of CloudEntity
   * @param user
   *          User object representing the caller.
   * @throws UnauthorizedException
   *           if the user does not have permission to write on the entity
   */
  protected void checkAclForWrite(Entity e, User user) throws UnauthorizedException {

    // get ACL
    String userId = getUserId(user);
    String ownerId = (String) e.getProperty(EntityDto.PROP_OWNER);

    // check ACL
    boolean isOwner = userId.equals(ownerId);
    boolean isPublic = e.getKind().startsWith(KIND_PREFIX_PUBLIC);
    boolean isWritable = isOwner || isPublic;
    if (!isWritable) {
      String id = e.getKey().getName();
      throw new UnauthorizedException("Insuffient permission for updating a CloudEntity: " + id
          + " by: " + userId);
    }
  }

  /**
   * Sets default security properties on the specified {@link EntityDto}.
   *
   * @param cd
   *          {@link EntityDto}
   * @param user
   *          {@link User} of the creator of CloudEntity
   */
  protected void setDefaultSecurityProps(EntityDto cd, User user) {

    // set createdBy and updatedBy
    if (user != null) {
      cd.setCreatedBy(user.getEmail());
      cd.setUpdatedBy(user.getEmail());
    }

    // set owner
    cd.setOwner(getUserId(user));
  }

  /**
   * Creates a {@link Key} from the specified kindName and CloudEntity id. If
   * the kindName has _private suffix, the key will be created under a namespace
   * for the specified {@link User}.
   *
   * @param kindName
   *          Name of kind
   * @param id
   *          CloudEntity id
   * @param user
   *          {@link User} of the requestor
   * @return {@link Key}
   */
  public Key createKeyWithNamespace(String kindName, String id, User user) {

    // save the original namespace
    String origNamespace = NamespaceManager.get();

    // set namespace based on the kind suffix
    if (kindName.startsWith(SecurityChecker.KIND_PREFIX_PRIVATE)) {
      String userId = getUserId(user);
      NamespaceManager.set(userId);
    } else {
      NamespaceManager.set(SecurityChecker.NAMESPACE_DEFAULT);
    }

    // create a key
    Key k = KeyFactory.createKey(kindName, id);

    // restore the original namespace
    NamespaceManager.set(origNamespace);
    return k;
  }

  /**
   * Creates {@link Query} from the specified kindName. If the kindName has
   * _private suffix, the key will be created under a namespace for the
   * specified {@link User}.
   *
   * @param kindName
   *          Name of kind
   * @param user
   *          {@link User} of the requestor
   * @return {@link Query}
   */
  public Query createKindQueryWithNamespace(String kindName, User user) {

    // save the original namespace
    String origNamespace = NamespaceManager.get();

    // set namespace based on the kind suffix
    if (kindName.startsWith(KIND_PREFIX_PRIVATE)) {
      String userId = getUserId(user);
      NamespaceManager.set(userId);
    } else {
      NamespaceManager.set(NAMESPACE_DEFAULT);
    }

    // create a key
    Query q = new Query(kindName);

    // restore the original namespace
    NamespaceManager.set(origNamespace);
    return q;
  }

  private String getUserId(User u) {

    // check if User is available
    if (u == null) {
      return USER_ID_FOR_ANONYMOUS;
    }

    // check if valid email is available
    String email = u.getEmail();
    if (email == null || email.trim().length() == 0) {
      throw new IllegalArgumentException("Illegal email: " + email);
    }

    // try to find it on local cache
    String memKey = USER_ID_PREFIX + email;
    String id = userIdCache.get(memKey);
    if (id != null) {
      return id;
    }

    // try to find it on memcache
    id = (String) memcache.get(memKey);
    if (id != null) {
      userIdCache.put(memKey, id);
      return id;
    }

    // create a key to find the user on Datastore
    String origNamespace = NamespaceManager.get();
    NamespaceManager.set(NAMESPACE_DEFAULT);
    Key key = KeyFactory.createKey(KIND_NAME_USERS, email);
    NamespaceManager.set(origNamespace);

    // try to find it on Datastore
    Entity e;
    try {
      e = datastore.get(key);
      id = (String) e.getProperty(USERS_PROP_USERID);
    } catch (EntityNotFoundException ex) {
      // when the user has not been registered
      e = new Entity(key);
      id = USER_ID_PREFIX + UUID.randomUUID().toString();
      e.setProperty(USERS_PROP_USERID, id);
      datastore.put(e);
    }

    // put the user on memcache and local cache
    userIdCache.put(memKey, id);
    memcache.put(memKey, id);
    return id;
  }

  /**
   * Checks if the specified kind name is not one of system configuration kinds
   * and is allowed to access.
   *
   * @param kindName
   * @throws IllegalArgumentException
   *           if the kind name is not accessible
   */
  public void checkIfKindNameAccessible(String kindName) {
    if (BackendConfigManager.CONFIGURATION_ENTITY_KIND.equals(kindName)
        || CloudEndpointsConfigManager.ENDPOINT_CONFIGURATION_KIND.equals(kindName)) {
      throw new IllegalArgumentException("save/saveAll: the kind name is not allowed to access: "
          + kindName);
    }
  }

}
