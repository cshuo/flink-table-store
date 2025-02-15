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

package org.apache.flink.table.store.data.serializer;

import org.apache.flink.table.store.io.DataInputView;
import org.apache.flink.table.store.io.DataOutputView;

import java.io.IOException;

/** Type serializer for {@code Byte} (and {@code byte}, via auto-boxing). */
public final class ByteSerializer extends SerializerSingleton<Byte> {

    private static final long serialVersionUID = 1L;

    /** Sharable instance of the IntSerializer. */
    public static final ByteSerializer INSTANCE = new ByteSerializer();

    @Override
    public Byte copy(Byte from) {
        return from;
    }

    @Override
    public void serialize(Byte record, DataOutputView target) throws IOException {
        target.writeByte(record);
    }

    @Override
    public Byte deserialize(DataInputView source) throws IOException {
        return source.readByte();
    }
}
