/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.internal.cache.tx;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Region.Entry;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionDataNotColocatedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.BucketNotFoundException;
import com.gemstone.gemfire.internal.cache.DataLocationException;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation;
import com.gemstone.gemfire.internal.cache.DistributedRemoveAllOperation;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.KeyInfo;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.RetryTimeKeeper;
import com.gemstone.gemfire.internal.cache.PartitionedRegionStats;
import com.gemstone.gemfire.internal.cache.PrimaryBucketException;
import com.gemstone.gemfire.internal.cache.PutAllPartialResultException;
import com.gemstone.gemfire.internal.cache.PutAllPartialResultException.PutAllPartialResult;
import com.gemstone.gemfire.internal.cache.TXStateStub;
import com.gemstone.gemfire.internal.cache.partitioned.PutAllPRMessage;
import com.gemstone.gemfire.internal.cache.partitioned.RemoteSizeMessage;
import com.gemstone.gemfire.internal.cache.partitioned.RemoveAllPRMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class PartitionedTXRegionStub extends AbstractPeerTXRegionStub {

  /**
   * tracks bucketIds of transactional operations so as to distinguish between
   * TransactionDataNotColocated and TransactionDataRebalanced exceptions.
   * Map rather than set, as a HashSet is backed by a HashMap. (avoids one "new" call).
   */
  private Map<Integer, Boolean> buckets = new HashMap<Integer, Boolean>();

  public PartitionedTXRegionStub(TXStateStub txstate,LocalRegion r) {
    super(txstate,r);
  }

  
  public void destroyExistingEntry(EntryEventImpl event, boolean cacheWrite,
      Object expectedOldValue) {
      PartitionedRegion pr = (PartitionedRegion)event.getLocalRegion();
      try {
        pr.destroyRemotely(state.getTarget(), event.getKeyInfo().getBucketId(), event, expectedOldValue);
      } catch (TransactionException e) {
        RuntimeException re = getTransactionException(event.getKeyInfo(), e);
        re.initCause(e.getCause());
        throw re;
      } catch (PrimaryBucketException e) {
        RuntimeException re = getTransactionException(event.getKeyInfo(), e);
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re;
        if (isBucketNotFoundException(e)) {
          re = new TransactionDataRebalancedException(LocalizedStrings.PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING.toLocalizedString());
        } else {
          re = new TransactionDataNodeHasDepartedException(LocalizedStrings.PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE.toLocalizedString(state.getTarget()));
        }
        re.initCause(e);
        waitToRetry();
        throw re;
      }
      trackBucketForTx(event.getKeyInfo());
  }
  
  
  private RuntimeException getTransactionException(KeyInfo keyInfo,
      Throwable cause) {
    region.getCancelCriterion().checkCancelInProgress(cause); // fixes bug 44567
    Throwable ex = cause;
    while (ex != null) {
      if (ex instanceof CacheClosedException) {
        return new TransactionDataNodeHasDepartedException(ex.getMessage());
      }
      ex = ex.getCause();
    }
    if (keyInfo != null && !buckets.isEmpty() && !buckets.containsKey(keyInfo.getBucketId())) {
      // for parent region if previous ops were successful and for child colocated regions
      // where the bucketId was not previously encountered
      return new TransactionDataNotColocatedException(LocalizedStrings.PartitionedRegion_KEY_0_NOT_COLOCATED_WITH_TRANSACTION
              .toLocalizedString(keyInfo.getKey()));
    }
    ex = cause;
    while (ex != null) {
      if (ex instanceof PrimaryBucketException) {
        return new TransactionDataRebalancedException(LocalizedStrings.PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
            .toLocalizedString());
      }
      ex = ex.getCause();
    }
    return new TransactionDataNodeHasDepartedException(cause.getLocalizedMessage());
  }


  /**
   * wait to retry after getting a ForceReattemptException
   */
  private void waitToRetry() {
    // this is what PR operations do.  The 2000ms is not used
    (new RetryTimeKeeper(2000)).waitForBucketsRecovery();
  }
  

  
  public Entry getEntry(KeyInfo keyInfo, boolean allowTombstones) {
      PartitionedRegion pr = (PartitionedRegion)region;
      try {
        Entry e = pr.getEntryRemotely((InternalDistributedMember)state.getTarget(),
                                keyInfo.getBucketId(), keyInfo.getKey(), false,
                                allowTombstones);
        trackBucketForTx(keyInfo);
        return e;
      } catch (EntryNotFoundException enfe) {
        return null;
      } catch (TransactionException e) {
        RuntimeException re = getTransactionException(keyInfo, e);
        re.initCause(e.getCause());
        throw re;
      } catch (PrimaryBucketException e) {
        RuntimeException re = getTransactionException(keyInfo, e);
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re;
        if (isBucketNotFoundException(e)) {
          re = new TransactionDataRebalancedException(LocalizedStrings.PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING.toLocalizedString());
        } else {
          re = new TransactionDataNodeHasDepartedException(LocalizedStrings.PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE.toLocalizedString(state.getTarget()));
        }
        re.initCause(e);
        waitToRetry();
        throw re;
      }
  }


  private void trackBucketForTx(KeyInfo keyInfo) {
    if (region.getCache().getLoggerI18n().fineEnabled()) {
      region.getCache().getLoggerI18n().fine("adding bucket:"+keyInfo.getBucketId()+" for tx:"+state.getTransactionId());
    }
    if (keyInfo.getBucketId() >= 0) {
      buckets.put(keyInfo.getBucketId(), Boolean.TRUE);
    }
  }

  
  public void invalidateExistingEntry(EntryEventImpl event,
      boolean invokeCallbacks, boolean forceNewEntry) {
      PartitionedRegion pr = (PartitionedRegion)event.getLocalRegion();
      try {
        pr.invalidateRemotely(state.getTarget(), event.getKeyInfo().getBucketId(), event);
      } catch (TransactionException e) {
        RuntimeException re = getTransactionException(event.getKeyInfo(), e);
        re.initCause(e.getCause());
        throw re;
      } catch (PrimaryBucketException e) {
        RuntimeException re = getTransactionException(event.getKeyInfo(), e);
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re;
        if (isBucketNotFoundException(e)) {
          re = new TransactionDataRebalancedException(LocalizedStrings.PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING.toLocalizedString());
        } else {
          re = new TransactionDataNodeHasDepartedException(LocalizedStrings.PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE.toLocalizedString(state.getTarget()));
        }
        re.initCause(e);
        waitToRetry();
        throw re;
      }
      trackBucketForTx(event.getKeyInfo());
  }

  
  public boolean containsKey(KeyInfo keyInfo) {
      PartitionedRegion pr = (PartitionedRegion)region;
      try {
        boolean retVal = pr.containsKeyRemotely((InternalDistributedMember)state.getTarget(), keyInfo.getBucketId(), keyInfo.getKey());
        trackBucketForTx(keyInfo);
        return retVal;
      }
      catch (TransactionException e) {
        RuntimeException re = getTransactionException(keyInfo, e);
        re.initCause(e.getCause());
        throw re;
      }
      catch (PrimaryBucketException e) {
        RuntimeException re = getTransactionException(keyInfo, e);
        re.initCause(e);
        throw re;
      }
      catch (ForceReattemptException e) {
        if (isBucketNotFoundException(e)) {
          return false;
        }
        waitToRetry();
        RuntimeException re = new TransactionDataNodeHasDepartedException(LocalizedStrings.PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE.toLocalizedString(state.getTarget()));
        re.initCause(e);
        throw re;
      }
  }

  
  /**
   * @param e
   * @return true if the cause of the FRE is a BucketNotFoundException
   */
  private boolean isBucketNotFoundException(ForceReattemptException e) {
    ForceReattemptException fre = e;
    while (fre.getCause() != null && fre.getCause() instanceof ForceReattemptException) {
      fre = (ForceReattemptException)fre.getCause();
    }
    return fre instanceof BucketNotFoundException;
  }

  
  public boolean containsValueForKey(KeyInfo keyInfo) {
      PartitionedRegion pr = (PartitionedRegion)region;
      try {
        boolean retVal = pr.containsValueForKeyRemotely((InternalDistributedMember)state.getTarget(), keyInfo.getBucketId(), keyInfo.getKey());
        trackBucketForTx(keyInfo);
        return retVal;
      }
      catch (TransactionException e) {
        RuntimeException re = getTransactionException(keyInfo, e);
        re.initCause(e.getCause());
        throw re;
      }
      catch (PrimaryBucketException e) {
        RuntimeException re = getTransactionException(keyInfo, e);
        re.initCause(e);
        throw re;
      }
      catch (ForceReattemptException e) {
        if (isBucketNotFoundException(e)) {
          return false;
        }
        waitToRetry();
        RuntimeException re = new TransactionDataNodeHasDepartedException(LocalizedStrings.PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE.toLocalizedString(state.getTarget()));
        re.initCause(e);
        throw re;
      }
  }

  
  public Object findObject(KeyInfo keyInfo, boolean isCreate,
      boolean generateCallbacks, Object value, boolean peferCD,
      ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent) {
    Object retVal = null;
    final Object key = keyInfo.getKey();
    final Object callbackArgument = keyInfo.getCallbackArg();
    PartitionedRegion pr = (PartitionedRegion)region;
    try {
      retVal = pr.getRemotely((InternalDistributedMember)state.getTarget(), keyInfo.getBucketId(), key, callbackArgument, peferCD, requestingClient, clientEvent, false);
    } catch (TransactionException e) {
      RuntimeException re = getTransactionException(keyInfo, e);
      re.initCause(e.getCause());
      throw re;
    } catch (PrimaryBucketException e) {
      RuntimeException re = getTransactionException(keyInfo, e);
      re.initCause(e);
      throw re;
    } catch (ForceReattemptException e) {
      if (isBucketNotFoundException(e)) {
        return null;
      }
      waitToRetry();
      RuntimeException re = getTransactionException(keyInfo, e);
      re.initCause(e);
      throw re;
    }
    trackBucketForTx(keyInfo);
    return retVal;
  }

  
  public Object getEntryForIterator(KeyInfo keyInfo, boolean allowTombstones) {
      PartitionedRegion pr = (PartitionedRegion)region;
      InternalDistributedMember primary = pr.getBucketPrimary(keyInfo.getBucketId());
      if (primary.equals(state.getTarget())) {
        return getEntry(keyInfo, allowTombstones);
      } else {
        return pr.getSharedDataView().getEntry(keyInfo, pr, allowTombstones);
      }
  }

  
  public boolean putEntry(EntryEventImpl event, boolean ifNew, boolean ifOld,
      Object expectedOldValue, boolean requireOldValue, long lastModified,
      boolean overwriteDestroyed) {
    boolean retVal = false;
    final LocalRegion r = event.getLocalRegion();
      PartitionedRegion pr = (PartitionedRegion)r;
      try {
        retVal = pr.putRemotely(state.getTarget(), event, ifNew, ifOld, expectedOldValue, requireOldValue);
      } catch (TransactionException e) {
        RuntimeException re = getTransactionException(event.getKeyInfo(), e);
        re.initCause(e.getCause());
        throw re;
      } catch (PrimaryBucketException e) {
        RuntimeException re = getTransactionException(event.getKeyInfo(), e);
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        waitToRetry();
        RuntimeException re = getTransactionException(event.getKeyInfo(), e);
        re.initCause(e);
        throw re;
      }
    trackBucketForTx(event.getKeyInfo());
    return retVal;
  }

  
  public int entryCount() {
    try {
      RemoteSizeMessage.SizeResponse response = RemoteSizeMessage.send(Collections.singleton(state.getTarget()), region);
      return response.waitForSize();
    } catch (Exception e) {
      throw getTransactionException(null, e);
    }
  }
  
  /**
   * Create PutAllPRMsgs for each bucket, and send them. 
   * 
   * @param putallO
   *                DistributedPutAllOperation object.  
   */
  public void postPutAll(DistributedPutAllOperation putallO, VersionedObjectList successfulPuts, LocalRegion r) throws TransactionException {
    if (r.getCache().isCacheAtShutdownAll()) {
      throw new CacheClosedException("Cache is shutting down");
    }

    PartitionedRegion pr = (PartitionedRegion)r;
    final long startTime = PartitionedRegionStats.startTime();
    // build all the msgs by bucketid
    HashMap prMsgMap = putallO.createPRMessages();
    PutAllPartialResult partialKeys = new PutAllPartialResult(putallO.putAllDataSize);
    
    successfulPuts.clear();  // this is rebuilt by this method
    Iterator itor = prMsgMap.entrySet().iterator();
    while (itor.hasNext()) {
      Map.Entry mapEntry = (Map.Entry)itor.next();
      Integer bucketId = (Integer)mapEntry.getKey();
      PutAllPRMessage prMsg =(PutAllPRMessage)mapEntry.getValue();
      pr.checkReadiness();
      try {
        VersionedObjectList versions = sendMsgByBucket(bucketId, prMsg,pr);
        //prMsg.saveKeySet(partialKeys);
        partialKeys.addKeysAndVersions(versions);
        successfulPuts.addAll(versions);
      } catch (PutAllPartialResultException pre) {
        // sendMsgByBucket applied partial keys 
        partialKeys.consolidate(pre.getResult());
      } catch (Exception ex) {
        // If failed at other exception
        EntryEventImpl firstEvent = prMsg.getFirstEvent(pr);
          partialKeys.saveFailedKey(firstEvent.getKey(), ex);
      }
    }
    pr.prStats.endPutAll(startTime);

    if (partialKeys.hasFailure()) {
      pr.getCache().getLoggerI18n().info(LocalizedStrings.Region_PutAll_Applied_PartialKeys_0_1,
          new Object[] {pr.getFullPath(), partialKeys});
      if (putallO.isBridgeOperation()) {
        if (partialKeys.getFailure() instanceof CancelException) {
          throw (CancelException)partialKeys.getFailure(); 
        } else {
          throw new PutAllPartialResultException(partialKeys);
        }
      } else {
        if (partialKeys.getFailure() instanceof RuntimeException) {
          throw (RuntimeException)partialKeys.getFailure();
        } else {
          throw new RuntimeException(partialKeys.getFailure());
        }
      }
    } 
  } 
  
  @Override
  public void postRemoveAll(DistributedRemoveAllOperation op, VersionedObjectList successfulOps, LocalRegion r) {
    if (r.getCache().isCacheAtShutdownAll()) {
      throw new CacheClosedException("Cache is shutting down");
    }

    PartitionedRegion pr = (PartitionedRegion)r;
    final long startTime = PartitionedRegionStats.startTime();
    // build all the msgs by bucketid
    HashMap<Integer, RemoveAllPRMessage> prMsgMap = op.createPRMessages();
    PutAllPartialResult partialKeys = new PutAllPartialResult(op.removeAllDataSize);
    
    successfulOps.clear();  // this is rebuilt by this method
    Iterator<Map.Entry<Integer, RemoveAllPRMessage>> itor = prMsgMap.entrySet().iterator();
    while (itor.hasNext()) {
      Map.Entry<Integer, RemoveAllPRMessage> mapEntry = itor.next();
      Integer bucketId = mapEntry.getKey();
      RemoveAllPRMessage prMsg = mapEntry.getValue();
      pr.checkReadiness();
      try {
        VersionedObjectList versions = sendMsgByBucket(bucketId, prMsg,pr);
        //prMsg.saveKeySet(partialKeys);
        partialKeys.addKeysAndVersions(versions);
        successfulOps.addAll(versions);
      } catch (PutAllPartialResultException pre) {
        // sendMsgByBucket applied partial keys 
        partialKeys.consolidate(pre.getResult());
      } catch (Exception ex) {
        // If failed at other exception
        EntryEventImpl firstEvent = prMsg.getFirstEvent(pr);
          partialKeys.saveFailedKey(firstEvent.getKey(), ex);
      }
    }
    pr.prStats.endRemoveAll(startTime);

    if (partialKeys.hasFailure()) {
      pr.getCache().getLoggerI18n().info(LocalizedStrings.Region_RemoveAll_Applied_PartialKeys_0_1,
          new Object[] {pr.getFullPath(), partialKeys});
      if (op.isBridgeOperation()) {
        if (partialKeys.getFailure() instanceof CancelException) {
          throw (CancelException)partialKeys.getFailure(); 
        } else {
          throw new PutAllPartialResultException(partialKeys);
        }
      } else {
        if (partialKeys.getFailure() instanceof RuntimeException) {
          throw (RuntimeException)partialKeys.getFailure();
        } else {
          throw new RuntimeException(partialKeys.getFailure());
        }
      }
    } 
    
  }
  
  
  /* If failed after retries, it will throw PartitionedRegionStorageException, no need for return value */
  private VersionedObjectList sendMsgByBucket(final Integer bucketId, PutAllPRMessage prMsg,PartitionedRegion pr)
  {
    // retry the put remotely until it finds the right node managing the bucket
    InternalDistributedMember currentTarget = pr.getOrCreateNodeForBucketWrite(bucketId.intValue(), null);
    if(!currentTarget.equals(this.state.getTarget())) {
      EntryEventImpl firstEvent = prMsg.getFirstEvent(pr);
        throw new TransactionDataNotColocatedException(LocalizedStrings.PartitionedRegion_KEY_0_NOT_COLOCATED_WITH_TRANSACTION.toLocalizedString(firstEvent.getKey()));
    }
    try {
      return pr.tryToSendOnePutAllMessage(prMsg,currentTarget);
    }
    catch (ForceReattemptException prce) {
      pr.checkReadiness();
      throw new TransactionDataNotColocatedException(prce.getMessage());
    }
    catch (PrimaryBucketException notPrimary) {
      RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings.PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING.toLocalizedString());
      re.initCause(notPrimary);
      throw re;
    }
    catch (DataLocationException dle) {
      throw new TransactionException(dle);
    }
  }

  /* If failed after retries, it will throw PartitionedRegionStorageException, no need for return value */
  private VersionedObjectList sendMsgByBucket(final Integer bucketId, RemoveAllPRMessage prMsg,PartitionedRegion pr)
  {
    // retry the put remotely until it finds the right node managing the bucket
    InternalDistributedMember currentTarget = pr.getOrCreateNodeForBucketWrite(bucketId.intValue(), null);
    if(!currentTarget.equals(this.state.getTarget())) {
      EntryEventImpl firstEvent = prMsg.getFirstEvent(pr);
        throw new TransactionDataNotColocatedException(LocalizedStrings.PartitionedRegion_KEY_0_NOT_COLOCATED_WITH_TRANSACTION.toLocalizedString(firstEvent.getKey()));
    }
    try {
      return pr.tryToSendOneRemoveAllMessage(prMsg,currentTarget);
    }
    catch (ForceReattemptException prce) {
      pr.checkReadiness();
      throw new TransactionDataNotColocatedException(prce.getMessage());
    }
    catch (PrimaryBucketException notPrimary) {
      RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings.PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING.toLocalizedString());
      re.initCause(notPrimary);
      throw re;
    }
    catch (DataLocationException dle) {
      throw new TransactionException(dle);
    }
  }


  @Override
  public void cleanup() {
  }

}
