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
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.SortDirection;
import com.google.appengine.api.prospectivesearch.FieldType;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchService;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchServiceFactory;
import com.google.appengine.api.users.User;
import com.google.cloud.backend.beans.EntityDto;
import com.google.cloud.backend.beans.EntityListDto;
import com.google.cloud.backend.beans.FilterDto;
import com.google.cloud.backend.beans.QueryDto;
import com.google.cloud.backend.beans.QueryDto.Scope;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Utility class that provides query operations for CloudEntities. Uses Search
 * API and Datastore as backend.
 *
 */
public class QueryOperations {

  /**
   * Specifies TypeId (ID used in CloudBackend to identify type of each GCM
   * message).
   */
  protected static final String GCM_TYPEID_QUERY = "query";

  /**
   * Name of Topic for GCM that will be used in CloudBackend as default.
   */
  public static final String PROS_SEARCH_DEFAULT_TOPIC = "defaultTopic";

  // subscription will expires in 24 hours
  private static final int PROS_SEARCH_DURATION_SEC = 60 * 60 * 24;

  private static final DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

  private static final ProspectiveSearchService prosSearch = ProspectiveSearchServiceFactory
      .getProspectiveSearchService();

  private static final Logger log = Logger.getLogger(QueryOperations.class.getCanonicalName());

  /**
   * Returns the Singleton instance.
   */
  public static QueryOperations getInstance() {
    return _instance;
  }

  private static final QueryOperations _instance = new QueryOperations();

  private QueryOperations() {
  }

  public EntityListDto list(QueryDto QueryDto, User user) {

    // execute query for past entities
    EntityListDto cdl;
    if (QueryDto.getScope() == Scope.PAST || QueryDto.getScope() == Scope.FUTURE_AND_PAST) {
      cdl = executeQuery(QueryDto, user);
    } else {
      cdl = new EntityListDto(); // empty
    }

    // add subscriber for future updates
    if (QueryDto.getScope() == Scope.FUTURE || QueryDto.getScope() == Scope.FUTURE_AND_PAST) {
      addQuerySubscriber(QueryDto);
    }
    return cdl;
  }

  private EntityListDto executeQuery(QueryDto QueryDto, User user) {

    // check if kindName is not the config kinds
    SecurityChecker.getInstance().checkIfKindNameAccessible(QueryDto.getKindName());

    // create Query
    Query q = SecurityChecker.getInstance().createKindQueryWithNamespace(QueryDto.getKindName(),
        user);
    q.setKeysOnly();

    // set filters
    FilterDto cf = QueryDto.getFilterDto();
    if (cf != null) {
      q.setFilter(cf.getDatastoreFilter());
    }

    // add sort orders
    if (QueryDto.getSortedPropertyName() != null) {
      q.addSort(QueryDto.getSortedPropertyName(),
          QueryDto.isSortAscending() ? SortDirection.ASCENDING : SortDirection.DESCENDING);
    }

    // add limit
    FetchOptions fo;
    if (QueryDto.getLimit() != null && QueryDto.getLimit() > 0) {
      fo = FetchOptions.Builder.withLimit(QueryDto.getLimit());
    } else {
      fo = FetchOptions.Builder.withDefaults();
    }

    // execute the query
    List<Entity> results = datastore.prepare(q).asList(fo);

    // get entities from the keys
    List<Key> keyList = new LinkedList<Key>();
    for (Entity e : results) {
      keyList.add(e.getKey());
    }
    Map<String, Entity> resultEntities = CrudOperations.getInstance().getAllEntitiesByKeyList(
        keyList);

    // convert the Entities to CbDtos
    EntityListDto cdl = new EntityListDto();
    for (Entity keyOnlyEntity : results) {
      Entity e = resultEntities.get(keyOnlyEntity.getKey().getName());
      cdl.getEntries().add(EntityDto.createFromEntity(e));
    }
    return cdl;
  }

  private void addQuerySubscriber(QueryDto QueryDto) {

    // ProsSearch subId = <regId>:query:<clientSubId>
    String subId = QueryDto.getRegId() + ":" + GCM_TYPEID_QUERY + ":" + QueryDto.getQueryId();

    // build ProsSearch query and schema
    // TODO: the query should include the ACL filters too
    String query = QueryDto.buildProsSearchQuery();
    Map<String, FieldType> schema = QueryDto.buildProsSearchSchema();

    // subscribe
    int duration = QueryDto.getSubscriptionDurationSec() == null ? PROS_SEARCH_DURATION_SEC
        : QueryDto.getSubscriptionDurationSec();
    prosSearch.subscribe(PROS_SEARCH_DEFAULT_TOPIC, subId, duration, query, schema);
    log.info("addQuerySubscriber: query: " + query + ", schema: " + schema + ", duration: "
        + duration);
  }
}
