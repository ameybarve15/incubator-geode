/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.cache.lucene.internal.distributed;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.apache.geode.cache.Cache;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.lucene.internal.LuceneIndexImpl;
import org.apache.geode.cache.lucene.internal.LuceneIndexStats;
import org.apache.geode.cache.lucene.internal.LuceneServiceImpl;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.Query;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.asyncqueue.internal.AsyncEventQueueImpl;
import org.apache.geode.cache.execute.FunctionAdapter;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.cache.execute.RegionFunctionContext;
import org.apache.geode.cache.execute.ResultSender;
import org.apache.geode.cache.lucene.LuceneQueryException;
import org.apache.geode.cache.lucene.LuceneQueryProvider;
import org.apache.geode.cache.lucene.LuceneService;
import org.apache.geode.cache.lucene.LuceneServiceProvider;
import org.apache.geode.cache.lucene.internal.repository.IndexRepository;
import org.apache.geode.cache.lucene.internal.repository.IndexResultCollector;
import org.apache.geode.cache.lucene.internal.repository.RepositoryManager;
import org.apache.geode.internal.InternalEntity;
import org.apache.geode.internal.cache.BucketNotFoundException;
import org.apache.geode.internal.logging.LogService;

/**
 * {@link WaitUntilFlushedFunction} will check all the members with index to wait until the events
 * in current AEQs are flushed into index. This function enables an accessor and client to call to
 * make sure the current events are processed.
 */
public class WaitUntilFlushedFunction implements Function, InternalEntity {
  private static final long serialVersionUID = 1L;
  public static final String ID = WaitUntilFlushedFunction.class.getName();

  private static final Logger logger = LogService.getLogger();

  @Override
  public void execute(FunctionContext context) {
    RegionFunctionContext ctx = (RegionFunctionContext) context;
    ResultSender<Boolean> resultSender = ctx.getResultSender();

    Region region = ctx.getDataSet();
    Cache cache = region.getCache();
    WaitUntilFlushedFunctionContext arg = (WaitUntilFlushedFunctionContext) ctx.getArguments();
    String indexName = arg.getIndexName();
    if (indexName == null) {
      throw new IllegalArgumentException("Missing index name");
    }
    long timeout = arg.getTimeout();
    TimeUnit unit = arg.getTimeunit();

    LuceneService service = LuceneServiceProvider.get(cache);
    LuceneIndexImpl index = (LuceneIndexImpl) service.getIndex(indexName, region.getFullPath());

    boolean result = false;
    String aeqId = LuceneServiceImpl.getUniqueIndexName(indexName, region.getFullPath());
    AsyncEventQueueImpl queue = (AsyncEventQueueImpl) cache.getAsyncEventQueue(aeqId);
    if (queue != null) {
      try {
        result = queue.waitUntilFlushed(timeout, unit);
      } catch (InterruptedException e) {
      }

    } else {
      resultSender.sendException(new IllegalStateException(
          "The AEQ does not exist for the index " + indexName + " region " + region.getFullPath()));
    }
    resultSender.lastResult(result);
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public boolean optimizeForWrite() {
    return true;
  }
}
