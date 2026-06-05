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
   * sub_query nodes are excluded — they are inlined into parent queries.
   * Physical-only leaf nodes are also excluded.
   */
  public List<Integer> sort(List<FullParsNode> fflNodes) {
    Map<Integer, FullParsNode> nodeById = fflNodes.stream()
      .collect(Collectors.toMap(FullParsNode::getId, n -> n));

    // Identify sub_query nodes
    Set<Integer> subQueryIds = fflNodes.stream()
      .filter(n -> "sub_query".equals(n.getType()))
      .map(FullParsNode::getId)
      .collect(Collectors.toSet());

    // Build graph: edge from dependent -> dependency
    // sub_query nodes are NOT in the graph as vertices, but their dependencies
    // are propagated to their parents via subquery_ids
    Map<Integer, Set<Integer>> dependencies = new HashMap<>();
    Map<Integer, Set<Integer>> dependents = new HashMap<>();

    for (FullParsNode node : fflNodes) {
      int nodeId = node.getId();
      if (subQueryIds.contains(nodeId)) {
        continue; // sub_query nodes are not in the topological order
      }
      dependencies.putIfAbsent(nodeId, new HashSet<>());
      dependents.putIfAbsent(nodeId, new HashSet<>());

      // Collect child node ids from this node's fields
      Set<Integer> childNodeIds = collectChildNodeIds(node, nodeById);
      for (int childId : childNodeIds) {
        if (!nodeById.containsKey(childId)) continue;
        if (subQueryIds.contains(childId)) {
          // If child is a sub_query, add its dependencies instead
          Set<Integer> subDeps = collectChildNodeIds(nodeById.get(childId), nodeById);
          for (int subDepId : subDeps) {
            if (!nodeById.containsKey(subDepId)) continue;
            if (subQueryIds.contains(subDepId)) continue; // nested sub_query — skip
            dependencies.get(nodeId).add(subDepId);
            dependents.putIfAbsent(subDepId, new HashSet<>());
            dependents.get(subDepId).add(nodeId);
          }
        } else {
          dependencies.get(nodeId).add(childId);
          dependents.putIfAbsent(childId, new HashSet<>());
          dependents.get(childId).add(nodeId);
        }
      }
    }

    // Kahn's algorithm with PriorityQueue sorted by sdbl_id
    // This ensures temp tables are defined before they are used
    // when multiple nodes have no unresolved dependencies
    PriorityQueue<Integer> queue = new PriorityQueue<>(
      Comparator.comparingInt((Integer id) -> nodeById.get(id).getSdblId()));
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

    return result; // leaves first, root last
  }

  private Set<Integer> collectChildNodeIds(FullParsNode node, Map<Integer, FullParsNode> nodeById) {
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
