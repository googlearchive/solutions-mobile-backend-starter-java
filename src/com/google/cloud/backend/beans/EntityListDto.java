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
package com.google.cloud.backend.beans;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.users.User;

import java.util.LinkedList;
import java.util.List;

/**
 * Provides a container for multiple {@link EntityDto} objects.
 *
 */
public class EntityListDto {

  private List<EntityDto> entries = new LinkedList<EntityDto>();

  public List<EntityDto> getEntries() {
    return entries;
  }

  public void setEntries(List<EntityDto> entries) {
    this.entries = entries;
  }

  /**
   * Returns a List of Ids of all {@link EntityDto}s.
   *
   * @return {@link List} of Ids.
   */
  public List<String> readIdList() {
    List<String> idList = new LinkedList<String>();
    for (EntityDto cd : this.getEntries()) {
      idList.add(cd.getId());
    }
    return idList;
  }

  /**
   * Returns a List of {@link Key}s for all {@link EntityDto}s.
   *
   * @return List of {@link Key}s for all {@link EntityDto}s.
   */
  public List<Key> readKeyList(User user) {
    List<Key> keys = new LinkedList<Key>();
    for (EntityDto cd : this.getEntries()) {
      keys.add(cd.readEntityKey(user));
    }
    return keys;
  }

  /**
   * Adds the specified {@link EntityDto} to the list.
   *
   * @param cd
   *          {@link EntityDto} to add.
   */
  public void add(EntityDto cd) {
    this.entries.add(cd);
  }

}
