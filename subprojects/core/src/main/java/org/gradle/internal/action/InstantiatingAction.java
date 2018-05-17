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

package org.gradle.internal.action;

import org.gradle.api.Action;
import org.gradle.api.internal.changedetection.state.isolation.Isolatable;
import org.gradle.caching.internal.BuildCacheHasher;
import org.gradle.internal.reflect.Instantiator;

import javax.annotation.Nullable;

public class InstantiatingAction<T> implements Action<T> {
    public static final Isolatable<Object[]> NO_PARAMS = new Isolatable<Object[]>() {
        @Override
        public Object[] isolate() {
            return new Object[0];
        }

        @Nullable
        @Override
        public <S> Isolatable<S> coerce(Class<S> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendToHasher(BuildCacheHasher hasher) {
            throw new UnsupportedOperationException();
        }
    };

    private final Class<? extends Action<T>> rule;
    private final Isolatable<Object[]> params;
    private final Instantiator instantiator;
    private final ExceptionHandler<T> exceptionHandler;

    public InstantiatingAction(Class<? extends Action<T>> rule, Isolatable<Object[]> params, Instantiator instantiator, ExceptionHandler<T> exceptionHandler) {
        this.rule = rule;
        this.params = params;
        this.instantiator = instantiator;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void execute(T target) {
        try {
            Action<T> instance = instantiator.newInstance(rule, params.isolate());
            instance.execute(target);
        } catch (Throwable t) {
            exceptionHandler.handleException(target, t);
        }
    }

    public interface ExceptionHandler<U> {
        void handleException(U target, Throwable throwable);
    }
}
