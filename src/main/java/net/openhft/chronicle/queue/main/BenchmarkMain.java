/*
 * Copyright 2016-2022 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.queue.main;

import net.openhft.chronicle.queue.internal.main.InternalBenchmarkMain;

/**
 * This class is using the following System Properties:
 *
 * static int throughput = Integer.getInteger("throughput", 250); // MB/s
 * static int runtime = Integer.getInteger("runtime", 300); // seconds
 * static String basePath = System.getProperty("path", OS.TMP);
 */
public final class BenchmarkMain {

    public static void main(String[] args) {
        InternalBenchmarkMain.main(args);
    }
}