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

package org.apache.flink.table.store.file.schema;

import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.annotation.VisibleForTesting;
import org.apache.flink.table.store.file.casting.CastExecutors;
import org.apache.flink.table.store.file.operation.Lock;
import org.apache.flink.table.store.file.schema.SchemaChange.AddColumn;
import org.apache.flink.table.store.file.schema.SchemaChange.DropColumn;
import org.apache.flink.table.store.file.schema.SchemaChange.RemoveOption;
import org.apache.flink.table.store.file.schema.SchemaChange.RenameColumn;
import org.apache.flink.table.store.file.schema.SchemaChange.SetOption;
import org.apache.flink.table.store.file.schema.SchemaChange.UpdateColumnComment;
import org.apache.flink.table.store.file.schema.SchemaChange.UpdateColumnNullability;
import org.apache.flink.table.store.file.schema.SchemaChange.UpdateColumnType;
import org.apache.flink.table.store.file.utils.JsonSerdeUtil;
import org.apache.flink.table.store.fs.FileIO;
import org.apache.flink.table.store.fs.Path;
import org.apache.flink.table.store.types.DataField;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DataTypeCasts;
import org.apache.flink.table.store.types.DataTypeVisitor;
import org.apache.flink.table.store.types.ReassignFieldId;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.table.store.utils.Preconditions;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.flink.table.store.file.utils.FileUtils.listVersionedFiles;
import static org.apache.flink.table.store.utils.Preconditions.checkState;

/** Schema Manager to manage schema versions. */
public class SchemaManager implements Serializable {

    private static final String SCHEMA_PREFIX = "schema-";

    private final FileIO fileIO;
    private final Path tableRoot;

    @Nullable private transient Lock lock;

    public SchemaManager(FileIO fileIO, Path tableRoot) {
        this.fileIO = fileIO;
        this.tableRoot = tableRoot;
    }

    public SchemaManager withLock(@Nullable Lock lock) {
        this.lock = lock;
        return this;
    }

    /** @return latest schema. */
    public Optional<TableSchema> latest() {
        try {
            return listVersionedFiles(fileIO, schemaDirectory(), SCHEMA_PREFIX)
                    .reduce(Math::max)
                    .map(this::schema);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** List all schema. */
    public List<TableSchema> listAll() {
        return listAllIds().stream().map(this::schema).collect(Collectors.toList());
    }

    /** List all schema IDs. */
    public List<Long> listAllIds() {
        try {
            return listVersionedFiles(fileIO, schemaDirectory(), SCHEMA_PREFIX)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Create a new schema from {@link Schema}. */
    public TableSchema createTable(Schema schema) throws Exception {
        while (true) {
            latest().ifPresent(
                            latest -> {
                                throw new IllegalStateException(
                                        "Schema in filesystem exists, please use updating,"
                                                + " latest schema is: "
                                                + latest());
                            });

            List<DataField> fields = schema.fields();
            List<String> partitionKeys = schema.partitionKeys();
            List<String> primaryKeys = schema.primaryKeys();
            Map<String, String> options = schema.options();
            int highestFieldId = RowType.currentHighestFieldId(fields);

            List<String> columnNames =
                    schema.fields().stream().map(DataField::name).collect(Collectors.toList());
            if (options.containsKey(CoreOptions.PRIMARY_KEY.key())) {
                if (!primaryKeys.isEmpty()) {
                    throw new RuntimeException(
                            "Cannot define primary key on DDL and table options at the same time.");
                }
                String pk = options.get(CoreOptions.PRIMARY_KEY.key());
                primaryKeys = Arrays.asList(pk.split(","));
                boolean exists = primaryKeys.stream().allMatch(columnNames::contains);
                if (!exists) {
                    throw new RuntimeException(
                            String.format(
                                    "Primary key column '%s' is not defined in the schema.",
                                    primaryKeys));
                }
                options.remove(CoreOptions.PRIMARY_KEY.key());
            }

            if (options.containsKey(CoreOptions.PARTITION.key())) {
                if (!partitionKeys.isEmpty()) {
                    throw new RuntimeException(
                            "Cannot define partition on DDL and table options at the same time.");
                }
                String partitions = options.get(CoreOptions.PARTITION.key());
                partitionKeys = Arrays.asList(partitions.split(","));
                boolean exists = partitionKeys.stream().allMatch(columnNames::contains);
                if (!exists) {
                    throw new RuntimeException(
                            String.format(
                                    "Partition column '%s' is not defined in the schema.",
                                    partitionKeys));
                }
                options.remove(CoreOptions.PARTITION.key());
            }

            TableSchema newSchema =
                    new TableSchema(
                            0,
                            fields,
                            highestFieldId,
                            partitionKeys,
                            primaryKeys,
                            options,
                            schema.comment());

            boolean success = commit(newSchema);
            if (success) {
                return newSchema;
            }
        }
    }

    /** Update {@link SchemaChange}s. */
    public TableSchema commitChanges(SchemaChange... changes) throws Exception {
        return commitChanges(Arrays.asList(changes));
    }

    /** Update {@link SchemaChange}s. */
    public TableSchema commitChanges(List<SchemaChange> changes) throws Exception {
        while (true) {
            TableSchema schema =
                    latest().orElseThrow(
                                    () -> new RuntimeException("Table not exists: " + tableRoot));
            Map<String, String> newOptions = new HashMap<>(schema.options());
            List<DataField> newFields = new ArrayList<>(schema.fields());
            AtomicInteger highestFieldId = new AtomicInteger(schema.highestFieldId());
            for (SchemaChange change : changes) {
                if (change instanceof SetOption) {
                    SetOption setOption = (SetOption) change;
                    checkAlterTableOption(setOption.key());
                    newOptions.put(setOption.key(), setOption.value());
                } else if (change instanceof RemoveOption) {
                    RemoveOption removeOption = (RemoveOption) change;
                    checkAlterTableOption(removeOption.key());
                    newOptions.remove(removeOption.key());
                } else if (change instanceof AddColumn) {
                    AddColumn addColumn = (AddColumn) change;
                    if (newFields.stream().anyMatch(f -> f.name().equals(addColumn.fieldName()))) {
                        throw new IllegalArgumentException(
                                String.format(
                                        "The column [%s] exists in the table[%s].",
                                        addColumn.fieldName(), tableRoot));
                    }
                    Preconditions.checkArgument(
                            addColumn.dataType().isNullable(),
                            "ADD COLUMN cannot specify NOT NULL.");
                    int id = highestFieldId.incrementAndGet();
                    DataType dataType =
                            ReassignFieldId.reassign(addColumn.dataType(), highestFieldId);
                    newFields.add(
                            new DataField(
                                    id, addColumn.fieldName(), dataType, addColumn.description()));
                } else if (change instanceof RenameColumn) {
                    RenameColumn rename = (RenameColumn) change;
                    validateNotPrimaryAndPartitionKey(schema, rename.fieldName());
                    if (newFields.stream().anyMatch(f -> f.name().equals(rename.newName()))) {
                        throw new IllegalArgumentException(
                                String.format(
                                        "The column [%s] exists in the table[%s].",
                                        rename.newName(), tableRoot));
                    }

                    updateNestedColumn(
                            newFields,
                            new String[] {rename.fieldName()},
                            0,
                            (field) ->
                                    new DataField(
                                            field.id(),
                                            rename.newName(),
                                            field.type(),
                                            field.description()));
                } else if (change instanceof DropColumn) {
                    DropColumn drop = (DropColumn) change;
                    validateNotPrimaryAndPartitionKey(schema, drop.fieldName());
                    if (!newFields.removeIf(
                            f -> f.name().equals(((DropColumn) change).fieldName()))) {
                        throw new IllegalArgumentException(
                                String.format(
                                        "The column [%s] doesn't exist in the table[%s].",
                                        drop.fieldName(), tableRoot));
                    }
                    if (newFields.isEmpty()) {
                        throw new IllegalArgumentException("Cannot drop all fields in table");
                    }
                } else if (change instanceof UpdateColumnType) {
                    UpdateColumnType update = (UpdateColumnType) change;
                    if (schema.partitionKeys().contains(update.fieldName())) {
                        throw new IllegalArgumentException(
                                String.format(
                                        "Cannot update partition column [%s] type in the table[%s].",
                                        update.fieldName(), tableRoot));
                    }
                    updateColumn(
                            newFields,
                            update.fieldName(),
                            (field) -> {
                                checkState(
                                        DataTypeCasts.supportsImplicitCast(
                                                        field.type(), update.newDataType())
                                                && CastExecutors.resolve(
                                                                field.type(), update.newDataType())
                                                        != null,
                                        String.format(
                                                "Column type %s[%s] cannot be converted to %s without loosing information.",
                                                field.name(), field.type(), update.newDataType()));
                                AtomicInteger dummyId = new AtomicInteger(0);
                                if (dummyId.get() != 0) {
                                    throw new RuntimeException(
                                            String.format(
                                                    "Update column to nested row type '%s' is not supported.",
                                                    update.newDataType()));
                                }
                                return new DataField(
                                        field.id(), field.name(), update.newDataType());
                            });
                } else if (change instanceof UpdateColumnNullability) {
                    UpdateColumnNullability update = (UpdateColumnNullability) change;
                    if (update.fieldNames().length == 1
                            && update.newNullability()
                            && schema.primaryKeys().contains(update.fieldNames()[0])) {
                        throw new UnsupportedOperationException(
                                "Cannot change nullability of primary key");
                    }
                    updateNestedColumn(
                            newFields,
                            update.fieldNames(),
                            0,
                            (field) ->
                                    new DataField(
                                            field.id(),
                                            field.name(),
                                            field.type().copy(update.newNullability()),
                                            field.description()));
                } else if (change instanceof UpdateColumnComment) {
                    UpdateColumnComment update = (UpdateColumnComment) change;
                    updateNestedColumn(
                            newFields,
                            update.fieldNames(),
                            0,
                            (field) ->
                                    new DataField(
                                            field.id(),
                                            field.name(),
                                            field.type(),
                                            update.newDescription()));
                } else {
                    throw new UnsupportedOperationException(
                            "Unsupported change: " + change.getClass());
                }
            }

            TableSchema newSchema =
                    new TableSchema(
                            schema.id() + 1,
                            newFields,
                            highestFieldId.get(),
                            schema.partitionKeys(),
                            schema.primaryKeys(),
                            newOptions,
                            schema.comment());

            boolean success = commit(newSchema);
            if (success) {
                return newSchema;
            }
        }
    }

    private void validateNotPrimaryAndPartitionKey(TableSchema schema, String fieldName) {
        /// TODO support partition and primary keys schema evolution
        if (schema.partitionKeys().contains(fieldName)) {
            throw new UnsupportedOperationException(
                    String.format("Cannot drop/rename partition key[%s]", fieldName));
        }
        if (schema.primaryKeys().contains(fieldName)) {
            throw new UnsupportedOperationException(
                    String.format("Cannot drop/rename primary key[%s]", fieldName));
        }
    }

    /** This method is hacky, newFields may be immutable. We should use {@link DataTypeVisitor}. */
    private void updateNestedColumn(
            List<DataField> newFields,
            String[] updateFieldNames,
            int index,
            Function<DataField, DataField> updateFunc) {
        boolean found = false;
        for (int i = 0; i < newFields.size(); i++) {
            DataField field = newFields.get(i);
            if (field.name().equals(updateFieldNames[index])) {
                found = true;
                if (index == updateFieldNames.length - 1) {
                    newFields.set(i, updateFunc.apply(field));
                    break;
                } else {
                    List<DataField> nestedFields =
                            new ArrayList<>(
                                    ((org.apache.flink.table.store.types.RowType) field.type())
                                            .getFields());
                    updateNestedColumn(nestedFields, updateFieldNames, index + 1, updateFunc);
                    newFields.set(
                            i,
                            new DataField(
                                    field.id(),
                                    field.name(),
                                    new org.apache.flink.table.store.types.RowType(
                                            field.type().isNullable(), nestedFields),
                                    field.description()));
                }
            }
        }
        if (!found) {
            throw new RuntimeException("Can not find column: " + Arrays.asList(updateFieldNames));
        }
    }

    private void updateColumn(
            List<DataField> newFields,
            String updateFieldName,
            Function<DataField, DataField> updateFunc) {
        updateNestedColumn(newFields, new String[] {updateFieldName}, 0, updateFunc);
    }

    @VisibleForTesting
    boolean commit(TableSchema newSchema) throws Exception {
        SchemaValidation.validateTableSchema(newSchema);

        Path schemaPath = toSchemaPath(newSchema.id());
        Callable<Boolean> callable = () -> fileIO.writeFileUtf8(schemaPath, newSchema.toString());
        if (lock == null) {
            return callable.call();
        }
        return lock.runWithLock(callable);
    }

    /** Read schema for schema id. */
    public TableSchema schema(long id) {
        try {
            return JsonSerdeUtil.fromJson(fileIO.readFileUtf8(toSchemaPath(id)), TableSchema.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path schemaDirectory() {
        return new Path(tableRoot + "/schema");
    }

    @VisibleForTesting
    public Path toSchemaPath(long id) {
        return new Path(tableRoot + "/schema/" + SCHEMA_PREFIX + id);
    }

    public static void checkAlterTableOption(String key) {
        if (CoreOptions.getImmutableOptionKeys().contains(key)) {
            throw new UnsupportedOperationException(
                    String.format("Change '%s' is not supported yet.", key));
        }
    }
}
