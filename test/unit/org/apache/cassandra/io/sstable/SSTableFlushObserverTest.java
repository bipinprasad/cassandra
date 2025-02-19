/*
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
package org.apache.cassandra.io.sstable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.SerializationHeader;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.LongType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.db.rows.AbstractUnfilteredRowIterator;
import org.apache.cassandra.db.rows.BTreeRow;
import org.apache.cassandra.db.rows.BufferCell;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.io.FSWriteError;
import org.apache.cassandra.io.sstable.format.SSTableFormat;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.format.SSTableWriter;
import org.apache.cassandra.io.sstable.metadata.MetadataCollector;
import org.apache.cassandra.io.util.File;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.schema.TableMetadataRef;

public class SSTableFlushObserverTest
{
    @BeforeClass
    public static void initDD()
    {
        DatabaseDescriptor.daemonInitialization();
        CommitLog.instance.start();
    }

    private static final String KS_NAME = "test";
    private static final String CF_NAME = "flush_observer";

    @Test
    public void testFlushObserver() throws Exception
    {
        TableMetadata cfm =
        TableMetadata.builder(KS_NAME, CF_NAME)
                     .addPartitionKeyColumn("id", UTF8Type.instance)
                     .addRegularColumn("first_name", UTF8Type.instance)
                     .addRegularColumn("age", Int32Type.instance)
                     .addRegularColumn("height", LongType.instance)
                     .build();

        LifecycleTransaction transaction = LifecycleTransaction.offline(OperationType.COMPACTION);
        FlushObserver observer = new FlushObserver();

        String sstableDirectory = DatabaseDescriptor.getAllDataFileLocations()[0];
        File directory = new File(sstableDirectory + File.pathSeparator() + KS_NAME + File.pathSeparator() + CF_NAME);
        directory.deleteOnExit();

        if (!directory.exists() && !directory.tryCreateDirectories())
            throw new FSWriteError(new IOException("failed to create tmp directory"), directory.absolutePath());

        SSTableFormat.Type sstableFormat = SSTableFormat.Type.current();
        Descriptor descriptor = new Descriptor(sstableFormat.info.getLatestVersion(),
                                               directory,
                                               cfm.keyspace,
                                               cfm.name,
                                               new SequenceBasedSSTableId(0),
                                               sstableFormat);

        SSTableWriter writer = descriptor.getFormat().getWriterFactory().builder(descriptor)
                                         .setKeyCount(10)
                                         .setTableMetadataRef(TableMetadataRef.forOfflineTools(cfm))
                                         .setMetadataCollector(new MetadataCollector(cfm.comparator).sstableLevel(0))
                                         .setSerializationHeader(new SerializationHeader(true, cfm, cfm.regularAndStaticColumns(), EncodingStats.NO_STATS))
                                         .setFlushObservers(Collections.singletonList(observer))
                                         .addDefaultComponents()
                                         .build(transaction, null);

        SSTableReader reader = null;
        Multimap<ByteBuffer, Cell<?>> expected = ArrayListMultimap.create();

        try
        {
            final long now = System.currentTimeMillis();

            ByteBuffer key = UTF8Type.instance.fromString("key1");
            expected.putAll(key, Arrays.asList(BufferCell.live(getColumn(cfm, "age"), now, Int32Type.instance.decompose(27)),
                                               BufferCell.live(getColumn(cfm, "first_name"), now, UTF8Type.instance.fromString("jack")),
                                               BufferCell.live(getColumn(cfm, "height"), now, LongType.instance.decompose(183L))));

            writer.append(new RowIterator(cfm, key.duplicate(), Collections.singletonList(buildRow(expected.get(key)))));

            key = UTF8Type.instance.fromString("key2");
            expected.putAll(key, Arrays.asList(BufferCell.live(getColumn(cfm, "age"), now, Int32Type.instance.decompose(30)),
                                               BufferCell.live(getColumn(cfm, "first_name"), now, UTF8Type.instance.fromString("jim")),
                                               BufferCell.live(getColumn(cfm, "height"), now, LongType.instance.decompose(180L))));

            writer.append(new RowIterator(cfm, key, Collections.singletonList(buildRow(expected.get(key)))));

            key = UTF8Type.instance.fromString("key3");
            expected.putAll(key, Arrays.asList(BufferCell.live(getColumn(cfm, "age"), now, Int32Type.instance.decompose(30)),
                                               BufferCell.live(getColumn(cfm, "first_name"), now, UTF8Type.instance.fromString("ken")),
                                               BufferCell.live(getColumn(cfm, "height"), now, LongType.instance.decompose(178L))));

            writer.append(new RowIterator(cfm, key, Collections.singletonList(buildRow(expected.get(key)))));

            reader = writer.finish(true);
        }
        finally
        {
            FileUtils.closeQuietly(writer);
        }

        Assert.assertTrue(observer.isComplete);
        Assert.assertEquals(expected.size(), observer.rows.size());

        for (Triple<ByteBuffer, Long, Long> e : observer.rows.keySet())
        {
            ByteBuffer key = e.getLeft();
            long dataPosition = e.getMiddle();
            long indexPosition = e.getRight();

            DecoratedKey indexKey = reader.keyAtPositionFromSecondaryIndex(indexPosition);
            Assert.assertEquals(0, UTF8Type.instance.compare(key, indexKey.getKey()));
            Assert.assertEquals(expected.get(key), observer.rows.get(e));
        }
    }

    private static class RowIterator extends AbstractUnfilteredRowIterator
    {
        private final Iterator<Unfiltered> rows;

        public RowIterator(TableMetadata cfm, ByteBuffer key, Collection<Unfiltered> content)
        {
            super(cfm,
                  DatabaseDescriptor.getPartitioner().decorateKey(key),
                  DeletionTime.LIVE,
                  cfm.regularAndStaticColumns(),
                  BTreeRow.emptyRow(Clustering.STATIC_CLUSTERING),
                  false,
                  EncodingStats.NO_STATS);

            rows = content.iterator();
        }

        @Override
        protected Unfiltered computeNext()
        {
            return rows.hasNext() ? rows.next() : endOfData();
        }
    }

    private static class FlushObserver implements SSTableFlushObserver
    {
        private final Multimap<Triple<ByteBuffer, Long, Long>, Cell<?>> rows = ArrayListMultimap.create();
        private final Multimap<Triple<ByteBuffer, Long, Long>, Cell<?>> staticRows = ArrayListMultimap.create();

        private Triple<ByteBuffer, Long, Long> currentKey;
        private boolean isComplete;

        @Override
        public void begin()
        {
        }

        @Override
        public void startPartition(DecoratedKey key, long dataPosition, long indexPosition)
        {
            currentKey = ImmutableTriple.of(key.getKey(), dataPosition, indexPosition);
        }

        @Override
        public void nextUnfilteredCluster(Unfiltered row)
        {
            if (row.isRow())
                ((Row) row).forEach((c) -> rows.put(currentKey, (Cell<?>) c));
        }

        @Override
        public void complete()
        {
            isComplete = true;
        }

        @Override
        public void staticRow(Row staticRow)
        {
            staticRow.forEach((c) -> staticRows.put(currentKey, (Cell<?>) c));
        }
    }

    private static Row buildRow(Collection<Cell<?>> cells)
    {
        Row.Builder rowBuilder = BTreeRow.sortedBuilder();
        rowBuilder.newRow(Clustering.EMPTY);
        cells.forEach(rowBuilder::addCell);
        return rowBuilder.build();
    }

    private static ColumnMetadata getColumn(TableMetadata cfm, String name)
    {
        return cfm.getColumn(UTF8Type.instance.fromString(name));
    }
}
