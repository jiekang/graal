/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, 2021, Red Hat Inc. All rights reserved.
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

import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.util.VMError;

public final class JfrThreadLocalMemory {
    private static JfrBuffer head;
    private static final VMMutex mutex = new VMMutex();

    private JfrThreadLocalMemory() {
    }

    @Uninterruptible(reason = "Accesses a JFR buffer.")
    public static JfrBuffer acquireBuffer(long bufferSize) {
        mutex.lockNoTransition();
        try {
            JfrBuffer node = JfrBufferAccess.allocate(WordFactory.unsigned(bufferSize));
            node.setNext(head);
            head = node;
            return node;
        } finally {
            mutex.unlock();
        }
    }

    @Uninterruptible(reason = "Accesses a JFR buffer.")
    private static void removeBuffer(JfrBuffer buffer) {
        mutex.lockNoTransition();
        try {
            if (buffer.equal(head)) {
                head = head.getNext();
                JfrBufferAccess.free(buffer);
                return;
            }
            JfrBuffer prev = head;
            JfrBuffer node = head.getNext();

            while (node.isNonNull()) {
                if (buffer.equal(node)) {
                    prev.setNext(node.getNext());
                    JfrBufferAccess.free(buffer);
                    return;
                }
                prev = node;
                node = node.getNext();
            }

            throw VMError.shouldNotReachHere("JFR Thread Local buffer is lost.");
        } finally {
            mutex.unlock();
        }
    }

    @Uninterruptible(reason = "Accesses a JFR buffer.")
    public static void writeBuffers(JfrChunkWriter writer) {
        JfrBuffer node = head;
        while (node.isNonNull()) {
            if (!node.getRetired()) {
                if (!JfrBufferAccess.acquire(node)) {
                    // Thread local buffers are acquired when flushing to promotion buffer
                    // or when flushing to disk. If acquired already, someone else is
                    // handling the data for us
                    continue;
                }
                try {
                    writer.write(node);
                } finally {
                    JfrBufferAccess.release(node);
                }
                node = node.getNext();
            } else {
                // TODO Optimize this to remove while iterating for writes
                // instead of the current second iteration in removeBuffer
                assert !JfrBufferAccess.isAcquired(node);
                // TODO Buffer should be flushed and reinitialized before marking as
                // retired but this assert sometimes fails at the moment
                assert JfrBufferAccess.getUnflushedSize(node).equal(0);
                JfrBuffer toRemove = node;
                node = node.getNext();
                removeBuffer(toRemove);
            }
        }
    }
}
