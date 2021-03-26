/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.jfr;

import org.graalvm.nativeimage.c.struct.RawField;
import org.graalvm.nativeimage.c.struct.RawFieldOffset;
import org.graalvm.nativeimage.c.struct.RawStructure;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.util.VMError;

/**
 * A {@link JfrBuffer} is a block of native memory (either thread-local or global) into which JFR
 * events are written.
 */
@RawStructure
public interface JfrBuffer extends PointerBase {
    @RawField
    JfrBuffer getNext();

    @RawField
    void setNext(JfrBuffer next);

    @RawField
    UnsignedWord getSize();

    @RawField
    void setSize(UnsignedWord value);

    @RawField
    Pointer getPos();

    @RawField
    void setPos(Pointer value);

    @RawFieldOffset
    static int offsetOfPos() {
        throw VMError.unimplemented(); // replaced
    }

    @RawField
    Pointer getTop();

    @RawField
    void setTop(Pointer value);

    @RawFieldOffset
    static int offsetOfTop() {
        throw VMError.unimplemented(); // replaced
    }

    @RawField
    int getAcquired();

    @RawField
    void setAcquired(int value);

    @RawFieldOffset
    static int offsetOfAcquired() {
        throw VMError.unimplemented(); // replaced
    }
}
