package com.banking.creditservice.application.query;

/** Bounded zero-based pagination shared by the list queries. */
public record PageRequest(int page, int size) {

  public static final int DEFAULT_PAGE = 0;
  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_PAGE = 10_000;
  public static final int MAX_SIZE = 100;

  /** Rejects a page outside 0 to {@link #MAX_PAGE} and a size outside 1 to {@link #MAX_SIZE}. */
  public PageRequest {
    if (page < 0 || page > MAX_PAGE) {
      throw new IllegalArgumentException("page must be between 0 and " + MAX_PAGE);
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
    }
  }

  /** Builds a page request applying the defaults for missing values. */
  public static PageRequest of(Integer page, Integer size) {
    return new PageRequest(page == null ? DEFAULT_PAGE : page, size == null ? DEFAULT_SIZE : size);
  }

  /** Rows to skip. */
  public long offset() {
    return (long) page * size;
  }
}
