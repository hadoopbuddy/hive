/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.optimizer;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.exec.FilterOperator;
import org.apache.hadoop.hive.ql.exec.GroupByOperator;
import org.apache.hadoop.hive.ql.exec.LimitOperator;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.OperatorUtils;
import org.apache.hadoop.hive.ql.exec.ReduceSinkOperator;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.optimizer.ppr.PartitionPruner;
import org.apache.hadoop.hive.ql.parse.GlobalLimitCtx;
import org.apache.hadoop.hive.ql.parse.ParseContext;
import org.apache.hadoop.hive.ql.parse.PrunedPartitionList;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.SplitSample;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.FilterDesc;
import org.apache.hadoop.hive.ql.plan.GroupByDesc;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.ReduceSinkDesc;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;

/**
 * This optimizer is used to reduce the input size for the query for queries which are
 * specifying a limit.
 * <p/>
 * For eg. for a query of type:
 * <p/>
 * select expr from T where <filter> limit 100;
 * <p/>
 * Most probably, the whole table T need not be scanned.
 * Chances are that even if we scan the first file of T, we would get the 100 rows
 * needed by this query.
 * This optimizer step populates the GlobalLimitCtx which is used later on to prune the inputs.
 */
public class GlobalLimitOptimizer implements Transform {

  private final Log LOG = LogFactory.getLog(GlobalLimitOptimizer.class.getName());

  public ParseContext transform(ParseContext pctx) throws SemanticException {
    Context ctx = pctx.getContext();
    Map<String, Operator<? extends OperatorDesc>> topOps = pctx.getTopOps();
    GlobalLimitCtx globalLimitCtx = pctx.getGlobalLimitCtx();
    Map<TableScanOperator, ExprNodeDesc> opToPartPruner = pctx.getOpToPartPruner();
    Map<String, SplitSample> nameToSplitSample = pctx.getNameToSplitSample();

    // determine the query qualifies reduce input size for LIMIT
    // The query only qualifies when there are only one top operator
    // and there is no transformer or UDTF and no block sampling
    // is used.
    if (ctx.getTryCount() == 0 && topOps.size() == 1
        && !globalLimitCtx.ifHasTransformOrUDTF() &&
        nameToSplitSample.isEmpty()) {

      // Here we recursively check:
      // 1. whether there are exact one LIMIT in the query
      // 2. whether there is no aggregation, group-by, distinct, sort by,
      //    distributed by, or table sampling in any of the sub-query.
      // The query only qualifies if both conditions are satisfied.
      //
      // Example qualified queries:
      //    CREATE TABLE ... AS SELECT col1, col2 FROM tbl LIMIT ..
      //    INSERT OVERWRITE TABLE ... SELECT col1, hash(col2), split(col1)
      //                               FROM ... LIMIT...
      //    SELECT * FROM (SELECT col1 as col2 (SELECT * FROM ...) t1 LIMIT ...) t2);
      //
      TableScanOperator ts = (TableScanOperator) topOps.values().toArray()[0];
      Integer tempGlobalLimit = checkQbpForGlobalLimit(ts);

      // query qualify for the optimization
      if (tempGlobalLimit != null && tempGlobalLimit != 0) {
        Table tab = ts.getConf().getTableMetadata();

        if (!tab.isPartitioned()) {
          Set<FilterOperator> filterOps =
                  OperatorUtils.findOperators(ts, FilterOperator.class);
          if (filterOps.size() == 0) {
            globalLimitCtx.enableOpt(tempGlobalLimit);
          }
        } else {
          // check if the pruner only contains partition columns
          if (PartitionPruner.onlyContainsPartnCols(tab,
              opToPartPruner.get(ts))) {

            PrunedPartitionList partsList;
            try {
              String alias = (String) topOps.keySet().toArray()[0];
              partsList = PartitionPruner.prune(ts, pctx, alias);
            } catch (HiveException e) {
              // Has to use full name to make sure it does not conflict with
              // org.apache.commons.lang.StringUtils
              LOG.error(org.apache.hadoop.util.StringUtils.stringifyException(e));
              throw new SemanticException(e.getMessage(), e);
            }

            // If there is any unknown partition, create a map-reduce job for
            // the filter to prune correctly
            if (!partsList.hasUnknownPartitions()) {
              globalLimitCtx.enableOpt(tempGlobalLimit);
            }
          }
        }
        if (globalLimitCtx.isEnable()) {
          LOG.info("Qualify the optimize that reduces input size for 'limit' for limit "
              + globalLimitCtx.getGlobalLimit());
        }
      }
    }
    return pctx;
  }

  /**
   * Check the limit number in all sub queries
   *
   * @return if there is one and only one limit for all subqueries, return the limit
   *         if there is no limit, return 0
   *         otherwise, return null
   */
  private static Integer checkQbpForGlobalLimit(TableScanOperator ts) {
    Set<Class<? extends Operator<?>>> searchedClasses =
          new ImmutableSet.Builder<Class<? extends Operator<?>>>()
            .add(ReduceSinkOperator.class)
            .add(GroupByOperator.class)
            .add(FilterOperator.class)
            .add(LimitOperator.class)
            .build();
    Multimap<Class<? extends Operator<?>>, Operator<?>> ops =
            OperatorUtils.classifyOperators(ts, searchedClasses);
    // To apply this optimization, in the input query:
    // - There cannot exist any order by/sort by clause,
    // thus existsOrdering should be false.
    // - There cannot exist any distribute by clause, thus
    // existsPartitioning should be false.
    // - There cannot exist any cluster by clause, thus
    // existsOrdering AND existsPartitioning should be false.
    for (Operator<?> op : ops.get(ReduceSinkOperator.class)) {
      ReduceSinkDesc reduceSinkConf = ((ReduceSinkOperator) op).getConf();
      if (reduceSinkConf.isOrdering() || reduceSinkConf.isPartitioning()) {
        return null;
      }
    }
    // - There cannot exist any (distinct) aggregate.
    for (Operator<?> op : ops.get(GroupByOperator.class)) {
      GroupByDesc groupByConf = ((GroupByOperator) op).getConf();
      if (groupByConf.isAggregate() || groupByConf.isDistinct()) {
        return null;
      }
    }
    // - There cannot exist any sampling predicate.
    for (Operator<?> op : ops.get(FilterOperator.class)) {
      FilterDesc filterConf = ((FilterOperator) op).getConf();
      if (filterConf.getIsSamplingPred()) {
        return null;
      }
    }
    // If there is one and only one limit starting at op, return the limit
    // If there is no limit, return 0
    // Otherwise, return null
    Collection<Operator<?>> limitOps = ops.get(LimitOperator.class);
    if (limitOps.size() == 1) {
      return ((LimitOperator) limitOps.iterator().next()).getConf().getLimit();
    }
    else if (limitOps.size() == 0) {
      return 0;
    }
    return null;
  }
}
