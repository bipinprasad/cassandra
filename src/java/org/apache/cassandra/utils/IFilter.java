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
package org.apache.cassandra.utils;

import java.io.IOException;

import org.apache.cassandra.io.util.DataOutputStreamPlus;
import org.apache.cassandra.utils.concurrent.SharedCloseable;

public interface IFilter extends SharedCloseable
{
    interface FilterKey
    {
        /**
         * Places the murmur3 hash of the key in the given long array of size at least two.
         */
        void filterHash(long[] dest);
    }

    void add(FilterKey key);

    boolean isPresent(FilterKey key);

    void clear();

    long serializedSize(boolean oldSerializationFormat);

    void serialize(DataOutputStreamPlus out, boolean oldSerializationFormat) throws IOException;

    void close();

    IFilter sharedCopy();

    /**
     * Returns the amount of memory in bytes used off heap.
     *
     * @return the amount of memory in bytes used off heap
     */
    long offHeapSize();

    boolean isInformative();
}
