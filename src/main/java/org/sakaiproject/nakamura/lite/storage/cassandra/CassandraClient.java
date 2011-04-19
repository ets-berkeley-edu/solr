/*
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.nakamura.lite.storage.cassandra;

import com.google.common.collect.Lists;

import org.apache.cassandra.thrift.Cassandra.Client;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.Deletion;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.Mutation;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.cassandra.thrift.TimedOutException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;
import org.sakaiproject.nakamura.api.lite.RemoveProperty;
import org.sakaiproject.nakamura.api.lite.StorageClientException;
import org.sakaiproject.nakamura.api.lite.StorageClientUtils;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.api.lite.content.Content;
import org.sakaiproject.nakamura.lite.content.BlockContentHelper;
import org.sakaiproject.nakamura.lite.content.BlockSetContentHelper;
import org.sakaiproject.nakamura.lite.storage.DisposableIterator;
import org.sakaiproject.nakamura.lite.storage.StorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class CassandraClient extends Client implements StorageClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraClient.class);
    public static final String CONFIG_BLOCK_SIZE = "block-size";
    public static final String CONFIG_MAX_CHUNKS_PER_BLOCK = "chunks-per-block";

    private static final int DEFAULT_BLOCK_SIZE = 1024 * 1024;
    private static final int DEFAULT_MAX_CHUNKS_PER_BLOCK = 64;

    private TSocket tSocket;
    private BlockContentHelper contentHelper;
    private int blockSize;
    private int maxChunksPerBlockSet;
    private CassandraClientPool pool;

    public CassandraClient(CassandraClientPool pool, TProtocol tProtocol, TSocket tSocket,
            Map<String, Object> properties) {
        super(tProtocol);
        this.tSocket = tSocket;
        this.pool = pool;
        contentHelper = new BlockSetContentHelper(this);
        blockSize = StorageClientUtils.getSetting(properties.get(CONFIG_BLOCK_SIZE),
                DEFAULT_BLOCK_SIZE);
        maxChunksPerBlockSet = StorageClientUtils.getSetting(
                properties.get(CONFIG_MAX_CHUNKS_PER_BLOCK), DEFAULT_MAX_CHUNKS_PER_BLOCK);

    }

    public void close() {
        pool.releaseClient(this);
    }

    public void destroy() {
        try {
            if (tSocket.isOpen()) {
                tSocket.flush();
                tSocket.close();
            }
        } catch (TTransportException e) {
            LOGGER.error("Failed to close the connection to the cassandra store.", e);
        }
    }

    public void passivate() {
    }

    public void activate() {
    }

    public void validate() throws TException {
        describe_version();
    }

    public Map<String, Object> get(String keySpace, String columnFamily, String key)
            throws StorageClientException {
        try {
            Map<String, Object> row = new HashMap<String, Object>();

            SlicePredicate predicate = new SlicePredicate();
            SliceRange sliceRange = new SliceRange();
            sliceRange.setStart(new byte[0]);
            sliceRange.setFinish(new byte[0]);
            predicate.setSlice_range(sliceRange);

            ColumnParent parent = new ColumnParent(columnFamily);
            List<ColumnOrSuperColumn> results = get_slice(keySpace, key, parent, predicate,
                    ConsistencyLevel.ONE);
            for (ColumnOrSuperColumn result : results) {
                if (result.isSetSuper_column()) {
                    Map<String, byte[]> sc = new HashMap<String, byte[]>();
                    for (Column column : result.super_column.columns) {
                        sc.put(StorageClientUtils.toString(column.name), column.value);
                    }
                    row.put(StorageClientUtils.toString(result.super_column.name), sc);
                } else {
                    row.put(StorageClientUtils.toString(result.column.name), result.column.value);
                }
            }
            return row;
        } catch (InvalidRequestException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (UnavailableException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (TimedOutException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (TException e) {
            throw new StorageClientException(e.getMessage(), e);
        }
    }

    public void insert(String keySpace, String columnFamily, String key, Map<String, Object> values, boolean probablyNew)
            throws StorageClientException {
        try {
            Map<String, Map<String, List<Mutation>>> mutation = new HashMap<String, Map<String, List<Mutation>>>();
            Map<String, List<Mutation>> columnMutations = new HashMap<String, List<Mutation>>();
            LOGGER.debug("Saving changes to {}:{}:{} ",
                    new Object[] { keySpace, columnFamily, key });
            List<Mutation> keyMutations = Lists.newArrayList();
            columnMutations.put(columnFamily, keyMutations);
            mutation.put(key, columnMutations);
            for (Entry<String, Object> value : values.entrySet()) {
                String name = value.getKey();
                byte[] bname = StorageClientUtils.toBytes(name);
                Object v = value.getValue();
                if (v instanceof RemoveProperty) {
                    Deletion deletion = new Deletion();
                    SlicePredicate deletionPredicate = new SlicePredicate();
                    deletionPredicate.addToColumn_names(bname);
                    deletion.setPredicate(deletionPredicate);
                    Mutation mu = new Mutation();
                    mu.setDeletion(deletion);
                    keyMutations.add(mu);
                } else if (v instanceof byte[]) {
                    byte[] bv = (byte[]) v;
                    Column column = new Column(bname, bv, System.currentTimeMillis());
                    ColumnOrSuperColumn csc = new ColumnOrSuperColumn();
                    csc.setColumn(column);
                    Mutation mu = new Mutation();
                    mu.setColumn_or_supercolumn(csc);
                    keyMutations.add(mu);
                } else if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, byte[]> sc = (Map<String, byte[]>) v;
                    List<Column> columns = new ArrayList<Column>();
                    for (Entry<String, byte[]> sce : sc.entrySet()) {
                        String cname = sce.getKey();
                        byte[] bcname = StorageClientUtils.toBytes(cname);
                        Column column = new Column(bcname, StorageClientUtils.toBytes(sce
                                .getValue()), System.currentTimeMillis());
                        columns.add(column);
                    }

                    SuperColumn superColumn = new SuperColumn(bname, columns);
                    ColumnOrSuperColumn csc = new ColumnOrSuperColumn();
                    csc.setSuper_column(superColumn);
                    Mutation mu = new Mutation();
                    mu.setColumn_or_supercolumn(csc);
                    keyMutations.add(mu);
                } else {
                    byte[] bv = StorageClientUtils.toBytes(v);
                    Column column = new Column(bname, bv, System.currentTimeMillis());
                    ColumnOrSuperColumn csc = new ColumnOrSuperColumn();
                    csc.setColumn(column);
                    Mutation mu = new Mutation();
                    mu.setColumn_or_supercolumn(csc);
                    keyMutations.add(mu);
                }
            }
            LOGGER.debug("Mutation {} ", mutation);
            batch_mutate(keySpace, mutation, ConsistencyLevel.ONE);
        } catch (InvalidRequestException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (UnavailableException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (TimedOutException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (TException e) {
            throw new StorageClientException(e.getMessage(), e);
        }
    }

    public void remove(String keySpace, String columnFamily, String key)
            throws StorageClientException {
        ColumnPath cp = new ColumnPath(columnFamily);
        try {
            remove(keySpace, key, cp, System.currentTimeMillis(), ConsistencyLevel.ONE);
        } catch (InvalidRequestException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (UnavailableException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (TimedOutException e) {
            throw new StorageClientException(e.getMessage(), e);
        } catch (TException e) {
            throw new StorageClientException(e.getMessage(), e);
        }
    }

    public Map<String, Object> streamBodyIn(String keySpace, String contentColumnFamily,
            String contentId, String contentBlockId, String streamId, Map<String, Object> content, InputStream in)
            throws StorageClientException, AccessDeniedException, IOException {
        return contentHelper.writeBody(keySpace, contentColumnFamily, contentId, contentBlockId, streamId,
                blockSize, maxChunksPerBlockSet, in);
    }

    public InputStream streamBodyOut(String keySpace, String contentColumnFamily, String contentId,
            String contentBlockId, String streamId, Map<String, Object> content) throws StorageClientException,
            AccessDeniedException {

        int nBlocks = StorageClientUtils.toInt(content.get(Content.NBLOCKS_FIELD));
        return contentHelper.readBody(keySpace, contentColumnFamily, contentBlockId, streamId, nBlocks);
    }

    public DisposableIterator<Map<String, Object>> find(String keySpace,
            String authorizableColumnFamily, Map<String, Object> properties) {
        // TODO: Implement
        throw new UnsupportedOperationException();
    }

    public DisposableIterator<Map<String, Object>> listChildren(String keySpace,
            String columnFamily, String key) throws StorageClientException {
        throw new UnsupportedOperationException();
    }

    public boolean hasBody(Map<String, Object> content, String streamId) {
        return contentHelper.hasBody(content, streamId);
    }
}
