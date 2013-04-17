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

import com.google.appengine.api.prospectivesearch.FieldType;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds AST (abstract syntax tree) for a query for CloudEntity.
 *
 */
public class QueryDto {

  public enum Scope {
    PAST, FUTURE, FUTURE_AND_PAST
  }

  private String kindName;

  private FilterDto cbFilter;

  private String sortedPropertyName;

  private boolean isSortAscending;

  private Integer limit;

  private Scope scope;

  private String regId;

  private String queryId;

  private Integer subscriptionDurationSec;

  public Integer getLimit() {
    return limit;
  }

  public void setLimit(Integer limit) {
    this.limit = limit;
  }

  public FilterDto getFilterDto() {
    return cbFilter;
  }

  public void setFilterDto(FilterDto filter) {
    this.cbFilter = filter;
  }

  public String getSortedPropertyName() {
    return sortedPropertyName;
  }

  public void setSortedPropertyName(String sort) {
    this.sortedPropertyName = sort;
  }

  public String getKindName() {
    return kindName;
  }

  public void setKindName(String kindName) {
    this.kindName = kindName;
  }

  @Override
  public String toString() {
    return "QueryDto(" + kindName + "): filters: " + cbFilter + ", sortedPropertyName: "
        + sortedPropertyName;
  }

  public boolean isSortAscending() {
    return isSortAscending;
  }

  public void setSortAscending(boolean isSortAscending) {
    this.isSortAscending = isSortAscending;
  }

  public String getRegId() {
    return regId;
  }

  public void setRegId(String regId) {
    this.regId = regId;
  }

  public String getQueryId() {
    return queryId;
  }

  public void setQueryId(String queryId) {
    this.queryId = queryId;
  }

  /**
   * Builds a query string for Prospective Search API for this query.
   *
   * @return query string for Prospective Search API.
   */
  public String buildProsSearchQuery() {

    // add condition for _kindName property
    StringBuilder sb = new StringBuilder();
    sb.append("(" + EntityDto.PROP_KIND_NAME + ":\"" + this.kindName + "\")");

    // add conditions for the filters
    if (this.cbFilter != null) {
      sb.append(" AND " + this.cbFilter.buildProsSearchQuery());
    }
    return sb.toString();
  }

  /**
   * Builds a Map schema for Prospective Search API for this query.
   *
   * @return a schema as a Map for Prospective Search API.
   */
  public Map<String, FieldType> buildProsSearchSchema() {

    // add schema for _kindName property
    Map<String, FieldType> m = new HashMap<String, FieldType>();
    m.put(EntityDto.PROP_KIND_NAME, FieldType.STRING);

    // add schemas for properties in the filters
    if (this.cbFilter != null) {
      m.putAll(this.cbFilter.buildProsSearchSchema());
    }
    return m;
  }

  public Scope getScope() {
    return scope;
  }

  public void setScope(Scope scope) {
    this.scope = scope;
  }

  public Integer getSubscriptionDurationSec() {
    return subscriptionDurationSec;
  }

  public void setSubscriptionDurationSec(Integer subscriptionDurationSec) {
    this.subscriptionDurationSec = subscriptionDurationSec;
  }

}
