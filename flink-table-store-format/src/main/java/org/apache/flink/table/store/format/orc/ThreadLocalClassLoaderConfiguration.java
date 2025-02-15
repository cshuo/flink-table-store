/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.format.orc;

import org.apache.hadoop.conf.Configuration;

import java.net.URL;

/**
 * Workaround for https://issues.apache.org/jira/browse/ORC-653.
 *
 * <p>Since the conf is effectively cached across Flink jobs, at least force the thread local
 * classloader to avoid classloader leaks.
 */
public final class ThreadLocalClassLoaderConfiguration extends Configuration {
    public ThreadLocalClassLoaderConfiguration() {}

    public ThreadLocalClassLoaderConfiguration(Configuration other) {
        super(other);
    }

    @Override
    public ClassLoader getClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Override
    public Class<?> getClassByNameOrNull(String name) {
        try {
            return Class.forName(name, true, getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    public URL getResource(String name) {
        return getClassLoader().getResource(name);
    }
}
