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

package org.apache.flink.table.store.connector.action;

import org.apache.flink.table.store.catalog.CatalogContext;
import org.apache.flink.table.store.file.catalog.Catalog;
import org.apache.flink.table.store.file.catalog.CatalogFactory;
import org.apache.flink.table.store.file.catalog.Identifier;
import org.apache.flink.table.store.options.CatalogOptions;
import org.apache.flink.table.store.options.Options;
import org.apache.flink.table.store.table.Table;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Abstract base of {@link Action}. */
public abstract class ActionBase implements Action {

    private static final Logger LOG = LoggerFactory.getLogger(ActionBase.class);

    protected Catalog catalog;

    protected Identifier identifier;

    protected Table table;

    ActionBase(String warehouse, String databaseName, String tableName) {
        this(warehouse, databaseName, tableName, new Options());
    }

    ActionBase(String warehouse, String databaseName, String tableName, Options options) {
        identifier = new Identifier(databaseName, tableName);
        catalog =
                CatalogFactory.createCatalog(
                        CatalogContext.create(
                                new Options().set(CatalogOptions.WAREHOUSE, warehouse)));

        try {
            table = catalog.getTable(identifier);
            if (options.toMap().size() > 0) {
                table = table.copy(options.toMap());
            }
        } catch (Catalog.TableNotExistException e) {
            LOG.error("Table doesn't exist in given path.", e);
            System.err.println("Table doesn't exist in given path.");
            throw new RuntimeException(e);
        }
    }
}
