package org.infinispan.persistence;

import static org.infinispan.test.TestingUtil.extractComponent;
import static org.infinispan.test.TestingUtil.getFirstWriter;
import static org.infinispan.test.TestingUtil.waitForRehashToComplete;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.infinispan.Cache;
import org.infinispan.commons.marshall.StreamingMarshaller;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.context.Flag;
import org.infinispan.distribution.Ownership;
import org.infinispan.interceptors.AsyncInterceptorChain;
import org.infinispan.interceptors.impl.CacheLoaderInterceptor;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.marshall.core.MarshalledEntryImpl;
import org.infinispan.persistence.dummy.DummyInMemoryStore;
import org.infinispan.persistence.dummy.DummyInMemoryStoreConfigurationBuilder;
import org.infinispan.test.MultipleCacheManagersTest;
import org.testng.annotations.Test;

/**
 * Tests if the conditional commands correctly fetch the value from cache loader even with the skip cache load/store
 * flags.
 * <p/>
 * The configuration used is a non-tx distributed cache without passivation.
 *
 * @author Pedro Ruivo
 * @since 7.0
 */
@Test(groups = "functional", testName = "persistence.ClusteredConditionalCommandTest")
public class ClusteredConditionalCommandTest extends MultipleCacheManagersTest {
   private static final String PRIVATE_STORE_CACHE_NAME = "private-store-cache";
   private static final String SHARED_STORE_CACHE_NAME = "shared-store-cache";
   private final String key = getClass().getSimpleName() + "-key";
   private final String value1 = getClass().getSimpleName() + "-value1";
   private final String value2 = getClass().getSimpleName() + "-value2";
   private final boolean passivation;

   public ClusteredConditionalCommandTest() {
      this(false, false);
   }

   protected ClusteredConditionalCommandTest(boolean transactional, boolean passivation) {
      this.transactional = transactional;
      this.passivation = passivation;
   }

   private static ConfigurationBuilder createConfiguration(String storeName, boolean shared, boolean transactional,
                                                           boolean passivation, int storePrefix) {
      ConfigurationBuilder builder = getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC, transactional);
      builder.jmxStatistics().enable();
      builder.clustering()
            .hash().numOwners(2);
      builder.persistence()
            .passivation(passivation)
            .addStore(DummyInMemoryStoreConfigurationBuilder.class)
            .storeName(storeName + (shared ? "-shared" : storePrefix))
            .fetchPersistentState(false)
            .purgeOnStartup(true)
            .shared(shared);
      return builder;
   }

   private static <K, V> void writeToStore(CacheHelper<K, V> cacheHelper, Ownership ownership, K key, V value) {
      cacheHelper.cacheStore(ownership).write(marshalledEntry(key, value, cacheHelper.marshaller(ownership)));
   }

   private static <K, V> MarshalledEntry<K, V> marshalledEntry(K key, V value, StreamingMarshaller marshaller) {
      return new MarshalledEntryImpl<>(key, value, null, marshaller);
   }

   private <K, V> CacheHelper<K, V> create(List<Cache<K, V>> cacheList) {
      CacheHelper<K, V> cacheHelper = new CacheHelper<>();
      for (Cache<K, V> cache : cacheList) {
         ClusteringDependentLogic clusteringDependentLogic = extractComponent(cache, ClusteringDependentLogic.class);
         log.debugf("owners for key %s are %s", key, clusteringDependentLogic.getOwners(key));
         if (clusteringDependentLogic.localNodeIsPrimaryOwner(key)) {
            log.debug("Cache " + address(cache) + " is the primary owner");
            assertTrue(cacheHelper.addCache(Ownership.PRIMARY, cache));
         } else if (clusteringDependentLogic.localNodeIsOwner(key)) {
            log.debug("Cache " + address(cache) + " is the backup owner");
            assertTrue(cacheHelper.addCache(Ownership.BACKUP, cache));
         } else {
            log.debug("Cache " + address(cache) + " is the non owner");
            assertTrue(cacheHelper.addCache(Ownership.NON_OWNER, cache));
         }
      }
      return cacheHelper;
   }

   private void doTest(List<Cache<String, String>> cacheList, ConditionalOperation operation, Ownership ownership,
                       Flag flag, boolean shared) {
      waitForRehashToComplete(cacheList);
      final CacheHelper<String, String> cacheHelper = create(cacheList);
      final boolean skipLoad = flag == Flag.SKIP_CACHE_LOAD || flag == Flag.SKIP_CACHE_STORE;
      assertEmpty(cacheList);
      initStore(cacheHelper, shared);

      try {
         if (flag != null) {
            operation.execute(cacheHelper.cache(ownership).getAdvancedCache().withFlags(flag), key, value1, value2);
         } else {
            operation.execute(cacheHelper.cache(ownership), key, value1, value2);
         }
      } catch (Exception e) {
         //some operation are allowed to fail. e.g. putIfAbsent.
         //we only check the final value
         log.debug(e);
      }

      assertLoadAfterOperation(cacheHelper, operation, ownership, skipLoad);

      assertEquals(operation.finalValue(value1, value2, skipLoad), cacheHelper.cache(Ownership.PRIMARY).get(key));
      assertEquals(operation.finalValue(value1, value2, skipLoad), cacheHelper.cache(Ownership.BACKUP).get(key));
      //don't test in non_owner because it generates remote gets and they can cross tests causing random failures.
   }

   protected <K, V> void assertLoadAfterOperation(CacheHelper<K, V> cacheHelper, ConditionalOperation operation, Ownership ownership, boolean skipLoad) {
      //in non-transactional caches, only the primary owner will load from store
      // backup will load only in case that it is origin (and needs previous value)
      assertLoad(cacheHelper, skipLoad ? 0 : 1, 0, 0);
   }

   protected final <K, V> void assertLoad(CacheHelper<K, V> cacheHelper, int primaryOwner, int backupOwner, int nonOwner) {
      assertEquals("primary owner load", primaryOwner, cacheHelper.loads(Ownership.PRIMARY));
      assertEquals("backup owner load", backupOwner, cacheHelper.loads(Ownership.BACKUP));
      assertEquals("non owner load", nonOwner, cacheHelper.loads(Ownership.NON_OWNER));
   }

   private <K, V> void assertEmpty(List<Cache<K, V>> cacheList) {
      for (Cache<K, V> cache : cacheList) {
         assertTrue(cache + ".isEmpty()", cache.isEmpty());
      }
   }

   private void initStore(CacheHelper<String, String> cacheHelper, boolean shared) {
      if (shared) {
         writeToStore(cacheHelper, Ownership.PRIMARY, key, value1);
         assertTrue(cacheHelper.cacheStore(Ownership.PRIMARY).contains(key));
         assertTrue(cacheHelper.cacheStore(Ownership.BACKUP).contains(key));
         assertTrue(cacheHelper.cacheStore(Ownership.NON_OWNER).contains(key));
      } else {
         writeToStore(cacheHelper, Ownership.PRIMARY, key, value1);
         writeToStore(cacheHelper, Ownership.BACKUP, key, value1);
         assertTrue(cacheHelper.cacheStore(Ownership.PRIMARY).contains(key));
         assertTrue(cacheHelper.cacheStore(Ownership.BACKUP).contains(key));
         assertFalse(cacheHelper.cacheStore(Ownership.NON_OWNER).contains(key));
      }

      cacheHelper.resetStats(Ownership.PRIMARY);
      cacheHelper.resetStats(Ownership.BACKUP);
      cacheHelper.resetStats(Ownership.NON_OWNER);
   }

   @Override
   protected void createCacheManagers() throws Throwable {
      createCluster(getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC), 3);

      int index = 0;
      for (EmbeddedCacheManager cacheManager : cacheManagers) {
         cacheManager.defineConfiguration(SHARED_STORE_CACHE_NAME,
                                          createConfiguration(getClass().getSimpleName(), true, transactional,
                                                              passivation, index).build());
         cacheManager.defineConfiguration(PRIVATE_STORE_CACHE_NAME,
                                          createConfiguration(getClass().getSimpleName(), false, transactional,
                                                              passivation, index).build());
         index++;
      }
   }

   public void testPutIfAbsentOnPrimaryOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.PRIMARY, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testPutIfAbsentOnPrimaryOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.PRIMARY, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testPutIfAbsentOnPrimaryOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.PRIMARY, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testPutIfAbsentOnPrimaryOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.PRIMARY, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testPutIfAbsentOnPrimaryOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.PRIMARY, null, true);
   }

   public void testPutIfAbsentOnPrimaryOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.PRIMARY, null, false);
   }

   public void testPutIfAbsentOnBackupOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.BACKUP, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testPutIfAbsentOnBackupOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.BACKUP, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testPutIfAbsentOnBackupOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.BACKUP, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testPutIfAbsentOnBackupOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.BACKUP, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testPutIfAbsentOnBackupOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.BACKUP, null, true);
   }

   public void testPutIfAbsentOnBackupOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.BACKUP, null, false);
   }

   public void testPutIfAbsentOnNonOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.NON_OWNER, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testPutIfAbsentOnNonOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.NON_OWNER, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testPutIfAbsentOnNonOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.NON_OWNER, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testPutIfAbsentOnNonOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.NON_OWNER, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testPutIfAbsentOnNonOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.NON_OWNER, null, true);
   }

   public void testPutIfAbsentOnNonOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.PUT_IF_ABSENT, Ownership.NON_OWNER, null, false);
   }

   public void testReplaceOnPrimaryOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.PRIMARY, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testReplaceOnPrimaryOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.PRIMARY, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testReplaceOnPrimaryOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.PRIMARY, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testReplaceOnPrimaryOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.PRIMARY, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testReplaceOnPrimaryOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.PRIMARY, null, true);
   }

   public void testReplaceOnPrimaryOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.PRIMARY, null, false);
   }

   public void testReplaceOnBackupOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.BACKUP, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testReplaceOnBackupOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.BACKUP, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testReplaceOnBackupOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.BACKUP, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testReplaceOnBackupOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.BACKUP, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testReplaceOnBackupOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.BACKUP, null, true);
   }

   public void testReplaceOnBackupOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.BACKUP, null, false);
   }

   public void testReplaceOnNonOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.NON_OWNER, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testReplaceOnNonOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.NON_OWNER, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testReplaceOnNonOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.NON_OWNER, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testReplaceOnNonOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.NON_OWNER, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testReplaceOnNonOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.NON_OWNER, null, true);
   }

   public void testReplaceOnNonOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE, Ownership.NON_OWNER, null, false);
   }

   public void testReplaceIfOnPrimaryOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.PRIMARY, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testReplaceIfOnPrimaryOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.PRIMARY, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testReplaceIfOnPrimaryOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.PRIMARY, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testReplaceIfOnPrimaryOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.PRIMARY, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testReplaceIfOnPrimaryOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.PRIMARY, null, true);
   }

   public void testReplaceIfOnPrimaryOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.PRIMARY, null, false);
   }

   public void testReplaceIfOnBackupOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.BACKUP, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testReplaceIfOnBackupOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.BACKUP, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testReplaceIfOnBackupOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.BACKUP, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testReplaceIfOnBackupOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.BACKUP, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testReplaceIfOnBackupOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.BACKUP, null, true);
   }

   public void testReplaceIfOnBackupOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.BACKUP, null, false);
   }

   public void testReplaceIfOnNonOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.NON_OWNER, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testReplaceIfOnNonOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.NON_OWNER, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testReplaceIfOnNonOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.NON_OWNER, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testReplaceIfOnNonOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.NON_OWNER, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testReplaceIfOnNonOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.NON_OWNER, null, true);
   }

   public void testReplaceIfOnNonOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REPLACE_IF, Ownership.NON_OWNER, null, false);
   }

   public void testRemoveIfOnPrimaryOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.PRIMARY, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testRemoveIfOnPrimaryOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.PRIMARY, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testRemoveIfOnPrimaryOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.PRIMARY, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testRemoveIfOnPrimaryOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.PRIMARY, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testRemoveIfOnPrimaryOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.PRIMARY, null, true);
   }

   public void testRemoveIfOnPrimaryOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.PRIMARY, null, false);
   }

   public void testRemoveIfOnBackupOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.BACKUP, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testRemoveIfOnBackupOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.BACKUP, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testRemoveIfOnBackupOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.BACKUP, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testRemoveIfOnBackupOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.BACKUP, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testRemoveIfOnBackupOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.BACKUP, null, true);
   }

   public void testRemoveIfOnBackupOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.BACKUP, null, false);
   }

   public void testRemoveIfOnNonOwnerWithSkipCacheLoaderShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.NON_OWNER, Flag.SKIP_CACHE_LOAD, true);
   }

   public void testRemoveIfOnNonOwnerWithSkipCacheLoader() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.NON_OWNER, Flag.SKIP_CACHE_LOAD, false);
   }

   public void testRemoveIfOnNonOwnerWithIgnoreReturnValuesShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.NON_OWNER, Flag.IGNORE_RETURN_VALUES, true);
   }

   public void testRemoveIfOnNonOwnerWithIgnoreReturnValues() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.NON_OWNER, Flag.IGNORE_RETURN_VALUES, false);
   }

   public void testRemoveIfOnNonOwnerShared() {
      doTest(this.caches(SHARED_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.NON_OWNER, null, true);
   }

   public void testRemoveIfOnNonOwner() {
      doTest(this.caches(PRIVATE_STORE_CACHE_NAME), ConditionalOperation.REMOVE_IF, Ownership.NON_OWNER, null, false);
   }

   protected enum ConditionalOperation {
      PUT_IF_ABSENT {
         @Override
         public <K, V> void execute(Cache<K, V> cache, K key, V value1, V value2) {
            cache.putIfAbsent(key, value2);
         }

         @Override
         public <V> V finalValue(V value1, V value2, boolean skipLoad) {
            return skipLoad ? value2 : value1;
         }
      },
      REPLACE {
         @Override
         public <K, V> void execute(Cache<K, V> cache, K key, V value1, V value2) {
            cache.replace(key, value2);
         }

         @Override
         public <V> V finalValue(V value1, V value2, boolean skipLoad) {
            return skipLoad ? value1 : value2;
         }
      },
      REPLACE_IF {
         @Override
         public <K, V> void execute(Cache<K, V> cache, K key, V value1, V value2) {
            cache.replace(key, value1, value2);
         }

         @Override
         public <V> V finalValue(V value1, V value2, boolean skipLoad) {
            return skipLoad ? value1 : value2;
         }
      },
      REMOVE_IF {
         @Override
         public <K, V> void execute(Cache<K, V> cache, K key, V value1, V value2) {
            cache.remove(key, value1);
         }

         @Override
         public <V> V finalValue(V value1, V value2, boolean skipLoad) {
            return skipLoad ? value1 : null;
         }
      };

      public abstract <K, V> void execute(Cache<K, V> cache, K key, V value1, V value2);

      public abstract <V> V finalValue(V value1, V value2, boolean skipLoad);
   }

   protected static class CacheHelper<K, V> {
      private final Map<Ownership, Cache<K, V>> cacheEnumMap;

      private CacheHelper() {
         cacheEnumMap = new EnumMap<>(Ownership.class);
      }

      public boolean addCache(Ownership ownership, Cache<K, V> cache) {
         boolean contains = cacheEnumMap.containsKey(ownership);
         if (!contains) {
            cacheEnumMap.put(ownership, cache);
         }
         return !contains;
      }

      private Cache<K, V> cache(Ownership ownership) {
         return cacheEnumMap.get(ownership);
      }

      private DummyInMemoryStore cacheStore(Ownership ownership) {
         return getFirstWriter(cache(ownership));
      }

      private StreamingMarshaller marshaller(Ownership ownership) {
         return cacheEnumMap.get(ownership).getAdvancedCache().getComponentRegistry().getCacheMarshaller();
      }

      protected long loads(Ownership ownership) {
         AsyncInterceptorChain chain = extractComponent(cache(ownership), AsyncInterceptorChain.class);
         CacheLoaderInterceptor interceptor = chain.findInterceptorExtending(CacheLoaderInterceptor.class);
         return interceptor.getCacheLoaderLoads();
      }

      private void resetStats(Ownership ownership) {
         AsyncInterceptorChain chain = extractComponent(cache(ownership), AsyncInterceptorChain.class);
         CacheLoaderInterceptor interceptor = chain.findInterceptorExtending(
               CacheLoaderInterceptor.class);
         interceptor.resetStatistics();
      }
   }

}
