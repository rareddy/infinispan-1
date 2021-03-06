package org.infinispan.interceptors.impl;

import static org.infinispan.commons.util.Util.toStr;
import static org.infinispan.persistence.manager.PersistenceManager.AccessMode.BOTH;
import static org.infinispan.persistence.manager.PersistenceManager.AccessMode.PRIVATE;

import java.util.Map;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.statetransfer.StateTransferManager;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Cache store interceptor specific for the distribution and replication cache modes.
 *
 * <p>If the cache store is shared, only the primary owner of the key writes to the cache store.</p>
 * <p>If the cache store is not shared, every owner of a key writes to the cache store.</p>
 * <p>In non-tx caches, if the originator is an owner, the command is executed there twice. The first time,
 * ({@code isOriginLocal() == true}) we don't write anything to the cache store; the second time,
 * the normal rules apply.</p>
 * <p>For clear operations, either only the originator of the command clears the cache store (if it is
 * shared), or every node clears its cache store (if it is not shared). Note that in non-tx caches, this
 * happens without holding a lock on the primary owner of all the keys.</p>
 *
 * @author Galder Zamarreño
 * @author Dan Berindei
 * @since 9.0
 */
public class DistCacheWriterInterceptor extends CacheWriterInterceptor {
   private DistributionManager dm;
   private Transport transport;
   private Address address;

   private static final Log log = LogFactory.getLog(DistCacheWriterInterceptor.class);
   private boolean isUsingLockDelegation;
   private ClusteringDependentLogic cdl;
   private StateTransferManager stateTransferManager;

   @Override
   protected Log getLog() {
      return log;
   }

   @Inject
   public void inject(DistributionManager dm, Transport transport, ClusteringDependentLogic cdl, StateTransferManager stateTransferManager) {
      this.dm = dm;
      this.transport = transport;
      this.cdl = cdl;
      this.stateTransferManager = stateTransferManager;
   }

   @Start(priority = 25) // after the distribution manager!
   @SuppressWarnings("unused")
   private void setAddress() {
      this.address = transport.getAddress();
      this.isUsingLockDelegation = !cacheConfiguration.transaction().transactionMode().isTransactional();
   }

   // ---- WRITE commands

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      return invokeNextThenApply(ctx, command, (rCtx, rCommand, rv) -> {
         PutKeyValueCommand putKeyValueCommand = (PutKeyValueCommand) rCommand;
         Object key = putKeyValueCommand.getKey();
         if (!isStoreEnabled(putKeyValueCommand) || rCtx.isInTxScope() || !putKeyValueCommand.isSuccessful())
            return rv;
         if (!isProperWriter(rCtx, putKeyValueCommand, putKeyValueCommand.getKey()))
            return rv;

         storeEntry(rCtx, key, putKeyValueCommand);
         if (getStatisticsEnabled())
            cacheStores.incrementAndGet();
         return rv;
      });
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      return invokeNextThenApply(ctx, command, (rCtx, rCommand, rv) -> {
         PutMapCommand putMapCommand = (PutMapCommand) rCommand;
         if (!isStoreEnabled(putMapCommand) || rCtx.isInTxScope())
            return rv;

         Map<Object, Object> map = putMapCommand.getMap();
         int count = 0;
         for (Object key : map.keySet()) {
            // In non-tx mode, a node may receive the same forwarded PutMapCommand many times - but each time
            // it must write only the keys locked on the primary owner that forwarded the command
            if (isUsingLockDelegation && putMapCommand.isForwarded() &&
                  !dm.getPrimaryLocation(key).equals(rCtx.getOrigin()))
               continue;

            if (isProperWriter(rCtx, putMapCommand, key)) {
               storeEntry(rCtx, key, putMapCommand);
               count++;
            }
         }
         if (getStatisticsEnabled())
            cacheStores.getAndAdd(count);

         return rv;
      });
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      return invokeNextThenApply(ctx, command, (rCtx, rCommand, rv) -> {
         RemoveCommand removeCommand = (RemoveCommand) rCommand;
         Object key = removeCommand.getKey();
         if (!isStoreEnabled(removeCommand) || rCtx.isInTxScope() || !removeCommand.isSuccessful())
            return rv;
         if (!isProperWriter(rCtx, removeCommand, key))
            return rv;

         boolean resp = persistenceManager
               .deleteFromAllStores(key, skipSharedStores(rCtx, key, removeCommand) ? PRIVATE : BOTH);
         log.tracef("Removed entry under key %s and got response %s from CacheStore", key, resp);

         return rv;
      });
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command)
         throws Throwable {
      return invokeNextThenApply(ctx, command, (rCtx, rCommand, rv) -> {
         ReplaceCommand replaceCommand = (ReplaceCommand) rCommand;
         Object key = replaceCommand.getKey();
         if (!isStoreEnabled(replaceCommand) || rCtx.isInTxScope() || !replaceCommand.isSuccessful())
            return rv;
         if (!isProperWriter(rCtx, replaceCommand, replaceCommand.getKey()))
            return rv;

         storeEntry(rCtx, key, replaceCommand);
         if (getStatisticsEnabled())
            cacheStores.incrementAndGet();

         return rv;
      });
   }

   @Override
   protected boolean skipSharedStores(InvocationContext ctx, Object key, FlagAffectedCommand command) {
      return !cdl.localNodeIsPrimaryOwner(key) || command.hasAnyFlag(FlagBitSets.SKIP_SHARED_CACHE_STORE);
   }

   @Override
   protected boolean isProperWriter(InvocationContext ctx, FlagAffectedCommand command, Object key) {
      if (command.hasAnyFlag(FlagBitSets.SKIP_OWNERSHIP_CHECK))
         return true;

      if (isUsingLockDelegation && !command.hasAnyFlag(FlagBitSets.CACHE_MODE_LOCAL)) {
         if (ctx.isOriginLocal() && !dm.getPrimaryLocation(key).equals(address)) {
            // The command will be forwarded back to the originator, and the value will be stored then
            // (while holding the lock on the primary owner).
            log.tracef("Skipping cache store on the originator because it is not the primary owner " +
                             "of key %s", toStr(key));
            return false;
         }
      }
      if (stateTransferManager.getCacheTopology() != null && !dm.getWriteConsistentHash().isKeyLocalToNode(address, key)) {
         log.tracef("Skipping cache store since the key is not local: %s", key);
         return false;
      }
      return true;
   }
}
