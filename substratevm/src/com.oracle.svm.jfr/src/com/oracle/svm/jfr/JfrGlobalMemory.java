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

import com.oracle.svm.core.locks.VMMutex;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.annotate.Uninterruptible;

/**
 * Manages the global JFR memory. A lot of the methods must be uninterruptible to ensure that we can
 * iterate and process the global JFR memory at a safepoint without having to worry about partial
 * modifications that were interrupted by the safepoint.
 */
public class JfrGlobalMemory {
    private static final int PROMOTION_RETRY_COUNT = 100;
    private static final long discardThreshold = 2; // Begin discarding when there are only 2 free buffers

    private final VMMutex retiredMutex;

    private long bufferCount;
    private long toRetireCount;
    private long bufferSize;
    private boolean toDisk = false;
    private JfrBuffers buffers;

    private long retiredCount;
    private JfrBuffer retiredHead;
    private JfrBuffer retiredTail;


    @Platforms(Platform.HOSTED_ONLY.class)
    public JfrGlobalMemory() {
        retiredMutex = new VMMutex();
    }

    public void initialize(long globalBufferSize, long globalBufferCount) {
        this.bufferCount = globalBufferCount;
        this.toRetireCount = bufferCount - discardThreshold;
        this.bufferSize = globalBufferSize;
        this.retiredCount = 0;

        // Allocate all buffers eagerly.
        buffers = UnmanagedMemory.calloc(SizeOf.unsigned(JfrBuffer.class).multiply(WordFactory.unsigned(bufferCount)));
        for (int i = 0; i < bufferCount; i++) {
            JfrBuffer buffer = JfrBufferAccess.allocate(WordFactory.unsigned(bufferSize));
            buffers.addressOf(i).write(buffer);
        }
    }

    public void teardown() {
        if (buffers.isNonNull()) {
            for (int i = 0; i < bufferCount; i++) {
                JfrBuffer buffer = buffers.addressOf(i).read();
                JfrBufferAccess.free(buffer);
            }
            UnmanagedMemory.free(buffers);
            buffers = WordFactory.nullPointer();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public JfrBuffers getBuffers() {
        assert buffers.isNonNull();
        return buffers;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public long getBufferCount() {
        return bufferCount;
    }

    @Uninterruptible(reason = "Epoch must not change while in this method.")
    public boolean write(JfrBuffer threadLocalBuffer, UnsignedWord unflushedSize) {
        JfrBuffer promotionBuffer = acquirePromotionBuffer(unflushedSize);
        if (promotionBuffer.isNull()) {
            return false;
        }
        try {
            // Copy all committed but not yet flushed memory to the promotion buffer.
            assert JfrBufferAccess.getAvailableSize(promotionBuffer).aboveOrEqual(unflushedSize);
            UnmanagedMemoryUtil.copy(threadLocalBuffer.getTop(), promotionBuffer.getPos(), unflushedSize);
            JfrBufferAccess.increasePos(promotionBuffer, unflushedSize);
        } finally {
             releasePromotionBuffer(promotionBuffer);
        }
        JfrBufferAccess.increaseTop(threadLocalBuffer, unflushedSize);
        return true;
    }

    @Uninterruptible(reason = "Epoch must not change while in this method.")
    private JfrBuffer acquirePromotionBuffer(UnsignedWord size) {
        while (true) {
            JfrBuffer buffer = acquireBufferWithRetry(size, PROMOTION_RETRY_COUNT);
            if (buffer.isNull() && shouldDiscard()) {
                discardOldest();
                continue;
            }
            return buffer;
        }
    }

    @Uninterruptible(reason = "Epoch must not change while in this method.")
    private JfrBuffer acquireBufferWithRetry(UnsignedWord size, int retryCount) {
        assert size.belowOrEqual(WordFactory.unsigned(bufferSize));
        for (int retry = 0; retry < retryCount; retry++) {
            for (int i = 0; i < bufferCount; i++) {
                JfrBuffer buffer = buffers.addressOf(i).read();
                if (buffer.getRetired()) {
                    continue;
                }
                if (JfrBufferAccess.acquire(buffer)) {
                    if (buffer.getRetired()) {
                        JfrBufferAccess.release(buffer);
                        continue;
                    }
                    if (JfrBufferAccess.getAvailableSize(buffer).aboveOrEqual(size)) {
                        return buffer;
                    }
                    retireBuffer(buffer);
                    JfrBufferAccess.release(buffer);
                }
                JfrRecorderThread recorderThread = SubstrateJVM.getRecorderThread();
                if (recorderThread.shouldSignal()) {
                    recorderThread.signal();
                }
            }
        }
        return WordFactory.nullPointer();
    }

    @Uninterruptible(reason = "Epoch must not change while in this method.")
    private static void releasePromotionBuffer(JfrBuffer buffer) {
        assert JfrBufferAccess.isAcquired(buffer);
        JfrBufferAccess.release(buffer);
    }

    @Uninterruptible(reason = "Epoch must not change while in this method.")
    private void retireBuffer(JfrBuffer buffer) {
        assert JfrBufferAccess.isAcquired(buffer);
        retiredMutex.lockNoTransition();
        try {
            if (buffer.getRetired()) {
                return;
            }
            buffer.setRetired(true);
            if (retiredHead.isNonNull()) {
                retiredTail.setNext(buffer);
            } else {
                retiredHead = buffer;
            }
            retiredTail = buffer;
            retiredCount++;
        } finally {
            retiredMutex.unlock();
        }
    }

    public void setToDisk(boolean toDisk) {
        this.toDisk = toDisk;
    }

    @Uninterruptible(reason = "Epoch must not change while in this method.")
    private void discardOldest() {
        JfrBuffer toDiscard = removeRetiredBuffer();
        if (toDiscard.isNonNull()) {
            JfrBufferAccess.reinitialize(toDiscard);
            assert JfrBufferAccess.getUnflushedSize(toDiscard).equal(0);
            JfrBufferAccess.release(toDiscard);
        }
    }

    @Uninterruptible(reason = "Epoch must not change while in this method.")
    private boolean shouldDiscard() {
        return !toDisk && retiredCount >= toRetireCount;
    }

    public boolean hasRetiredBuffer() {
        return retiredCount > 0;
    }

    @Uninterruptible(reason = "Epoch must not change while in this method.")
    public JfrBuffer removeRetiredBuffer() {
        retiredMutex.lockNoTransition();
        try {
            if (retiredCount < 1) {
                return WordFactory.nullPointer();
            }
            assert retiredHead.isNonNull() && retiredTail.isNonNull();
            JfrBuffer toRemove = retiredHead;
            while (!JfrBufferAccess.acquire(toRemove)) {
                JfrBufferAccess.acquire(toRemove);
            }
            if (retiredTail.equal(retiredHead)) {
                retiredTail = retiredHead.getNext();
            }
            retiredHead = retiredHead.getNext();
            retiredCount--;
            return toRemove;
        } finally {
            retiredMutex.unlock();
        }
    }
}
