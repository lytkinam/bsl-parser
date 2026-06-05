package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class RestoredQueryNode {
  private int id;
  private String name;
  private String type;
  private String unionType;
  private String into;
  private List<String> selectExpressions = new ArrayList<>();
  private List<DataSource> from = new ArrayList<>();
  private List<RestoredJoin> joins = new ArrayList<>();
  private List<String> whereConditions = new ArrayList<>();
  private List<String> groupByFields = new ArrayList<>();
  private List<String> havingConditions = new ArrayList<>();
  private List<String> orderByFields = new ArrayList<>();
  private String limitations;
  private List<RestoredQueryNode> unionParts = new ArrayList<>();
  /** Inline subqueries by name (from from[].subquery) */
  private Map<String, RestoredQueryNode> inlineSubqueries = new HashMap<>();
  /** Inline subqueries referenced in WHERE conditions */
  private Map<String, RestoredQueryNode> whereSubqueries = new HashMap<>();
  /** Inline subqueries referenced in virtual table parameters */
  private Map<String, RestoredQueryNode> vtSubqueries = new HashMap<>();
}
