/**
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.informantproject.local.trace;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Objects;

/**
 * Structure used in the response to "/trace/summaries".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class TraceSnapshotSummary {

    private final String id;
    private final long capturedAt;
    private final double duration; // nanoseconds
    private final boolean completed;

    public static TraceSnapshotSummary from(String id, long capturedAt, double duration,
            boolean completed) {
        return new TraceSnapshotSummary(id, capturedAt, duration, completed);
    }

    private TraceSnapshotSummary(String id, long capturedAt, double duration, boolean completed) {
        this.id = id;
        this.capturedAt = capturedAt;
        this.duration = duration;
        this.completed = completed;
    }

    public String getId() {
        return id;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public double getDuration() {
        return duration;
    }

    public boolean isCompleted() {
        return completed;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof TraceSnapshotSummary)) {
            return false;
        }
        TraceSnapshotSummary other = (TraceSnapshotSummary) o;
        return Objects.equal(id, other.getId())
                && Objects.equal(capturedAt, other.getCapturedAt())
                && Objects.equal(duration, other.getDuration())
                && Objects.equal(completed, other.isCompleted());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, capturedAt, duration, completed);
    }
}
