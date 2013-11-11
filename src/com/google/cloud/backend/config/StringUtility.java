package com.google.cloud.backend.config;

import com.google.appengine.api.datastore.Text;

/**
 * Utility class contains utility methods for String type objects.
 */
public class StringUtility {

  private StringUtility() {}

  /**
   * Determine if an input string is null or empty.
   *
   * @param string Input string that might contain null, empty string or non-empty string
   * @return True if the input string is null or empty; false, the otherwise
   */
  public static boolean isNullOrEmpty(String string) {
    return (string == null || string.isEmpty());
  }

  /**
   * Determine if an input text is null or empty.
   *
   * @param text Input text that might contain null, empty text or non-empty text
   * @return True if the input text is null or empty; false, the otherwise
   */
  public static boolean isNullOrEmpty(Text text) {
    return (text == null || text.getValue() == null || text.getValue().isEmpty());
  }
}
