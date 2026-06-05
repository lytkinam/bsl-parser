package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsChildField;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsConditionField;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsJoinCondition;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;

import java.util.*;
import java.util.stream.Collectors;

public class TopologicalSorter {

  /**
   * Returns node ids in order from leaves to root (dependencies first).
   * Physical-only leaf nodes (no child_fields with node_id) are excluded.
   */
  public List<Integer> sort(List<FullParsNode> fflNodes) {
    Map<Integer, FullParsNode> nodeById = fflNodes.stream()
      .collect(Collectors.toMap(FullParsNode::getId, n -> n));

    // Build graph: edge from dependent -> dependency
    Map<Integer, Set<Integer>> dependencies = new HashMap<>();
    Map<Integer, Set<Integer>> dependents = new HashMap<>();

    for (FullParsNode node : fflNodes) {
      int nodeId = node.getId();
      dependencies.putIfAbsent(nodeId, new HashSet<>());
      dependents.putIfAbsent(nodeId, new HashSet<>());

      Set<Integer> childNodeIds = collectChildNodeIds(node);
      for (int childId : childNodeIds) {
        if (!nodeById.containsKey(childId)) continue;
        dependencies.get(nodeId).add(childId);
        dependents.putIfAbsent(childId, new HashSet<>());
        dependents.get(childId).add(nodeId);
      }
    }

    // Kahn's algorithm
    // Start with nodes that have no dependencies (or only physical dependencies)
    Queue<Integer> queue = new LinkedList<>();
    Map<Integer, Integer> inDegree = new HashMap<>();

    for (int nodeId : dependencies.keySet()) {
      inDegree.put(nodeId, dependencies.get(nodeId).size());
      if (dependencies.get(nodeId).isEmpty()) {
        queue.add(nodeId);
      }
    }

    List<Integer> result = new ArrayList<>();
    while (!queue.isEmpty()) {
      int current = queue.poll();
      result.add(current);
      for (int dep : dependents.getOrDefault(current, Collections.emptySet())) {
        int newDegree = inDegree.get(dep) - 1;
        inDegree.put(dep, newDegree);
        if (newDegree == 0) {
          queue.add(dep);
        }
      }
    }

    // Reverse: from leaves to root
    // Collections.reverse(result);
    return result;
  }

  private Set<Integer> collectChildNodeIds(FullParsNode node) {
    Set<Integer> result = new HashSet<>();

    // From select child_fields
    if (node.getSelect() != null) {
      for (FullParsSelectField sf : node.getSelect()) {
        if (sf.getChildFields() != null) {
          for (FullParsChildField child : sf.getChildFields()) {
            if (child.getNodeId() != null) {
              result.add(child.getNodeId());
            }
          }
        }
      }
    }

    // From where_fields
    for (FullParsConditionField cf : node.getWhereFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            result.add(child.getNodeId());
          }
        }
      }
    }

    // From join_conditions
    for (FullParsJoinCondition jc : node.getJoinFields()) {
      for (FullParsConditionField cf : jc.getConditionFields()) {
        if (cf.getChildFields() != null) {
          for (FullParsChildField child : cf.getChildFields()) {
            if (child.getNodeId() != null) {
              result.add(child.getNodeId());
            }
          }
        }
      }
    }

    // From group_by_fields
    for (FullParsConditionField cf : node.getGroupByFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            result.add(child.getNodeId());
          }
        }
      }
    }

    // From having_fields
    for (FullParsConditionField cf : node.getHavingFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            result.add(child.getNodeId());
          }
        }
      }
    }

    // From union_nodes_ids
    if (node.getUnionNodesIds() != null) {
      for (int unionId : node.getUnionNodesIds()) {
        result.add(unionId);
      }
    }

    // From subquery_ids
    if (node.getSubqueryIds() != null) {
      for (int subId : node.getSubqueryIds()) {
        result.add(subId);
      }
    }

    return result;
  }
}
