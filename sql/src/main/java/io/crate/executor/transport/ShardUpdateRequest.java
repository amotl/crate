/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.transport;

import com.google.common.collect.Iterators;
import io.crate.Constants;
import io.crate.Streamer;
import io.crate.planner.symbol.Reference;
import io.crate.planner.symbol.Symbol;
import org.elasticsearch.action.support.single.instance.InstanceShardOperationRequest;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ShardUpdateRequest extends InstanceShardOperationRequest<ShardUpdateRequest> implements Iterable<ShardUpdateRequest.Item> {

    /**
     * A single update item.
     */
    public static class Item implements Streamable {

        private String id;
        private Symbol[] assignments;
        private long version = Versions.MATCH_ANY;
        @Nullable
        private Object[] missingAssignments;
        @Nullable
        private Streamer[] streamers;


        Item(@Nullable Streamer[] streamers) {
            this.streamers = streamers;
        }

        Item(String id,
             Symbol[] assignments,
             @Nullable Long version,
             @Nullable Object[] missingAssignments,
             @Nullable Streamer[] streamers) {
            this(streamers);
            this.id = id;
            this.assignments = assignments;
            if (version != null) {
                this.version = version;
            }
            this.missingAssignments = missingAssignments;
        }

        public String id() {
            return id;
        }

        public long version() {
            return version;
        }

        public int retryOnConflict() {
            return version == Versions.MATCH_ANY ? Constants.UPDATE_RETRY_ON_CONFLICT : 0;
        }

        public Symbol[] assignments() {
            return assignments;
        }

        @Nullable
        public Object[] missingAssignments() {
            return missingAssignments;
        }

        static Item readItem(StreamInput in, @Nullable Streamer[] streamers) throws IOException {
            Item item = new Item(streamers);
            item.readFrom(in);
            return item;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            id = in.readString();
            int mapSize = in.readVInt();
            assignments = new Symbol[mapSize];
            for (int i = 0; i < mapSize; i++) {
                assignments[i] = Symbol.fromStream(in);
            }
            int missingAssignmentsSize = in.readVInt();
            if (missingAssignmentsSize > 0) {
                this.missingAssignments = new Object[missingAssignmentsSize];
                for (int i = 0; i < missingAssignmentsSize; i++) {
                    missingAssignments[i] = streamers[i].readValueFrom(in);
                }
            }

            version = Versions.readVersion(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(id);
            out.writeVInt(assignments.length);
            for (int i = 0; i < assignments.length; i++) {
                Symbol.toStream(assignments[i], out);
            }
            // Stream References
            if (missingAssignments != null) {
                out.writeVInt(missingAssignments.length);
                for (int i = 0; i < missingAssignments.length; i++) {
                    streamers[i].writeValueTo(out, missingAssignments[i]);
                }
            } else {
                out.writeVInt(0);
            }

            Versions.writeVersion(version, out);
        }
    }

    private List<Item> items;
    private Reference[] assignmentsColumns;
    @Nullable
    private String routing;
    @Nullable
    private Reference[] missingAssignmentsColumns;
    @Nullable
    private Streamer[] streamers;

    public ShardUpdateRequest() {
    }

    public ShardUpdateRequest(String index,
                              Reference[] assignmentsColumns,
                              @Nullable Reference[] missingAssignmentsColumns) {
        super(index);
        this.assignmentsColumns = assignmentsColumns;
        this.missingAssignmentsColumns = missingAssignmentsColumns;
        items = new ArrayList<>();
        if (missingAssignmentsColumns != null) {
            streamers = new Streamer[missingAssignmentsColumns.length];
            for (int i = 0; i < missingAssignmentsColumns.length; i++) {
                streamers[i] = missingAssignmentsColumns[i].valueType().streamer();
            }
        }
    }

    public ShardUpdateRequest(ShardId shardId,
                              Reference[] assignmentsColumns,
                              @Nullable Reference[] missingAssignmentsColumns) {
        this(shardId.getIndex(), assignmentsColumns, missingAssignmentsColumns);
        this.shardId = shardId.id();
    }

    public List<Item> items() {
        return this.items;
    }

    public ShardUpdateRequest add(String id,
                                  Symbol[] assignments,
                                  @Nullable Long version) {
        items.add(new Item(id, assignments, version, null, null));
        return this;
    }

    public ShardUpdateRequest add(String id,
                                  Symbol[] assignments,
                                  @Nullable Long version,
                                  @Nullable Object[] missingAssignments) {
        items.add(new Item(id, assignments, version, missingAssignments, streamers));
        return this;
    }

    public String type() {
        return Constants.DEFAULT_MAPPING_TYPE;
    }

    public ShardUpdateRequest routing(@Nullable String routing) {
        if (routing != null && routing.length() == 0) {
            this.routing = null;
        } else {
            this.routing = routing;
        }
        return this;
    }

    @Nullable
    public String routing() {
        return routing;
    }

    public ShardUpdateRequest shardId(int shardId) {
        this.shardId = shardId;
        return this;
    }

    public int shardId() {
        return shardId;
    }

    public Reference[] assignmentsColumns() {
        return assignmentsColumns;
    }

    @Nullable
    public Reference[] missingAssignmentsColumns() {
        return missingAssignmentsColumns;
    }

    @Override
    public Iterator<Item> iterator() {
        return Iterators.unmodifiableIterator(items.iterator());
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        int assignmentsColumnsSize = in.readVInt();
        if (assignmentsColumnsSize > 0) {
            assignmentsColumns = new Reference[assignmentsColumnsSize];
            for (int i = 0; i < assignmentsColumnsSize; i++) {
                assignmentsColumns[i] = Reference.fromStream(in);
            }
        }
        int missingAssignmentsColumnsSize = in.readVInt();
        if (missingAssignmentsColumnsSize > 0) {
            missingAssignmentsColumns = new Reference[missingAssignmentsColumnsSize];
            streamers = new Streamer[missingAssignmentsColumnsSize];
            for (int i = 0; i < missingAssignmentsColumnsSize; i++) {
                missingAssignmentsColumns[i] = Reference.fromStream(in);
                streamers[i] = missingAssignmentsColumns[i].valueType().streamer();
            }
        }
        int size = in.readVInt();
        items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(Item.readItem(in, streamers));
        }
        routing = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        // Stream References
        if (assignmentsColumns != null) {
            out.writeVInt(assignmentsColumns.length);
            for(Reference reference : assignmentsColumns) {
                Reference.toStream(reference, out);
            }
        } else {
            out.writeVInt(0);
        }
        if (missingAssignmentsColumns != null) {
            out.writeVInt(missingAssignmentsColumns.length);
            for(Reference reference : missingAssignmentsColumns) {
                Reference.toStream(reference, out);
            }
        } else {
            out.writeVInt(0);
        }
        out.writeVInt(items().size());
        for (int i = 0; i < items.size(); i++) {
            items.get(i).writeTo(out);
        }
        out.writeOptionalString(routing);
    }

}
