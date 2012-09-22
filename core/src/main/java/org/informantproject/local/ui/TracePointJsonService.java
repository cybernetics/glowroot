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
package org.informantproject.local.ui;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.util.Clock;
import org.informantproject.local.trace.TraceSinkLocal;
import org.informantproject.local.trace.TraceSnapshotDao;
import org.informantproject.local.trace.TraceSnapshotDao.StringComparator;
import org.informantproject.local.trace.TraceSnapshotService;
import org.informantproject.local.trace.TraceSnapshotSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TracePointJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TracePointJsonService.class);

    private static final int NANOSECONDS_PER_MILLISECOND = 1000000;

    private final TraceSnapshotDao traceSnapshotDao;
    private final TraceRegistry traceRegistry;
    private final TraceSinkLocal traceSinkLocal;
    private final TraceSnapshotService traceSnapshotService;
    private final Ticker ticker;
    private final Clock clock;
    private final Gson gson = new Gson();

    @Inject
    TracePointJsonService(TraceSnapshotDao traceSnapshotDao, TraceRegistry traceRegistry,
            TraceSinkLocal traceSinkLocal, TraceSnapshotService traceSnapshotService,
            Ticker ticker, Clock clock) {

        this.traceSnapshotDao = traceSnapshotDao;
        this.traceRegistry = traceRegistry;
        this.traceSinkLocal = traceSinkLocal;
        this.traceSnapshotService = traceSnapshotService;
        this.ticker = ticker;
        this.clock = clock;
    }

    @JsonServiceMethod
    String getPoints(String message) throws IOException {
        return new Handler().handle(message);
    }

    private class Handler {

        private TraceRequest request;
        private long requestAt;
        private long low;
        private long high;
        @Nullable
        private StringComparator userIdComparator;
        private List<Trace> activeTraces = ImmutableList.of();
        private long capturedAt;
        private long captureTick;
        private List<TraceSnapshotSummary> summaries;

        private String handle(String message) throws IOException {
            logger.debug("getPoints(): message={}", message);
            try {
                request = gson.fromJson(message, TraceRequest.class);
            } catch (JsonSyntaxException e) {
                logger.warn(e.getMessage(), e);
                return writeResponse(ImmutableList.<TraceSnapshotSummary> of(),
                        ImmutableList.<Trace> of(), 0, 0, false);
            }
            requestAt = clock.currentTimeMillis();
            if (request.getFrom() < 0) {
                request.setFrom(requestAt + request.getFrom());
            }
            low = (long) Math.ceil(request.getLow() * NANOSECONDS_PER_MILLISECOND);
            high = request.getHigh() == 0 ? Long.MAX_VALUE : (long) Math.floor(request.getHigh()
                    * NANOSECONDS_PER_MILLISECOND);
            String comparatorText = request.getUserIdComparator();
            if (comparatorText != null) {
                userIdComparator = StringComparator.valueOf(comparatorText
                        .toUpperCase(Locale.ENGLISH));
            }
            boolean captureActiveTraces = shouldCaptureActiveTraces();
            if (captureActiveTraces) {
                // capture active traces first to make sure that none are missed in the transition
                // between active and pending/stored (possible duplicates are removed below)
                getMatchingActiveTraces();
            }
            if (request.getTo() == 0) {
                request.setTo(requestAt);
            }
            summaries = getStoredAndPendingSummaries(captureActiveTraces);
            removeDuplicatesBetweenActiveTracesAndSummaries();
            boolean limitExceeded = (summaries.size() + activeTraces.size() > request.getLimit());
            if (summaries.size() + activeTraces.size() > request.getLimit()) {
                // summaries is already ordered, so just drop the last few items
                // always include all active traces
                summaries = summaries.subList(0, request.getLimit() - activeTraces.size());
            }
            return writeResponse(summaries, activeTraces, capturedAt, captureTick, limitExceeded);
        }

        private boolean shouldCaptureActiveTraces() {
            return (request.getTo() == 0 || request.getTo() > requestAt)
                    && request.getFrom() < requestAt;
        }

        private List<TraceSnapshotSummary> getStoredAndPendingSummaries(
                boolean captureActiveTraces) {

            List<TraceSnapshotSummary> matchingPendingSummaries;
            // it only seems worth looking at pending traces if request asks for active traces
            if (captureActiveTraces) {
                // important to grab pending traces before stored summaries to ensure none are
                // missed in the transition between pending and stored
                matchingPendingSummaries = getMatchingPendingSummaries();
            } else {
                matchingPendingSummaries = ImmutableList.of();
            }
            List<TraceSnapshotSummary> storedSummaries = traceSnapshotDao.readSummaries(
                    request.getFrom(), request.getTo(), low, high, request.isBackground(),
                    request.isErrorOnly(), request.isFineOnly(), userIdComparator,
                    request.getUserId(), request.getLimit() + 1);
            if (!matchingPendingSummaries.isEmpty()) {
                // create single merged and limited list of summaries
                List<TraceSnapshotSummary> combinedSummaries = Lists.newArrayList(storedSummaries);
                for (TraceSnapshotSummary pendingSummary : matchingPendingSummaries) {
                    mergeIntoCombinedSummaries(pendingSummary, combinedSummaries);
                }
                return combinedSummaries;
            } else {
                return storedSummaries;
            }
        }

        private void getMatchingActiveTraces() {
            activeTraces = Lists.newArrayList();
            for (Trace trace : traceRegistry.getTraces()) {
                if (traceSnapshotService.shouldPersist(trace)
                        && matchesDuration(trace)
                        && matchesBackground(trace)
                        && matchesErrorOnly(trace)
                        && matchesFineOnly(trace)
                        && matchesUserId(trace)) {
                    activeTraces.add(trace);
                }
            }
            Collections.sort(activeTraces,
                    Ordering.natural().onResultOf(new Function<Trace, Long>() {
                        public Long apply(@Nullable Trace trace) {
                            return trace.getStartTick();
                        }
                    }));
            if (activeTraces.size() > request.getLimit()) {
                activeTraces = activeTraces.subList(0, request.getLimit());
            }
            // take capture timings after the capture to make sure there are no traces captured
            // that start after the recorded capture time (resulting in negative duration)
            capturedAt = clock.currentTimeMillis();
            captureTick = ticker.read();
        }

        private List<TraceSnapshotSummary> getMatchingPendingSummaries() {
            List<TraceSnapshotSummary> summaries = Lists.newArrayList();
            for (Trace trace : traceSinkLocal.getPendingCompleteTraces()) {
                if (matchesDuration(trace)
                        && matchesBackground(trace)
                        && matchesErrorOnly(trace)
                        && matchesFineOnly(trace)
                        && matchesUserId(trace)) {
                    summaries.add(TraceSnapshotSummary.from(trace.getId(),
                            clock.currentTimeMillis(), trace.getDuration(), true));
                }
            }
            return summaries;
        }

        private boolean matchesDuration(Trace trace) {
            long duration = trace.getDuration();
            return duration >= low && duration <= high;
        }

        private boolean matchesBackground(Trace trace) {
            return request.isBackground() == null || request.isBackground() == trace.isBackground();
        }

        private boolean matchesErrorOnly(Trace trace) {
            return !request.isErrorOnly() || trace.isError();
        }

        private boolean matchesFineOnly(Trace trace) {
            return !request.isFineOnly() || trace.isFine();
        }

        private boolean matchesUserId(Trace trace) {
            if (userIdComparator == null || request.getUserId() == null) {
                return true;
            }
            String traceUserId = trace.getUserId();
            if (traceUserId == null) {
                return false;
            }
            switch (userIdComparator) {
            case BEGINS:
                return traceUserId.startsWith(request.getUserId());
            case CONTAINS:
                return traceUserId.contains(request.getUserId());
            case EQUALS:
                return traceUserId.equals(request.getUserId());
            default:
                logger.error("unexpected user id comparator '{}'", userIdComparator);
                return false;
            }
        }

        private void mergeIntoCombinedSummaries(TraceSnapshotSummary pendingSummary,
                List<TraceSnapshotSummary> combinedSummaries) {

            boolean duplicate = false;
            int orderedInsertionIndex = 0;
            // check if duplicate and capture ordered insertion index at the same time
            for (int i = 0; i < combinedSummaries.size(); i++) {
                TraceSnapshotSummary summary = combinedSummaries.get(i);
                if (pendingSummary.getDuration() < summary.getDuration()) {
                    // keep pushing orderedInsertionIndex down the line
                    orderedInsertionIndex = i + 1;
                }
                if (pendingSummary.getId().equals(summary.getId())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                combinedSummaries.add(orderedInsertionIndex, pendingSummary);
            }
        }

        private void removeDuplicatesBetweenActiveTracesAndSummaries() {
            for (Iterator<Trace> i = activeTraces.iterator(); i.hasNext();) {
                Trace activeTrace = i.next();
                for (Iterator<TraceSnapshotSummary> j = summaries.iterator(); j.hasNext();) {
                    TraceSnapshotSummary summary = j.next();
                    if (activeTrace.getId().equals(summary.getId())) {
                        // prefer stored trace if it is completed, otherwise prefer active trace
                        if (summary.isCompleted()) {
                            i.remove();
                        } else {
                            j.remove();
                        }
                        // there can be at most one duplicate per id, so ok to break to outer
                        break;
                    }
                }
            }
        }

        private String writeResponse(List<TraceSnapshotSummary> summaries,
                List<Trace> activeTraces, long capturedAt, long captureTick, boolean limitExceeded)
                throws IOException {

            StringWriter sw = new StringWriter();
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("activePoints").beginArray();
            for (Trace activeTrace : activeTraces) {
                jw.beginArray();
                jw.value(capturedAt);
                jw.value((captureTick - activeTrace.getStartTick()) / 1000000000.0);
                jw.value(activeTrace.getId());
                jw.endArray();
            }
            jw.endArray();
            jw.name("storedPoints").beginArray();
            for (TraceSnapshotSummary summary : summaries) {
                jw.beginArray();
                jw.value(summary.getCapturedAt());
                jw.value(summary.getDuration() / 1000000000.0);
                jw.value(summary.getId());
                jw.endArray();
            }
            jw.endArray();
            if (limitExceeded) {
                jw.name("limitExceeded");
                jw.value(true);
            }
            jw.endObject();
            jw.close();
            return sw.toString();
        }
    }
}
