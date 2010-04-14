package org.infinispan.client.hotrod;

/**
 * Besides the key and value, also contains an version. To be used in versioned operations, e.g. {@link org.infinispan.client.hotrod.RemoteCache#remove(Object, long)}.
 * @author Mircea.Markus@jboss.com
 */
public interface VersionedValue<V> {
   public long getVersion();
   public V getValue();
}
