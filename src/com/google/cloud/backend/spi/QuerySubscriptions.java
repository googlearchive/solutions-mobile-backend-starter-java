package com.google.cloud.backend.spi;

import java.util.List;

/**
 * Class manages CloudEntities query unsubscription.
 */
public class QuerySubscriptions {

  private static final QuerySubscriptions _instance = new QuerySubscriptions();

  /**
   * Returns an instance of {@link QuerySubscriptions}.
   *
   * @return a singleton
   */
  public static QuerySubscriptions getInstance() {
    return _instance;
  }

  private QuerySubscriptions() {
  }

  /**
   * Remove all subscriptions for this device.
   *
   * @param deviceId a unique identifier for the device
   */
  protected void unsubscribe(String deviceId) {
    List<String> id = SubscriptionUtility.extractRegIdAsList(deviceId);
    SubscriptionUtility.clearSubscriptionAndDeviceEntity(id);
  }
}
