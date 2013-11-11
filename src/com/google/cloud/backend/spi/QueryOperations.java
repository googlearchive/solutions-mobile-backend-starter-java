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
import com.google.cloud.backend.config.StringUtility;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Utility class that provides query operations for CloudEntities. Uses Search
 * API and Datastore as backend.
 */
public class QueryOperations {

  /**
   * Name of Topic for GCM that will be used in CloudBackend as default.
   */
  public static final String PROS_SEARCH_DEFAULT_TOPIC = "defaultTopic";

  // by default subscription will not expire, which is indicated with a duration of 0 second
  private static final int PROS_SEARCH_DURATION_SEC = 0;

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

  public EntityListDto processQueryRequest(QueryDto queryDto, User user) {
    if (queryDto == null) {
      throw new IllegalArgumentException("queryDto cannot be null.");
    }

    if (StringUtility.isNullOrEmpty(queryDto.getRegId()) && queryDto.getScope() != Scope.PAST) {
      throw new IllegalArgumentException(
          "queryDto.regId cannot be null when scope includes FUTURE queries.");
    }

    // execute query for past entities
    EntityListDto cdl;
    if (queryDto.getScope() == Scope.PAST || queryDto.getScope() == Scope.FUTURE_AND_PAST) {
      cdl = executeQuery(queryDto, user);
    } else {
      cdl = new EntityListDto(); // empty
    }

    // add subscriber for future updates
    if (queryDto.getScope() == Scope.FUTURE || queryDto.getScope() == Scope.FUTURE_AND_PAST) {
      addQuerySubscriber(queryDto);
    }

    return cdl;
  }

  private EntityListDto executeQuery(QueryDto queryDto, User user) {
    // check if kindName is not the config kinds
    SecurityChecker.getInstance().checkIfKindNameAccessible(queryDto.getKindName());

    // create Query
    Query q = SecurityChecker.getInstance().createKindQueryWithNamespace(queryDto.getKindName(),
        user);
    q.setKeysOnly();

    // set filters
    FilterDto cf = queryDto.getFilterDto();
    if (cf != null) {
      q.setFilter(cf.getDatastoreFilter());
    }

    // add sort orders
    if (queryDto.getSortedPropertyName() != null) {
      q.addSort(queryDto.getSortedPropertyName(),
          queryDto.isSortAscending() ? SortDirection.ASCENDING : SortDirection.DESCENDING);
    }

    // add limit
    FetchOptions fo;
    if (queryDto.getLimit() != null && queryDto.getLimit() > 0) {
      fo = FetchOptions.Builder.withLimit(queryDto.getLimit());
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

  private void addQuerySubscriber(QueryDto queryDto) {
    String queryId = queryDto.getQueryId();
    String regId = queryDto.getRegId();
    String subId = SubscriptionUtility.constructSubId(regId, queryId);

    // build ProsSearch query and schema
    String query = queryDto.buildProsSearchQuery();
    Map<String, FieldType> schema = queryDto.buildProsSearchSchema();

    // subscribe
    int duration = queryDto.getSubscriptionDurationSec() == null ? PROS_SEARCH_DURATION_SEC
        : queryDto.getSubscriptionDurationSec();
    prosSearch.subscribe(PROS_SEARCH_DEFAULT_TOPIC, subId, duration, query, schema);
    log.info("addQuerySubscriber: query: " + query + ", schema: " + schema + ", duration: "
        + duration);

    // Add a deviceSubscription entity which can be pulled later for subscription id clean up
    DeviceSubscription deviceSubscription = new DeviceSubscription();
    deviceSubscription.create(SubscriptionUtility.getMobileType(subId), regId, subId);
  }
}
