package com.github._1c_syntax.bsl.parser.sdql.api;

public class AliasNotFoundException extends SdqlApiException {

  public AliasNotFoundException(String message) {
    super("ALIAS_NOT_FOUND", message);
  }
}
