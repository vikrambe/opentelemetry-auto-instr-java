/*
 * Copyright 2020, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.opentelemetry.auto.instrumentation.javaconcurrent;

import static io.opentelemetry.auto.bootstrap.instrumentation.java.concurrent.AdviceUtils.TRACER;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import io.opentelemetry.auto.bootstrap.ContextStore;
import io.opentelemetry.auto.bootstrap.InstrumentationContext;
import io.opentelemetry.auto.bootstrap.instrumentation.java.concurrent.CallableWrapper;
import io.opentelemetry.auto.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import io.opentelemetry.auto.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import io.opentelemetry.auto.bootstrap.instrumentation.java.concurrent.State;
import io.opentelemetry.auto.tooling.Instrumenter;
import io.opentelemetry.trace.Span;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JavaExecutorInstrumentation extends AbstractExecutorInstrumentation {

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put(Runnable.class.getName(), State.class.getName());
    map.put(Callable.class.getName(), State.class.getName());
    map.put(ForkJoinTask.class.getName(), State.class.getName());
    map.put(Future.class.getName(), State.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("execute").and(takesArgument(0, Runnable.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetExecuteRunnableStateAdvice");
    transformers.put(
        named("execute").and(takesArgument(0, ForkJoinTask.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetJavaForkJoinStateAdvice");
    transformers.put(
        named("submit").and(takesArgument(0, Runnable.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetSubmitRunnableStateAdvice");
    transformers.put(
        named("submit").and(takesArgument(0, Callable.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetCallableStateAdvice");
    transformers.put(
        named("submit").and(takesArgument(0, ForkJoinTask.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetJavaForkJoinStateAdvice");
    transformers.put(
        nameMatches("invoke(Any|All)$").and(takesArgument(0, Collection.class)),
        JavaExecutorInstrumentation.class.getName()
            + "$SetCallableStateForCallableCollectionAdvice");
    transformers.put(
        nameMatches("invoke").and(takesArgument(0, ForkJoinTask.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetJavaForkJoinStateAdvice");
    transformers.put(
        named("schedule").and(takesArgument(0, Runnable.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetSubmitRunnableStateAdvice");
    transformers.put(
        named("schedule").and(takesArgument(0, Callable.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetCallableStateAdvice");
    return transformers;
  }

  public static class SetExecuteRunnableStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      final Span span = TRACER.getCurrentSpan();
      final Runnable newTask = RunnableWrapper.wrapIfNeeded(task);
      // It is important to check potentially wrapped task if we can instrument task in this
      // executor. Some executors do not support wrapped tasks.
      if (ExecutorInstrumentationUtils.shouldAttachStateToTask(newTask, executor)) {
        task = newTask;
        final ContextStore<Runnable, State> contextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        return ExecutorInstrumentationUtils.setupState(contextStore, newTask, span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable) {
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(state, throwable);
    }
  }

  public static class SetJavaForkJoinStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) final ForkJoinTask task) {
      final Span span = TRACER.getCurrentSpan();
      if (ExecutorInstrumentationUtils.shouldAttachStateToTask(task, executor)) {
        final ContextStore<ForkJoinTask, State> contextStore =
            InstrumentationContext.get(ForkJoinTask.class, State.class);
        return ExecutorInstrumentationUtils.setupState(contextStore, task, span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable) {
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(state, throwable);
    }
  }

  public static class SetSubmitRunnableStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      final Span span = TRACER.getCurrentSpan();
      final Runnable newTask = RunnableWrapper.wrapIfNeeded(task);
      // It is important to check potentially wrapped task if we can instrument task in this
      // executor. Some executors do not support wrapped tasks.
      if (ExecutorInstrumentationUtils.shouldAttachStateToTask(newTask, executor)) {
        task = newTask;
        final ContextStore<Runnable, State> contextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        return ExecutorInstrumentationUtils.setupState(contextStore, newTask, span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Future future) {
      if (state != null && future != null) {
        final ContextStore<Future, State> contextStore =
            InstrumentationContext.get(Future.class, State.class);
        contextStore.put(future, state);
      }
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(state, throwable);
    }
  }

  public static class SetCallableStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Callable task) {
      final Span span = TRACER.getCurrentSpan();
      final Callable newTask = CallableWrapper.wrapIfNeeded(task);
      // It is important to check potentially wrapped task if we can instrument task in this
      // executor. Some executors do not support wrapped tasks.
      if (ExecutorInstrumentationUtils.shouldAttachStateToTask(newTask, executor)) {
        task = newTask;
        final ContextStore<Callable, State> contextStore =
            InstrumentationContext.get(Callable.class, State.class);
        return ExecutorInstrumentationUtils.setupState(contextStore, newTask, span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Future future) {
      if (state != null && future != null) {
        final ContextStore<Future, State> contextStore =
            InstrumentationContext.get(Future.class, State.class);
        contextStore.put(future, state);
      }
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(state, throwable);
    }
  }

  public static class SetCallableStateForCallableCollectionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Collection<?> submitEnter(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Collection<? extends Callable<?>> tasks) {
      final Span span = TRACER.getCurrentSpan();
      if (span != null && tasks != null) {
        final Collection<Callable<?>> wrappedTasks = new ArrayList<>(tasks.size());
        for (final Callable<?> task : tasks) {
          if (task != null) {
            final Callable newTask = CallableWrapper.wrapIfNeeded(task);
            if (ExecutorInstrumentationUtils.isExecutorDisabledForThisTask(executor, newTask)) {
              wrappedTasks.add(task);
            } else {
              wrappedTasks.add(newTask);
              final ContextStore<Callable, State> contextStore =
                  InstrumentationContext.get(Callable.class, State.class);
              ExecutorInstrumentationUtils.setupState(contextStore, newTask, span);
            }
          }
        }
        tasks = wrappedTasks;
        return tasks;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void submitExit(
        @Advice.This final Executor executor,
        @Advice.Enter final Collection<? extends Callable<?>> wrappedTasks,
        @Advice.Thrown final Throwable throwable) {
      /*
       Note1: invokeAny doesn't return any futures so all we need to do for it
       is to make sure we close all scopes in case of an exception.
       Note2: invokeAll does return futures - but according to its documentation
       it actually only returns after all futures have been completed - i.e. it blocks.
       This means we do not need to setup any hooks on these futures, we just need to clean
       up any continuations in case of an error.
       (according to ExecutorService docs and AbstractExecutorService code)
      */
      if (null != throwable && wrappedTasks != null) {
        for (final Callable<?> task : wrappedTasks) {
          if (task != null) {
            final ContextStore<Callable, State> contextStore =
                InstrumentationContext.get(Callable.class, State.class);
            final State state = contextStore.get(task);
            if (state != null) {
              /*
              Note: this may potentially close somebody else's continuation if we didn't set it
              up in setupState because it was already present before us. This should be safe but
              may lead to non-attributed async work in some very rare cases.
              Alternative is to not close continuation here if we did not set it up in setupState
              but this may potentially lead to memory leaks if callers do not properly handle
              exceptions.
               */
              state.clearParentSpan();
            }
          }
        }
      }
    }
  }
}
