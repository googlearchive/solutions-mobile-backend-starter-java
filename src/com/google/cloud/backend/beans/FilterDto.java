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

import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.CompositeFilter;
import com.google.appengine.api.datastore.Query.CompositeFilterOperator;
import com.google.appengine.api.datastore.Query.Filter;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.prospectivesearch.FieldType;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;

/**
 * Represents an AST (abstract syntax tree) made of filters that represents a
 * query filter for a {@link QueryDto}. Equivalent to {@link FilterPredicate}
 * and {@link CompositeFilter} of Datastore query.
 *
 * The values property takes different number of elements depending on the Op:
 *
 * - For EQ, LT, LE, GT, GE, NE: This object works as a filter predicate. Values
 * should have two values, where the first value should be a property name and
 * the second value should be a value.
 *
 * - For IN: This object works as a filter predicate with IN op. Values may have
 * any number of values.
 *
 * - For AND, OR: This works as a composite filter. Values may have any number
 * of FilterDto instances.
 *
 * TODO: This kind of behaviors should be implemented by polymorphism, but
 * Endpoints doesn't support inheritance.
 *
 */
public class FilterDto {

  private static final DatatypeFactory datatypeFactory;

  static {
    try {
      datatypeFactory = DatatypeFactory.newInstance();
    } catch (DatatypeConfigurationException e) {
      throw new RuntimeException(e);
    }
  }

  public enum Op {
    EQ, LT, LE, GT, GE, NE, IN, AND, OR
  }

  private List<Object> values;

  private List<FilterDto> subfilters;

  private Op operator;

  public Op getOperator() {
    return operator;
  }

  public void setOperator(Op operator) {
    this.operator = operator;
  }

  public List<Object> getValues() {
    return values;
  }

  public void setValues(List<Object> values) {
    this.values = values;
  }

  /**
   * Converts the tree of {@link FilterDto}s to a tree of {@link FilterDto}s.
   */
  public Filter getDatastoreFilter() {

    switch (this.operator) {
    case EQ:
      return new Query.FilterPredicate(getPropName(), Query.FilterOperator.EQUAL, getOperand());
    case LT:
      return new Query.FilterPredicate(getPropName(), Query.FilterOperator.LESS_THAN, getOperand());
    case LE:
      return new Query.FilterPredicate(getPropName(), Query.FilterOperator.LESS_THAN_OR_EQUAL,
          getOperand());
    case GT:
      return new Query.FilterPredicate(getPropName(), Query.FilterOperator.GREATER_THAN,
          getOperand());
    case GE:
      return new Query.FilterPredicate(getPropName(), Query.FilterOperator.GREATER_THAN_OR_EQUAL,
          getOperand());
    case NE:
      return new Query.FilterPredicate(getPropName(), Query.FilterOperator.NOT_EQUAL, getOperand());
    case IN:
      LinkedList<Object> l = new LinkedList<Object>(values);
      l.removeFirst();
      return new Query.FilterPredicate(getPropName(), Query.FilterOperator.IN, l);
    case AND:
      return new Query.CompositeFilter(CompositeFilterOperator.AND, getSubfilters(subfilters));
    case OR:
      return new Query.CompositeFilter(CompositeFilterOperator.OR, getSubfilters(subfilters));
    }
    return null;
  }

  private String getPropName() {
    return (String) values.get(0);
  }

  // returns Date if the operand is JSON date string
  private Object getOperand() {
    String s = String.valueOf(values.get(1));
    long t = convertJSONDateToEpochTime(s);
    return t == 0 ? values.get(1) : new Date(t);
  }

  private List<Filter> getSubfilters(List<FilterDto> values) {
    List<Filter> subfilters = new LinkedList<Query.Filter>();
    for (FilterDto cb : values) {
      subfilters.add(cb.getDatastoreFilter());
    }
    return subfilters;
  }

  @Override
  public String toString() {
    return "FilterDto op: " + operator.toString() + ", values: " + values;
  }

  public List<FilterDto> getSubfilters() {
    return subfilters;
  }

  public void setSubfilters(List<FilterDto> subfilters) {
    this.subfilters = subfilters;
  }

  /**
   * Returns Prospective Search query string that is converted form this filter.
   */
  protected String buildProsSearchQuery() {

    switch (this.operator) {
    case EQ:
      return "( " + getPropName() + " : " + getOperandString() + ")";
    case LT:
      return "( " + getPropName() + " < " + getOperandString() + " )";
    case LE:
      return "( " + getPropName() + " <= " + getOperandString() + " )";
    case GT:
      return "( " + getPropName() + " > " + getOperandString() + " )";
    case GE:
      return "( " + getPropName() + " <= " + getOperandString() + " )";
    case NE:
      return "(NOT " + getPropName() + " : " + getOperandString() + ")";
    case IN:
      return buildQueryForOperatorIN();
    case AND:
      return buildQueryForLogicalOperator("AND");
    case OR:
      return buildQueryForLogicalOperator("OR");
    }
    return null;
  }

  private String getOperandString() {
    return getOperandString(1);
  }

  // returns epoch time if the operand is JSON date string
  private String getOperandString(int i) {
    FieldType ft = detectFieldType(values.get(i));
    String s = String.valueOf(values.get(i));
    if (ft == FieldType.STRING || ft == FieldType.TEXT) {
      s = "\"" + s + "\"";
    }
    long t = convertJSONDateToEpochTime(s);
    return t == 0 ? s : String.valueOf(t);
  }

  private String buildQueryForOperatorIN() {
    StringBuilder sb = new StringBuilder();
    sb.append("(");
    for (int i = 1; i < this.values.size(); i++) {
      sb.append("(" + this.getPropName() + " : " + getOperandString(i) + " )");
      if (i != this.values.size() - 1) {
        sb.append(" OR ");
      }
    }
    sb.append(")");
    return sb.toString();
  }

  private String buildQueryForLogicalOperator(String op) {
    StringBuilder sb = new StringBuilder();
    sb.append("(");
    for (int i = 0; i < this.subfilters.size(); i++) {
      sb.append(this.subfilters.get(i).buildProsSearchQuery());
      if (i != this.subfilters.size() - 1) {
        sb.append(" " + op + " ");
      }
    }
    sb.append(")");
    return sb.toString();
  }

  /**
   * Builds schema of Prospective Search for this filter.
   */
  protected Map<String, FieldType> buildProsSearchSchema() {
    Map<String, FieldType> schema = new HashMap<String, FieldType>();
    switch (this.operator) {
    case EQ:
    case LT:
    case LE:
    case GT:
    case GE:
    case NE:
      schema.put(getPropName(), detectFieldType(values.get(1)));
      break;
    case IN: // IN supports only String
      schema.put(getPropName(), FieldType.STRING);
    case AND:
    case OR:
      for (FilterDto cb : this.subfilters) {
        schema.putAll(cb.buildProsSearchSchema());
      }
      break;
    }
    return schema;
  }

  private FieldType detectFieldType(Object value) {
    if (value instanceof Boolean) {
      return FieldType.BOOLEAN;
    } else if (value instanceof Number) {
      if (value instanceof Integer) {
        return FieldType.INT32;
      } else {
        return FieldType.DOUBLE;
      }
    } else {
      boolean isNotJSONDate = convertJSONDateToEpochTime(String.valueOf(value)) == 0;
      if (isNotJSONDate) {
        return FieldType.STRING;
      } else {
        return FieldType.DOUBLE;
      }
    }
  }

  // converts JSON date to epoch time value
  // return 0 if it can't be converted
  private long convertJSONDateToEpochTime(String date) {

    // fast check if date could be a JSON date time
    if (date == null || !date.matches("^\\d.+[Z\\d]$")) {
      return 0;
    }

    // try to convert to epoch time
    try {
      return datatypeFactory.newXMLGregorianCalendar(date).toGregorianCalendar().getTimeInMillis();
    } catch (Throwable th) {
      return 0;
    }
  }
}
