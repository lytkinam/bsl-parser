package com.github._1c_syntax.bsl.parser.sdql.api;

import lombok.Getter;

@Getter
public class SdqlApiException extends RuntimeException {

  private final String code;

  public SdqlApiException(String code, String message) {
    super(message);
    this.code = code;
  }

  public SdqlApiException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }
}
