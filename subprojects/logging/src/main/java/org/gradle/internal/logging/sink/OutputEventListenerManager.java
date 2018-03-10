/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.sink;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;

public class OutputEventListenerManager {

    private final OutputEventRenderer renderer;
    private OutputEventListener other;

    private final OutputEventListener broadcaster = new OutputEventListener() {
        @Override
        public void onOutput(OutputEvent event) {
            try {
                renderer.onOutput(event);
            } catch (Exception e) {
                // TODO what here?
            }

            OutputEventListener otherRef = other;
            if (otherRef != null) {
                otherRef.onOutput(event);
            }
        }
    };

    public OutputEventListenerManager(OutputEventRenderer renderer) {
        this.renderer = renderer;
    }

    public void setListener(OutputEventListener listener) {
        other = listener;
    }

    public void removeListener(OutputEventListener listener) {
        if (other == listener) {
            other = null;
        }
    }

    public OutputEventListener getBroadcaster() {
        return broadcaster;
    }

}
