/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;

import com.oracle.truffle.api.Option;

@Option.Group(PolyglotEngineImpl.OPTION_GROUP_ENGINE)
final class PolyglotEngineOptions {
    static final String PREINITIALIZE_CONTEXT_NAME = "PreinitializeContexts";
    private static final String INSTRUMENT_EXCEPTIONS_ARE_THROWN_NAME = "InstrumentExceptionsAreThrown";

    @Option(name = PREINITIALIZE_CONTEXT_NAME, category = OptionCategory.EXPERT, deprecated = true, help = "Preinitialize language contexts for given languages.")//
    static final OptionKey<String> PreinitializeContexts = new OptionKey<>("");

    /**
     * When the option is set the exceptions thrown by instruments are propagated rather than logged
     * into err.
     */
    @Option(name = INSTRUMENT_EXCEPTIONS_ARE_THROWN_NAME, category = OptionCategory.INTERNAL, help = "Propagates exceptions thrown by instruments.")//
    static final OptionKey<Boolean> InstrumentExceptionsAreThrown = new OptionKey<>(false);

    @Option(category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = "Show internal frames specific to the language implementation in stack traces.")//
    static final OptionKey<Boolean> ShowInternalStackFrames = new OptionKey<>(false);

    @Option(category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = "Enables conservative context references. " +
                    "This allows invalid sharing between contexts. " +
                    "For testing purposes only.")//
    static final OptionKey<Boolean> UseConservativeContextReferences = new OptionKey<>(false);

    @Option(category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = "Enables specialization statistics for nodes generated with Truffle DSL and prints the result on exit. " +
                    "In order for this flag to be functional -Atruffle.dsl.GenerateSpecializationStatistics=true needs to be set at build time." + //
                    "Enabling this flag and the compiler option has major implications on the performance and footprint of the interpreter." + //
                    "Do not use in production environments.")//
    static final OptionKey<Boolean> SpecializationStatistics = new OptionKey<>(false);

    @Option(category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = "Traces thread local events and when they are processed on the individual threads." +
                    "Prints messages with the [engine] [tl] prefix. ")//
    static final OptionKey<Boolean> TraceThreadLocalActions = new OptionKey<>(false);

    @Option(category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = "" +
                    "Repeadly submits thread local actions and collects statistics about safepoint intervals in the process. " +
                    "Prints event and interval statistics when the context is closed for each thread. " +
                    "This option significantly slows down execution and is therefore intended for testing purposes only.")//
    static final OptionKey<Boolean> SafepointALot = new OptionKey<>(false);

    @Option(category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, help = "" +
                    "Prints the stack trace for all threads for a time interval. By default 0, which disables the output.")//
    static final OptionKey<Long> TraceStackTraceInterval = new OptionKey<>(0L);

    @Option(category = OptionCategory.USER, stability = OptionStability.STABLE, help = "" +
                    "Print warning when the engine is using a default Truffle runtime.")//
    static final OptionKey<Boolean> WarnInterpreterOnly = new OptionKey<>(true);

    @Option(category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = "" +
                    "Use pre-initialized context when it's available.")//
    static final OptionKey<Boolean> UsePreInitializedContext = new OptionKey<>(true);

}
