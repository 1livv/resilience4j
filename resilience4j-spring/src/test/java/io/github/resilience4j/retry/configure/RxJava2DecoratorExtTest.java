/*
 * Copyright 2019 Mahmoud Romeh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.retry.configure;

import io.github.resilience4j.retry.Retry;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.vavr.CheckedFunction0;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * aspect unit test
 */
@RunWith(MockitoJUnitRunner.class)
public class RxJava2DecoratorExtTest {

    @Mock
    CheckedFunction0<Object> function;

    @InjectMocks
    RxJava2DecoratorExt rxJava2RetryAspectExt;


    @Test
    public void testCheckTypes() {
        assertThat(rxJava2RetryAspectExt.canDecorateReturnType(Flowable.class)).isTrue();
        assertThat(rxJava2RetryAspectExt.canDecorateReturnType(Single.class)).isTrue();
    }

    @Test
    public void testReactorTypes() throws Throwable {
        Retry retry = Retry.ofDefaults("test");

        when(function.apply()).thenReturn(Single.just("Test"));
        assertThat(rxJava2RetryAspectExt.decorate(retry, function).apply())
            .isNotNull();

        when(function.apply()).thenReturn(Flowable.just("Test"));
        assertThat(rxJava2RetryAspectExt.decorate(retry, function).apply())
            .isNotNull();
    }
}