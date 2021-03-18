/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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

import static jdk.jfr.internal.LogLevel.ERROR;
import static jdk.jfr.internal.LogTag.JFR_SYSTEM;

import java.io.IOException;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.jfr.traceid.JfrTraceIdEpoch;

import jdk.jfr.internal.Logger;

public class JfrRecorderService {
    private static final int BUFFER_FULL_ENOUGH_PERCENTAGE = 50;

    private final JfrGlobalMemory globalMemory;

    JfrRecorderService(JfrGlobalMemory globalMemory) {
        this.globalMemory = globalMemory;
    }

    /**
     * We are writing all the in-memory data to the file. However, even though we are at a
     * safepoint, further JFR events can still be triggered by the current thread at any time. This
     * includes allocation and GC events. Therefore, it is necessary that our whole JFR
     * infrastructure is epoch-based. So, we can uninterruptibly switch to a new epoch before we
     * start writing out the data of the old epoch.
     */
    // TODO: add more logic to all JfrRepositories so that it is possible to switch the epoch. The
    // global JFR memory must also support different epochs.
    public void rotateChunk(JfrChunkWriter writer, byte[] metadataDescriptor, JfrRepository[] repositories) {
        JfrCloseFileOperation op = new JfrCloseFileOperation(writer, metadataDescriptor, repositories);
        op.enqueue();
    }

    private class JfrCloseFileOperation extends JavaVMOperation {
        private final byte[] metadataDescriptor;
        private final JfrRepository[] repositories;
        private final JfrChunkWriter writer;

        protected JfrCloseFileOperation(JfrChunkWriter writer, byte[] metadataDescriptor, JfrRepository[] repositories) {
            // Some of the JDK code that deals with files uses Java synchronization. So, we need to
            // allow Java synchronization for this VM operation.
            super("JFR close file", SystemEffect.SAFEPOINT, true);
            this.metadataDescriptor = metadataDescriptor;
            this.repositories = repositories;
            this.writer = writer;
        }

        @Override
        protected void operate() {
            // Flush global buffers to disk before changing epoch to make room for transferring thread local buffer data
            persistBuffers(writer);
            changeEpoch();
            try {
                persistBuffers(writer);
                long constantPoolPosition = writeCheckpointEvent(writer, repositories);
                long metadataPosition = writeMetadataEvent(writer, metadataDescriptor);
                writer.patchFileHeader(constantPoolPosition, metadataPosition);
                writer.close();
            } catch (IOException e) {
                Logger.log(JFR_SYSTEM, ERROR, "Error while closing chunk " + writer.getFilename() + ": " + e.getMessage());
            }
        }

        @Uninterruptible(reason = "Prevent pollution of the current thread's thread local JFR buffer.")
        private void changeEpoch() {
            // We need to ensure that all JFR events that are triggered by the current thread
            // are recorded for the next epoch. Otherwise, those JFR events could pollute the data
            // that we currently try to persist. To ensure that, we must execute the following steps
            // uninterruptibly:
            //
            // - Flush all thread-local buffers (native & Java) to global JFR memory.
            // - Set all Java EventWriter.notified values
            // - Change the epoch.
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                JfrThreadLocal.flushAndNotifyAtSafepoint(thread);
            }
            JfrTraceIdEpoch.getInstance().changeEpoch();
        }
    }

    private long writeCheckpointEvent(JfrChunkWriter writer, JfrRepository[] repositories) throws IOException {
        JfrSerializer[] serializers = JfrSerializerSupport.get().getSerializers();

        // TODO: Write the global buffers of the previous epoch to disk. Assert that none of the
        // buffers from the previous epoch is acquired (all operations on the buffers must have
        // finished before the safepoint).

        long start = writer.beginEvent();
        writer.writeCompressedLong(JfrEvents.CheckpointEvent.getId());
        writer.writeCompressedLong(JfrTicks.elapsedTicks());
        writer.writeCompressedLong(0); // duration
        writer.writeCompressedLong(0); // deltaToNext
        writer.writeBoolean(true); // flush
        int count = 0;
        // TODO: This should be simplified, serializers and repositories can probably go under the same
        // structure.
        for (JfrSerializer serializer : serializers) {
            if (serializer.hasItems()) {
                count++;
            }
        }
        for (JfrRepository repository : repositories) {
            if (repository.hasItems()) {
                count++;
            }
        }
        writer.writeCompressedInt(count); // pools size
        writeSerializers(writer, serializers);
        writeRepositories(writer, repositories);
        writer.endEvent(start);

        return start;
    }

    private long writeMetadataEvent(JfrChunkWriter writer, byte[] metadataDescriptor) throws IOException {
        long start = writer.beginEvent();
        writer.writeCompressedLong(JfrEvents.MetadataEvent.getId());
        writer.writeCompressedLong(JfrTicks.elapsedTicks());
        writer.writeCompressedLong(0); // duration
        writer.writeCompressedLong(0); // metadata id
        writer.writeBytes(metadataDescriptor); // payload
        writer.endEvent(start);
        return start;
    }

    private void writeSerializers(JfrChunkWriter writer, JfrSerializer[] serializers) throws IOException {
        for (JfrSerializer serializer : serializers) {
            if (serializer.hasItems()) {
                serializer.write(writer);
            }
        }
    }

    private void writeRepositories(JfrChunkWriter writer, JfrRepository[] constantPools) throws IOException {
        for (JfrRepository constantPool : constantPools) {
            if (constantPool.hasItems()) {
                constantPool.write(writer);
            }
        }
    }

    public void persistBuffers(JfrChunkWriter writer) {
        JfrBuffers buffers = globalMemory.getBuffers();
        for (int i = 0; i < globalMemory.getBufferCount(); i++) {
            JfrBuffer buffer = buffers.addressOf(i).read();
            if (isFullEnough(buffer) && JfrBufferAccess.acquire(buffer)) {
                boolean shouldNotify = writer.write(buffer);
                JfrBufferAccess.reinitialize(buffer);
                JfrBufferAccess.release(buffer);

                if (shouldNotify) {
                    //Checkstyle: stop
                    synchronized (Target_jdk_jfr_internal_JVM.FILE_DELTA_CHANGE) {
                        Target_jdk_jfr_internal_JVM.FILE_DELTA_CHANGE.notifyAll();
                    }
                    //Checkstyle: resume
                }
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isFullEnough(JfrBuffer buffer) {
        UnsignedWord bufferTargetSize = buffer.getSize().multiply(100).unsignedDivide(BUFFER_FULL_ENOUGH_PERCENTAGE);
        return JfrBufferAccess.getAvailableSize(buffer).belowOrEqual(bufferTargetSize);
    }
}
