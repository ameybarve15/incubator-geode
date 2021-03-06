/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.admin.internal;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashSet;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.admin.remote.AdminResponse;

/**
 * The response to the {@link PrepareBackupRequest}
 * 
 * @author dsmith
 *
 */
public class PrepareBackupResponse extends AdminResponse {

  private HashSet<PersistentID> persistentIds;
  
  public PrepareBackupResponse() {
    super();
  }

  public PrepareBackupResponse(InternalDistributedMember sender, HashSet<PersistentID> persistentIds) {
    this.setRecipient(sender);
    this.persistentIds = persistentIds;
  }
  
  public HashSet<PersistentID> getPersistentIds() {
    return persistentIds;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    persistentIds = DataSerializer.readHashSet(in);
  }



  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);    
    DataSerializer.writeHashSet(persistentIds, out);
  }



  @Override
  protected Object clone() throws CloneNotSupportedException {
    // TODO Auto-generated method stub
    return super.clone();
  }

  public int getDSFID() {
    return PREPARE_BACKUP_RESPONSE;
  }
  
  @Override
  public String toString() {
    return getClass().getName() + ": " + persistentIds;
  }
}
