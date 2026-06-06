package com.github._1c_syntax.bsl.parser.sdql.api;

public class NodeNotFoundException extends SdqlApiException {

  public NodeNotFoundException(String message) {
    super("NODE_NOT_FOUND", message);
  }
}
