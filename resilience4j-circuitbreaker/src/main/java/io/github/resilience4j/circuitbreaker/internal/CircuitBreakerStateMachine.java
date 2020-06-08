/*
 *
 *  Copyright 2019 Robert Winkler
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.circuitbreaker.internal;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.ResultRecordedAsFailureException;
import io.github.resilience4j.circuitbreaker.event.*;
import io.github.resilience4j.core.EventConsumer;
import io.github.resilience4j.core.EventProcessor;
import io.github.resilience4j.core.lang.Nullable;
import io.vavr.collection.HashMap;
import io.vavr.collection.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.*;
import static io.github.resilience4j.circuitbreaker.internal.CircuitBreakerMetrics.Result;
import static io.github.resilience4j.circuitbreaker.internal.CircuitBreakerMetrics.Result.BELOW_THRESHOLDS;
import static java.time.temporal.ChronoUnit.MILLIS;

/**
 * A CircuitBreaker finite state machine.
 */
public final class CircuitBreakerStateMachine implements CircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreakerStateMachine.class);

    private final String name;
    private final AtomicReference<CircuitBreakerState> stateReference;
    private final CircuitBreakerConfig circuitBreakerConfig;
    private final Map<String, String> tags;
    private final CircuitBreakerEventProcessor eventProcessor;
    private final Clock clock;
    private final SchedulerFactory schedulerFactory;

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     * @param clock                A Clock which can be mocked in tests.
     * @param schedulerFactory     A SchedulerFactory which can be mocked in tests.
     */
    private CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock, SchedulerFactory schedulerFactory,
        io.vavr.collection.Map<String, String> tags) {
        this.name = name;
        this.circuitBreakerConfig = Objects
            .requireNonNull(circuitBreakerConfig, "Config must not be null");
        this.eventProcessor = new CircuitBreakerEventProcessor();
        this.clock = clock;
        this.stateReference = new AtomicReference<>(new ClosedState());
        this.schedulerFactory = schedulerFactory;
        this.tags = Objects.requireNonNull(tags, "Tags must not be null");
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     * @param schedulerFactory     A SchedulerFactory which can be mocked in tests.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        SchedulerFactory schedulerFactory) {
        this(name, circuitBreakerConfig, Clock.systemUTC(), schedulerFactory, HashMap.empty());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock) {
        this(name, circuitBreakerConfig, clock, SchedulerFactory.getInstance(), HashMap.empty());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock, io.vavr.collection.Map<String, String> tags) {
        this(name, circuitBreakerConfig, clock, SchedulerFactory.getInstance(), tags);
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig) {
        this(name, circuitBreakerConfig, Clock.systemUTC());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     * @param tags                 Tags to add to the CircuitBreaker.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        io.vavr.collection.Map<String, String> tags) {
        this(name, circuitBreakerConfig, Clock.systemUTC(), tags);
    }

    /**
     * Creates a circuitBreaker with default config.
     *
     * @param name the name of the CircuitBreaker
     */
    public CircuitBreakerStateMachine(String name) {
        this(name, CircuitBreakerConfig.ofDefaults());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration supplier.
     */
    public CircuitBreakerStateMachine(String name,
        Supplier<CircuitBreakerConfig> circuitBreakerConfig) {
        this(name, circuitBreakerConfig.get());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration supplier.
     */
    public CircuitBreakerStateMachine(String name,
        Supplier<CircuitBreakerConfig> circuitBreakerConfig,
        io.vavr.collection.Map<String, String> tags) {
        this(name, circuitBreakerConfig.get(), tags);
    }

    @Override
    public boolean tryAcquirePermission() {
        boolean callPermitted = stateReference.get().tryAcquirePermission();
        if (!callPermitted) {
            publishCallNotPermittedEvent();
        }
        return callPermitted;
    }

    @Override
    public void releasePermission() {
        stateReference.get().releasePermission();
    }

    @Override
    public void acquirePermission() {
        try {
            stateReference.get().acquirePermission();
        } catch (Exception e) {
            publishCallNotPermittedEvent();
            throw e;
        }
    }

    @Override
    public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
        // Handle the case if the completable future throws a CompletionException wrapping the original exception
        // where original exception is the the one to retry not the CompletionException.
        if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
            Throwable cause = throwable.getCause();
            handleThrowable(duration, durationUnit, cause);
        } else {
            handleThrowable(duration, durationUnit, throwable);
        }
    }

    private void handleThrowable(long duration, TimeUnit durationUnit, Throwable throwable) {
        if (circuitBreakerConfig.getIgnoreExceptionPredicate().test(throwable)) {
            LOG.debug("CircuitBreaker '{}' ignored an exception:", name, throwable);
            releasePermission();
            publishCircuitIgnoredErrorEvent(name, duration, durationUnit, throwable);
        } else if (circuitBreakerConfig.getRecordExceptionPredicate().test(throwable)) {
            LOG.debug("CircuitBreaker '{}' recorded an exception as failure:", name, throwable);
            publishCircuitErrorEvent(name, duration, durationUnit, throwable);
            stateReference.get().onError(duration, durationUnit, throwable);
        } else {
            LOG.debug("CircuitBreaker '{}' recorded an exception as success:", name, throwable);
            publishSuccessEvent(duration, durationUnit);
            stateReference.get().onSuccess(duration, durationUnit);
        }
    }

    @Override
    public void onSuccess(long duration, TimeUnit durationUnit, Optional<?> result) {
        handleResult(duration, durationUnit, result);
    }

    private void handleResult(long duration, TimeUnit durationUnit, Optional<?> result) {
        if (result.isPresent() && circuitBreakerConfig.getRecordResultPredicate().test(result.get())) {
            LOG.debug("CircuitBreaker '{}' recorded a result type '{}' as failure:", name, result.get().getClass());
            ResultRecordedAsFailureException failure = new ResultRecordedAsFailureException(name, result.get());
            publishCircuitErrorEvent(name, duration, durationUnit, failure);
            stateReference.get().onError(duration, durationUnit, failure);
        } else {
            LOG.debug("CircuitBreaker '{}' succeeded:", name);
            publishSuccessEvent(duration, durationUnit);
            stateReference.get().onSuccess(duration, durationUnit);
        }
    }

    /**
     * Get the state of this CircuitBreaker.
     *
     * @return the the state of this CircuitBreaker
     */
    @Override
    public State getState() {
        return this.stateReference.get().getState();
    }

    /**
     * Get the name of this CircuitBreaker.
     *
     * @return the the name of this CircuitBreaker
     */
    @Override
    public String getName() {
        return this.name;
    }

    /**
     * Get the config of this CircuitBreaker.
     *
     * @return the config of this CircuitBreaker
     */
    @Override
    public CircuitBreakerConfig getCircuitBreakerConfig() {
        return circuitBreakerConfig;
    }

    @Override
    public Metrics getMetrics() {
        return this.stateReference.get().getMetrics();
    }

    @Override
    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return String.format("CircuitBreaker '%s'", this.name);
    }

    @Override
    public void reset() {
        CircuitBreakerState previousState = stateReference
            .getAndUpdate(currentState -> new ClosedState());
        if (previousState.getState() != CLOSED) {
            publishStateTransitionEvent(
                StateTransition.transitionBetween(getName(), previousState.getState(), CLOSED));
        }
        publishResetEvent();
    }

    private void stateTransition(State newState,
        UnaryOperator<CircuitBreakerState> newStateGenerator) {
        CircuitBreakerState previousState = stateReference.getAndUpdate(currentState -> {
            StateTransition.transitionBetween(getName(), currentState.getState(), newState);
            currentState.preTransitionHook();
            return newStateGenerator.apply(currentState);
        });
        publishStateTransitionEvent(
            StateTransition.transitionBetween(getName(), previousState.getState(), newState));
    }

    @Override
    public void transitionToDisabledState() {
        stateTransition(DISABLED, currentState -> new DisabledState());
    }

    @Override
    public void transitionToMetricsOnlyState() {
        stateTransition(METRICS_ONLY, currentState -> new MetricsOnlyState());
    }

    @Override
    public void transitionToForcedOpenState() {
        stateTransition(FORCED_OPEN,
            currentState -> new ForcedOpenState(currentState.attempts() + 1));
    }

    @Override
    public void transitionToClosedState() {
        stateTransition(CLOSED, currentState -> new ClosedState());
    }

    @Override
    public void transitionToOpenState() {
        stateTransition(OPEN,
            currentState -> new OpenState(currentState.attempts() + 1, currentState.getMetrics()));
    }

    @Override
    public void transitionToHalfOpenState() {
        stateTransition(HALF_OPEN, currentState -> new HalfOpenState(currentState.attempts()));
    }


    private boolean shouldPublishEvents(CircuitBreakerEvent event) {
        return stateReference.get().shouldPublishEvents(event);
    }

    private void publishEventIfPossible(CircuitBreakerEvent event) {
        if (shouldPublishEvents(event)) {
            if (eventProcessor.hasConsumers()) {
                try {
                    eventProcessor.consumeEvent(event);
                    LOG.debug("Event {} published: {}", event.getEventType(), event);
                } catch (Throwable t) {
                    LOG.warn("Failed to handle event {}", event.getEventType(), t);
                }
            } else {
                LOG.debug("No Consumers: Event {} not published", event.getEventType());
            }
        } else {
            LOG.debug("Publishing not allowed: Event {} not published", event.getEventType());
        }
    }

    private void publishStateTransitionEvent(final StateTransition stateTransition) {
        if (StateTransition.isInternalTransition(stateTransition)) {
            return;
        }
        final CircuitBreakerOnStateTransitionEvent event = new CircuitBreakerOnStateTransitionEvent(
            name, stateTransition);
        publishEventIfPossible(event);
    }

    private void publishResetEvent() {
        final CircuitBreakerOnResetEvent event = new CircuitBreakerOnResetEvent(name);
        publishEventIfPossible(event);
    }

    private void publishCallNotPermittedEvent() {
        final CircuitBreakerOnCallNotPermittedEvent event = new CircuitBreakerOnCallNotPermittedEvent(
            name);
        publishEventIfPossible(event);
    }

    private void publishSuccessEvent(final long duration, TimeUnit durationUnit) {
        final CircuitBreakerOnSuccessEvent event = new CircuitBreakerOnSuccessEvent(name,
            Duration.ofNanos(durationUnit.toNanos(duration)));
        publishEventIfPossible(event);
    }

    private void publishCircuitErrorEvent(final String name, final long duration,
        TimeUnit durationUnit, final Throwable throwable) {
        final CircuitBreakerOnErrorEvent event = new CircuitBreakerOnErrorEvent(name,
            Duration.ofNanos(durationUnit.toNanos(duration)), throwable);
        publishEventIfPossible(event);
    }

    private void publishCircuitIgnoredErrorEvent(String name, long duration, TimeUnit durationUnit,
        Throwable throwable) {
        final CircuitBreakerOnIgnoredErrorEvent event = new CircuitBreakerOnIgnoredErrorEvent(name,
            Duration.ofNanos(durationUnit.toNanos(duration)), throwable);
        publishEventIfPossible(event);
    }

    private void publishCircuitFailureRateExceededEvent(String name, float failureRate) {
        final CircuitBreakerOnFailureRateExceededEvent event = new CircuitBreakerOnFailureRateExceededEvent(name,
            failureRate);
        publishEventIfPossible(event);
    }

    private void publishCircuitSlowCallRateExceededEvent(String name, float slowCallRate) {
        final CircuitBreakerOnSlowCallRateExceededEvent event = new CircuitBreakerOnSlowCallRateExceededEvent(name,
            slowCallRate);
        publishEventIfPossible(event);
    }

    private void publishCircuitThresholdsExceededEvent(Result result, CircuitBreakerMetrics metrics) {
        if (Result.hasFailureRateExceededThreshold(result)) {
            publishCircuitFailureRateExceededEvent(getName(), metrics.getFailureRate());
        }
        if (Result.hasSlowCallRateExceededThreshold(result)) {
            publishCircuitSlowCallRateExceededEvent(getName(), metrics.getSlowCallRate());
        }
    }

    @Override
    public EventPublisher getEventPublisher() {
        return eventProcessor;
    }

    private interface CircuitBreakerState {

        boolean tryAcquirePermission();

        void acquirePermission();

        void releasePermission();

        void onError(long duration, TimeUnit durationUnit, Throwable throwable);

        void onSuccess(long duration, TimeUnit durationUnit);

        int attempts();

        CircuitBreaker.State getState();

        CircuitBreakerMetrics getMetrics();

        /**
         * Should the CircuitBreaker in this state publish events
         *
         * @return a boolean signaling if the events should be published
         */
        default boolean shouldPublishEvents(CircuitBreakerEvent event) {
            return event.getEventType().forcePublish || getState().allowPublish;
        }

        /**
         * This method is invoked before transit to other CircuitBreakerState.
         */
        default void preTransitionHook() {
            // noOp
        }
    }

    private class CircuitBreakerEventProcessor extends
        EventProcessor<CircuitBreakerEvent> implements EventConsumer<CircuitBreakerEvent>,
        EventPublisher {

        @Override
        public EventPublisher onSuccess(
            EventConsumer<CircuitBreakerOnSuccessEvent> onSuccessEventConsumer) {
            registerConsumer(CircuitBreakerOnSuccessEvent.class.getSimpleName(),
                onSuccessEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onError(
            EventConsumer<CircuitBreakerOnErrorEvent> onErrorEventConsumer) {
            registerConsumer(CircuitBreakerOnErrorEvent.class.getSimpleName(),
                onErrorEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onStateTransition(
            EventConsumer<CircuitBreakerOnStateTransitionEvent> onStateTransitionEventConsumer) {
            registerConsumer(CircuitBreakerOnStateTransitionEvent.class.getSimpleName(),
                onStateTransitionEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onReset(
            EventConsumer<CircuitBreakerOnResetEvent> onResetEventConsumer) {
            registerConsumer(CircuitBreakerOnResetEvent.class.getSimpleName(),
                onResetEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onIgnoredError(
            EventConsumer<CircuitBreakerOnIgnoredErrorEvent> onIgnoredErrorEventConsumer) {
            registerConsumer(CircuitBreakerOnIgnoredErrorEvent.class.getSimpleName(),
                onIgnoredErrorEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onCallNotPermitted(
            EventConsumer<CircuitBreakerOnCallNotPermittedEvent> onCallNotPermittedEventConsumer) {
            registerConsumer(CircuitBreakerOnCallNotPermittedEvent.class.getSimpleName(),
                onCallNotPermittedEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onFailureRateExceeded(
            EventConsumer<CircuitBreakerOnFailureRateExceededEvent> onFailureRateExceededConsumer) {
            registerConsumer(CircuitBreakerOnFailureRateExceededEvent.class.getSimpleName(),
                onFailureRateExceededConsumer);
            return this;
        }

        @Override
        public EventPublisher onSlowCallRateExceeded(
            EventConsumer<CircuitBreakerOnSlowCallRateExceededEvent> onSlowCallRateExceededConsumer) {
            registerConsumer(CircuitBreakerOnSlowCallRateExceededEvent.class.getSimpleName(),
                onSlowCallRateExceededConsumer);
            return this;
        }

        @Override
        public void consumeEvent(CircuitBreakerEvent event) {
            super.processEvent(event);
        }
    }

    private class ClosedState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isClosed;

        ClosedState() {
            this.circuitBreakerMetrics = CircuitBreakerMetrics.forClosed(getCircuitBreakerConfig(), clock);
            this.isClosed = new AtomicBoolean(true);
        }

        /**
         * Returns always true, because the CircuitBreaker is closed.
         *
         * @return always true, because the CircuitBreaker is closed.
         */
        @Override
        public boolean tryAcquirePermission() {
            return isClosed.get();
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is closed.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Transitions to open state when thresholds have been exceeded.
         *
         * @param result the Result
         */
        private void checkIfThresholdsExceeded(Result result) {
            if (Result.hasExceededThresholds(result)) {
                if (isClosed.compareAndSet(true, false)) {
                    publishCircuitThresholdsExceededEvent(result, circuitBreakerMetrics);
                    transitionToOpenState();
                }
            }
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.CLOSED;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    private class OpenState implements CircuitBreakerState {

        private final int attempts;
        private final Instant retryAfterWaitDuration;
        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isOpen;

        @Nullable
        private final ScheduledFuture<?> transitionToHalfOpenFuture;

        OpenState(final int attempts, CircuitBreakerMetrics circuitBreakerMetrics) {
            this.attempts = attempts;
            final long waitDurationInMillis = circuitBreakerConfig
                .getWaitIntervalFunctionInOpenState().apply(attempts);
            this.retryAfterWaitDuration = clock.instant().plus(waitDurationInMillis, MILLIS);
            this.circuitBreakerMetrics = circuitBreakerMetrics;

            if (circuitBreakerConfig.isAutomaticTransitionFromOpenToHalfOpenEnabled()) {
                ScheduledExecutorService scheduledExecutorService = schedulerFactory.getScheduler();
                transitionToHalfOpenFuture = scheduledExecutorService
                    .schedule(this::toHalfOpenState, waitDurationInMillis, TimeUnit.MILLISECONDS);
            } else {
                transitionToHalfOpenFuture = null;
            }
            isOpen = new AtomicBoolean(true);
        }

        /**
         * Returns false, if the wait duration has not elapsed. Returns true, if the wait duration
         * has elapsed and transitions the state machine to HALF_OPEN state.
         *
         * @return false, if the wait duration has not elapsed. true, if the wait duration has
         * elapsed.
         */
        @Override
        public boolean tryAcquirePermission() {
            // Thread-safe
            if (clock.instant().isAfter(retryAfterWaitDuration)) {
                toHalfOpenState();
                return true;
            }
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            if (!tryAcquirePermission()) {
                throw CallNotPermittedException
                    .createCallNotPermittedException(CircuitBreakerStateMachine.this);
            }
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // Could be called when Thread 1 invokes acquirePermission when the state is CLOSED, but in the meantime another
            // Thread 2 calls onError and the state changes from CLOSED to OPEN before Thread 1 calls onError.
            // But the onError event should still be recorded, even if it happened after the state transition.
            circuitBreakerMetrics.onError(duration, durationUnit);
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // Could be called when Thread 1 invokes acquirePermission when the state is CLOSED, but in the meantime another
            // Thread 2 calls onError and the state changes from CLOSED to OPEN before Thread 1 calls onSuccess.
            // But the onSuccess event should still be recorded, even if it happened after the state transition.
            circuitBreakerMetrics.onSuccess(duration, durationUnit);
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }

        @Override
        public void preTransitionHook() {
            cancelAutomaticTransitionToHalfOpen();
        }

        private void toHalfOpenState() {
            if (isOpen.compareAndSet(true, false)) {
                transitionToHalfOpenState();
            }
        }

        private void cancelAutomaticTransitionToHalfOpen() {
            if (transitionToHalfOpenFuture != null && !transitionToHalfOpenFuture.isDone()) {
                transitionToHalfOpenFuture.cancel(true);
            }
        }

    }

    private class DisabledState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;

        DisabledState() {
            this.circuitBreakerMetrics = CircuitBreakerMetrics
                .forDisabled(getCircuitBreakerConfig(), clock);
        }

        /**
         * Returns always true, because the CircuitBreaker is disabled.
         *
         * @return always true, because the CircuitBreaker is disabled.
         */
        @Override
        public boolean tryAcquirePermission() {
            return true;
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is disabled.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // noOp
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // noOp
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.DISABLED;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    private class MetricsOnlyState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isFailureRateExceeded;
        private final AtomicBoolean isSlowCallRateExceeded;

        MetricsOnlyState() {
            circuitBreakerMetrics = CircuitBreakerMetrics
                .forMetricsOnly(getCircuitBreakerConfig(), clock);
            isFailureRateExceeded = new AtomicBoolean(false);
            isSlowCallRateExceeded = new AtomicBoolean(false);
        }

        /**
         * Returns always true, because the CircuitBreaker is always closed in this state.
         *
         * @return always true, because the CircuitBreaker is always closed in this state.
         */
        @Override
        public boolean tryAcquirePermission() {
            return true;
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is always closed in this state.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        private void checkIfThresholdsExceeded(Result result) {
            if (!Result.hasExceededThresholds(result)) {
                return;
            }

            if (shouldPublishFailureRateExceededEvent(result)) {
                publishCircuitThresholdsExceededEvent(result, circuitBreakerMetrics);
            }

            if (shouldPublishSlowCallRateExceededEvent(result)) {
                publishCircuitThresholdsExceededEvent(result, circuitBreakerMetrics);
            }
        }

        private boolean shouldPublishFailureRateExceededEvent(Result result) {
            return Result.hasFailureRateExceededThreshold(result) &&
                isFailureRateExceeded.compareAndSet(false, true);
        }

        private boolean shouldPublishSlowCallRateExceededEvent(Result result) {
            return Result.hasSlowCallRateExceededThreshold(result) &&
                isSlowCallRateExceeded.compareAndSet(false, true);
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.METRICS_ONLY;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    private class ForcedOpenState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final int attempts;

        ForcedOpenState(int attempts) {
            this.attempts = attempts;
            this.circuitBreakerMetrics = CircuitBreakerMetrics.forForcedOpen(circuitBreakerConfig, clock);
        }

        /**
         * Returns always false, and records the rejected call.
         *
         * @return always false, since the FORCED_OPEN state always denies calls.
         */
        @Override
        public boolean tryAcquirePermission() {
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            circuitBreakerMetrics.onCallNotPermitted();
            throw CallNotPermittedException
                .createCallNotPermittedException(CircuitBreakerStateMachine.this);
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // noOp
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.FORCED_OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    private class HalfOpenState implements CircuitBreakerState {

        private final AtomicInteger permittedNumberOfCalls;
        private final AtomicBoolean isHalfOpen;
        private final int attempts;
        private final CircuitBreakerMetrics circuitBreakerMetrics;

        HalfOpenState(int attempts) {
            int permittedNumberOfCallsInHalfOpenState = circuitBreakerConfig
                .getPermittedNumberOfCallsInHalfOpenState();
            this.circuitBreakerMetrics = CircuitBreakerMetrics
                .forHalfOpen(permittedNumberOfCallsInHalfOpenState, getCircuitBreakerConfig(), clock);
            this.permittedNumberOfCalls = new AtomicInteger(permittedNumberOfCallsInHalfOpenState);
            this.isHalfOpen = new AtomicBoolean(true);
            this.attempts = attempts;
        }

        /**
         * Checks if test request is allowed.
         * <p>
         * Returns true, if test request counter is not zero. Returns false, if test request counter
         * is zero.
         *
         * @return true, if test request counter is not zero.
         */
        @Override
        public boolean tryAcquirePermission() {
            if (permittedNumberOfCalls.getAndUpdate(current -> current == 0 ? current : --current)
                > 0) {
                return true;
            }
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            if (!tryAcquirePermission()) {
                throw CallNotPermittedException
                    .createCallNotPermittedException(CircuitBreakerStateMachine.this);
            }
        }

        @Override
        public void releasePermission() {
            permittedNumberOfCalls.incrementAndGet();
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Transitions to open state when thresholds have been exceeded. Transitions to closed state
         * when thresholds have not been exceeded.
         *
         * @param result the result
         */
        private void checkIfThresholdsExceeded(Result result) {
            if (Result.hasExceededThresholds(result)) {
                if (isHalfOpen.compareAndSet(true, false)) {
                    transitionToOpenState();
                }
            }
            if (result == BELOW_THRESHOLDS) {
                if (isHalfOpen.compareAndSet(true, false)) {
                    transitionToClosedState();
                }
            }
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.HALF_OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }
}
