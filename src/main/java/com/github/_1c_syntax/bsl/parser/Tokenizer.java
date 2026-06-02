package com.github._1c_syntax.bsl.parser;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.List;

public abstract class Tokenizer<T extends ParseTree, P extends Parser> {

  protected final String content;
  protected final Lexer lexer;
  protected final P parser;

  @SuppressWarnings("unchecked")
  public Tokenizer(String content, Lexer lexer, Class<P> parserClass) {
    this.content = content;
    this.lexer = lexer;
    this.lexer.setInputStream(CharStreams.fromString(content));
    CommonTokenStream tokenStream = new CommonTokenStream(this.lexer);
    try {
      this.parser = parserClass.getConstructor(org.antlr.v4.runtime.TokenStream.class).newInstance(tokenStream);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create parser instance", e);
    }
  }

  public List<Token> getTokens() {
    lexer.setInputStream(CharStreams.fromString(content));
    CommonTokenStream tokenStream = new CommonTokenStream(lexer);
    tokenStream.fill();
    return tokenStream.getTokens();
  }

  public T getAst() {
    lexer.setInputStream(CharStreams.fromString(content));
    CommonTokenStream tokenStream = new CommonTokenStream(lexer);
    parser.setTokenStream(tokenStream);
    return rootAST();
  }

  protected abstract T rootAST();
}
