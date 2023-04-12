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

package net.openhft.chronicle.queue.util;

import net.openhft.chronicle.core.Jvm;

public final class ToolsUtil {

    private ToolsUtil() {
    }

    /**
     * When running tools e.g. ChronicleReader, from the CQ source dir, resource tracing may be turned on
     */
    public static void warnIfResourceTracing() {
        // System.err (*not* logger as slf4j may not be set up e.g. when running queue_reader.sh)
        if (Jvm.isResourceTracing())
            System.err.println("Resource tracing is turned on - this will eventually die with OOME");
    }
}
