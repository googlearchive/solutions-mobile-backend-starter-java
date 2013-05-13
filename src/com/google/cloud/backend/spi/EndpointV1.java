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
import com.google.api.server.spi.config.ApiNamespace;
import com.google.api.server.spi.response.NotFoundException;
import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.users.User;
import com.google.cloud.backend.beans.EntityDto;
import com.google.cloud.backend.beans.EntityListDto;
import com.google.cloud.backend.beans.QueryDto;

import javax.inject.Named;

/**
 * An endpoint for all CloudBackend requests.
 *
 */
@Api(name = "mobilebackend", namespace = @ApiNamespace(ownerDomain = "google.com",
    ownerName = "google.com", packagePath = "cloud.backend.android"),
    useDatastoreForAdditionalConfig = AnnotationBoolean.TRUE)
public class EndpointV1 {

  /**
   * Inserts a CloudEntity on the backend. If it does not have any Id, it
   * creates a new Entity. If it has, find the existing entity and update it.
   *
   * @param cd
   *          {@link EntityDto} for inserting a CloudEntity.
   * @param user
   *          {@link User} who called this request.
   * @return {@link EntityDto} that has updated fields (like updatedAt and new
   *         Id).
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   */
  @ApiMethod(path = "CloudEntities/insert/{kind}", httpMethod = "POST")
  public EntityDto insert(@Named("kind") String kindName, EntityDto cd, User user)
      throws UnauthorizedException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    EntityListDto cdl = new EntityListDto();
    cdl.add(cd);
    CrudOperations.getInstance().saveAll(cdl, user);
    return cd;
  }

  /**
   * Updates a CloudEntity on the backend. If it does not have any Id, it
   * creates a new Entity. If it has, find the existing entity and update it.
   *
   * @param cd
   *          {@link EntityDto} for inserting/updating a CloudEntity.
   * @param user
   *          {@link User} who called this request.
   * @return {@link EntityDto} that has updated fields (like updatedAt and new
   *         Id).
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   */
  @ApiMethod(path = "CloudEntities/update/{kind}", httpMethod = "POST")
  public EntityDto update(@Named("kind") String kindName, EntityDto cd, User user)
      throws UnauthorizedException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    EntityListDto cdl = new EntityListDto();
    cdl.add(cd);
    CrudOperations.getInstance().saveAll(cdl, user);
    return cd;
  }

  /**
   * Inserts multiple CloudEntities on the backend. Works just the same as
   * {@link EndpointV1#insert(String, EntityDto, User)}
   *
   * @param cdl
   *          {@link EntityListDto} that holds {@link EntityDto}s to save.
   * @param user
   *          {@link User} who called this request.
   * @return {@link EntityListDto} that has updated {@link EntityDto}s.
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   */
  @ApiMethod(path = "CloudEntities/insertAll", httpMethod = "POST")
  // the path need to include the op name to distinguish between saveAll and
  // getAll.
  public EntityListDto insertAll(EntityListDto cdl, User user) throws UnauthorizedException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    return CrudOperations.getInstance().saveAll(cdl, user);
  }

  /**
   * Updates multiple CloudEntities on the backend. Works just the same as
   * {@link EndpointV1#update(String, EntityDto, User)}
   *
   * @param cdl
   *          {@link EntityListDto} that holds {@link EntityDto}s to save.
   * @param user
   *          {@link User} who called this request.
   * @return {@link EntityListDto} that has updated {@link EntityDto}s.
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   */
  @ApiMethod(path = "CloudEntities/updateAll", httpMethod = "POST")
  // the path need to include the op name to distinguish between saveAll and
  // getAll.
  public EntityListDto updateAll(EntityListDto cdl, User user) throws UnauthorizedException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    return CrudOperations.getInstance().saveAll(cdl, user);
  }

  /**
   * Finds the CloudEntity specified by its Id.
   *
   * @param kindName
   *          Name of the kind for the CloudEntity to get.
   * @param id
   *          Id of the CloudEntity to find.
   * @return {@link EntityDto} of the found CloudEntity.
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   * @throws NotFoundException
   *           if the requested CloudEntity has not found
   */
  @ApiMethod(path = "CloudEntities/{kind}/{id}", httpMethod = "GET")
  public EntityDto get(@Named("kind") String kindName, @Named("id") String id, User user)
      throws UnauthorizedException, NotFoundException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    return CrudOperations.getInstance().getEntity(kindName, id, user);
  }

  /**
   * Finds all the CloudEntities specified by the {@link EntityListDto} of Ids.
   *
   * @param cdl
   *          {@link EntityListDto} that contains a list of Ids to get.
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   */
  @ApiMethod(path = "CloudEntities/getAll", httpMethod = "POST")
  public EntityListDto getAll(EntityListDto cdl, User user) throws UnauthorizedException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    return CrudOperations.getInstance().getAllEntities(cdl, user);
  }

  /**
   * Deletes the CloudEntity specified by its Id.
   *
   * @param kindName
   *          Name of the kind for the CloudEntity to delete.
   * @param id
   *          Id of the CloudEntity to delete.
   * @return {@link EntityDto} a dummy object (Endpoints requires to return any
   *         bean object).
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   */
  @ApiMethod(path = "CloudEntities/{kind}/{id}", httpMethod = "DELETE")
  public EntityDto delete(@Named("kind") String kindName, @Named("id") String id, User user)
      throws UnauthorizedException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    return CrudOperations.getInstance().delete(kindName, id, user);
  }

  /**
   * Deletes all the CloudEntities specified by the List of Ids.
   *
   * @param cdl
   *          {@link EntityListDto} that contains a list of Ids to delete.
   * @return {@link EntityListDto} of a dummy {@link EntityDto}s (Endpoints
   *         requires to return any bean object).
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   */
  @ApiMethod(path = "CloudEntities/deleteAll", httpMethod = "POST")
  // DELETE can't have content body
  public EntityListDto deleteAll(EntityListDto cdl, User user) throws UnauthorizedException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    return CrudOperations.getInstance().deleteAll(cdl, user);
  }

  /**
   * Executes a query.
   *
   * @param cbQuery
   *          {@link QueryDto} to execute.
   * @param user
   *          {@link User} who requested this operation
   * @return {@link EntityListDto} that contains the result {@link EntityDto}s.
   * @throws UnauthorizedException
   *           if the requesting {@link User} has no sufficient permission for
   *           the operation.
   */
  @ApiMethod(path = "CloudEntities/list", httpMethod = "POST")
  public EntityListDto list(QueryDto cbQuery, User user) throws UnauthorizedException {

    SecurityChecker.getInstance().checkIfUserIsAvailable(user);
    return QueryOperations.getInstance().list(cbQuery, user);
  }
}
