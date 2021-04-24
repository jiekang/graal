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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;


import com.oracle.svm.core.thread.VMOperation;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.SignedWord;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.os.RawFileOperationSupport;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.VMOperationControl;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.jfr.traceid.JfrTraceIdEpoch;

import jdk.jfr.internal.Logger;

/**
 * This class is used when writing the in-memory JFR data to a file. For all operations, except
 * those listed in {@link JfrUnlockedChunkWriter}, it is necessary to acquire the {@link #lock}
 * before invoking the operation.
 *
 * If an operation needs both a safepoint and the lock, then it is necessary to acquire the lock
 * outside of the safepoint. Otherwise, this will result in deadlocks as other threads may hold the
 * lock while they are paused at a safepoint.
 */
public final class JfrChunkWriter implements JfrUnlockedChunkWriter {
    private static final byte[] FILE_MAGIC = {'F', 'L', 'R', '\0'};
    private static final short JFR_VERSION_MAJOR = 2;
    private static final short JFR_VERSION_MINOR = 0;
    private static final int CHUNK_SIZE_OFFSET = 8;

    private final JfrGlobalMemory globalMemory;

    private final ReentrantLock lock;
    private final boolean compressedInts;
    private long notificationThreshold;

    private String filename;
    private RawFileOperationSupport fileOperationSupport;
    private RawFileOperationSupport.RawFileDescriptor fd;
    private long chunkStartTicks;
    private long chunkStartNanos;

    @Platforms(Platform.HOSTED_ONLY.class)
    public JfrChunkWriter(JfrGlobalMemory globalMemory) {
        this.lock = new ReentrantLock();
        this.compressedInts = true;
        this.globalMemory = globalMemory;
    }

    @Override
    public void initialize(long maxChunkSize) {
        this.fileOperationSupport = RawFileOperationSupport.bigEndian();
        this.notificationThreshold = maxChunkSize;
    }

    @Override
    public JfrChunkWriter lock() {
        lock.lock();
        return this;
    }

    public void unlock() {
        lock.unlock();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @Override
    public boolean hasOpenFile() {
        return filename != null;
    }

    public void setFilename(String filename) {
        assert lock.isHeldByCurrentThread();
        this.filename = filename;
    }

    public void maybeOpenFile() {
        assert lock.isHeldByCurrentThread();
        if (filename != null) {
            openFile(filename);
        }
    }

    public boolean openFile(String outputFile) {
        assert lock.isHeldByCurrentThread();
        chunkStartNanos = JfrTicks.currentTimeNanos();
        chunkStartTicks = JfrTicks.elapsedTicks();
        try {
            filename = outputFile;
            fd = fileOperationSupport.open(filename, RawFileOperationSupport.FileAccessMode.READ_WRITE);
            writeFileHeader();
            // TODO: this should probably also write all live threads
            return true;
        } catch (IOException e) {
            Logger.log(JFR_SYSTEM, ERROR, "Error while writing file " + filename + ": " + e.getMessage());
            return false;
        }
    }

    public boolean write(JfrBuffer buffer) {
        assert lock.isHeldByCurrentThread()  || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        UnsignedWord unflushedSize = JfrBufferAccess.getUnflushedSize(buffer);
        if (unflushedSize.equal(0)) {
            return false;
        }

        boolean success = fileOperationSupport.write(fd, JfrBufferAccess.getDataStart(buffer), unflushedSize);
        if (!success) {
            return false;
        }
        JfrBufferAccess.increaseTop(buffer, unflushedSize);
        return fileOperationSupport.position(fd).rawValue() > notificationThreshold;
    }

    @Uninterruptible(reason = "Called by uninterruptible code")
    public boolean writeAtSafepoint(JfrBuffer buffer) {
        assert VMOperation.isInProgressAtSafepoint();
        UnsignedWord unflushedSize = JfrBufferAccess.getUnflushedSize(buffer);
        if (unflushedSize.equal(0)) {
            return false;
        }

        boolean success = fileOperationSupport.write(fd, JfrBufferAccess.getDataStart(buffer), unflushedSize);
        if (!success) {
            return false;
        }
        JfrBufferAccess.increaseTop(buffer, unflushedSize);
        return fileOperationSupport.position(fd).rawValue() > notificationThreshold;
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
    public void closeFile(byte[] metadataDescriptor, JfrRepository[] repositories) {
        assert lock.isHeldByCurrentThread();
        JfrCloseFileOperation op = new JfrCloseFileOperation();
        op.enqueue();

        try {
            SignedWord constantPoolPosition = writeCheckpointEvent(repositories);
            SignedWord metadataPosition = writeMetadataEvent(metadataDescriptor);
            patchFileHeader(constantPoolPosition, metadataPosition);
            fileOperationSupport.close(fd);
        } catch (IOException e) {
            Logger.log(JFR_SYSTEM, ERROR, "Error while writing file " + filename + ": " + e.getMessage());
        }

        filename = null;
    }

    private void writeFileHeader() throws IOException {
        // Write the header - some of the data gets patched later on.
        fileOperationSupport.write(fd, FILE_MAGIC);
        fileOperationSupport.writeShort(fd, JFR_VERSION_MAJOR);
        fileOperationSupport.writeShort(fd, JFR_VERSION_MINOR);
        assert fileOperationSupport.position(fd).equal(CHUNK_SIZE_OFFSET);
        fileOperationSupport.writeLong(fd, 0L); // chunk size
        fileOperationSupport.writeLong(fd, 0L); // last checkpoint offset
        fileOperationSupport.writeLong(fd, 0L); // metadata position
        fileOperationSupport.writeLong(fd, 0L); // startNanos
        fileOperationSupport.writeLong(fd, 0L); // durationNanos
        fileOperationSupport.writeLong(fd, chunkStartTicks);
        fileOperationSupport.writeLong(fd, JfrTicks.getTicksFrequency());
        fileOperationSupport.writeInt(fd, compressedInts ? 1 : 0);
    }

    public void patchFileHeader(SignedWord constantPoolPosition, SignedWord metadataPosition) throws IOException {
        long chunkSize = fileOperationSupport.position(fd).rawValue();
        long durationNanos = JfrTicks.currentTimeNanos() - chunkStartNanos;
        fileOperationSupport.seek(fd, WordFactory.signed(CHUNK_SIZE_OFFSET));
        fileOperationSupport.writeLong(fd, chunkSize);
        fileOperationSupport.writeLong(fd, constantPoolPosition.rawValue());
        fileOperationSupport.writeLong(fd, metadataPosition.rawValue());
        fileOperationSupport.writeLong(fd, chunkStartNanos);
        fileOperationSupport.writeLong(fd, durationNanos);
    }

    private SignedWord writeCheckpointEvent(JfrRepository[] repositories) throws IOException {
        JfrSerializer[] serializers = JfrSerializerSupport.get().getSerializers();

        // TODO: Write the global buffers of the previous epoch to disk. Assert that none of the
        // buffers from the previous epoch is acquired (all operations on the buffers must have
        // finished before the safepoint).

        SignedWord start = beginEvent();
        writeCompressedLong(JfrEvents.CheckpointEvent.getId());
        writeCompressedLong(JfrTicks.elapsedTicks());
        writeCompressedLong(0); // duration
        writeCompressedLong(0); // deltaToNext
        writeBoolean(true); // flush
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
        writeCompressedInt(count); // pools size
        writeSerializers(serializers);
        writeRepositories(repositories);
        endEvent(start);

        return start;
    }

    private void writeSerializers(JfrSerializer[] serializers) throws IOException {
        for (JfrSerializer serializer : serializers) {
            if (serializer.hasItems()) {
                serializer.write(this);
            }
        }
    }

    private void writeRepositories(JfrRepository[] constantPools) throws IOException {
        for (JfrRepository constantPool : constantPools) {
            if (constantPool.hasItems()) {
                constantPool.write(this);
            }
        }
    }

    private SignedWord writeMetadataEvent(byte[] metadataDescriptor) throws IOException {
        SignedWord start = beginEvent();
        writeCompressedLong(JfrEvents.MetadataEvent.getId());
        writeCompressedLong(JfrTicks.elapsedTicks());
        writeCompressedLong(0); // duration
        writeCompressedLong(0); // metadata id
        writeBytes(metadataDescriptor); // payload
        endEvent(start);
        return start;
    }

    public boolean shouldRotateDisk() {
        assert lock.isHeldByCurrentThread();
        return filename != null && fileOperationSupport.size(fd).rawValue() > notificationThreshold;
    }

    public SignedWord beginEvent() throws IOException {
        SignedWord start = fileOperationSupport.position(fd);
        // Write a placeholder for the size. Will be patched by endEvent,
        fileOperationSupport.writeInt(fd, 0);
        return start;
    }


    public void endEvent(SignedWord start) throws IOException {
        SignedWord end = fileOperationSupport.position(fd);
        long writtenBytes = end.rawValue() - start.rawValue();
        fileOperationSupport.seek(fd, start);
        fileOperationSupport.writeInt(fd, makePaddedInt(writtenBytes));
        fileOperationSupport.seek(fd, end);
    }

    public void writeBoolean(boolean value) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        writeCompressedInt(value ? 1 : 0);
    }

    public void writeByte(byte value) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        fileOperationSupport.writeByte(fd, value);
    }

    public void writeBytes(byte[] values) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        fileOperationSupport.write(fd, values);
    }

    public void writeCompressedInt(int value) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        writeCompressedLong(value & 0xFFFFFFFFL);
    }

    public void writeCompressedLong(long value) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        long v = value;
        if ((v & ~0x7FL) == 0L) {
            fileOperationSupport.writeByte(fd, (byte) v); // 0-6
            return;
        }
        fileOperationSupport.writeByte(fd, (byte) (v | 0x80L)); // 0-6
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            fileOperationSupport.writeByte(fd, (byte) v); // 7-13
            return;
        }
        fileOperationSupport.writeByte(fd, (byte) (v | 0x80L)); // 7-13
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            fileOperationSupport.writeByte(fd, (byte) v); // 14-20
            return;
        }
        fileOperationSupport.writeByte(fd, (byte) (v | 0x80L)); // 14-20
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            fileOperationSupport.writeByte(fd, (byte) v); // 21-27
            return;
        }
        fileOperationSupport.writeByte(fd, (byte) (v | 0x80L)); // 21-27
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            fileOperationSupport.writeByte(fd, (byte) v); // 28-34
            return;
        }
        fileOperationSupport.writeByte(fd, (byte) (v | 0x80L)); // 28-34
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            fileOperationSupport.writeByte(fd, (byte) v); // 35-41
            return;
        }
        fileOperationSupport.writeByte(fd, (byte) (v | 0x80L)); // 35-41
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            fileOperationSupport.writeByte(fd, (byte) v); // 42-48
            return;
        }
        fileOperationSupport.writeByte(fd, (byte) (v | 0x80L)); // 42-48
        v >>>= 7;

        if ((v & ~0x7FL) == 0L) {
            fileOperationSupport.writeByte(fd, (byte) v); // 49-55
            return;
        }
        fileOperationSupport.writeByte(fd, (byte) (v | 0x80L)); // 49-55
        fileOperationSupport.writeByte(fd, (byte) (v >>> 7)); // 56-63, last byte as is.
    }

    public void close() throws IOException {
        try {
            fileOperationSupport.close(fd);
        } finally {
            filename = null;
        }
    }

    public enum StringEncoding {
        NULL(0),
        EMPTY_STRING(1),
        CONSTANT_POOL(2),
        UTF8_BYTE_ARRAY(3),
        CHAR_ARRAY(4),
        LATIN1_BYTE_ARRAY(5);
        public byte byteValue;
        StringEncoding(int byteValue) {
            this.byteValue = (byte) byteValue;
        }
    }

    public void writeString(String str) throws IOException {
        // TODO: Implement writing strings in the other encodings
        if (str.isEmpty()) {
            fileOperationSupport.writeByte(fd, StringEncoding.EMPTY_STRING.byteValue);
        } else {
            fileOperationSupport.writeByte(fd, StringEncoding.UTF8_BYTE_ARRAY.byteValue);
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            writeCompressedInt(bytes.length);
            fileOperationSupport.write(fd, bytes);
        }
    }

    private static int makePaddedInt(long sizeWritten) {
        return JfrNativeEventWriter.makePaddedInt(NumUtil.safeToInt(sizeWritten));
    }

    private class JfrCloseFileOperation extends JavaVMOperation {

        protected JfrCloseFileOperation() {
            // Some of the JDK code that deals with files uses Java synchronization. So, we need to
            // allow Java synchronization for this VM operation.
            super("JFR close file", SystemEffect.SAFEPOINT, true);
        }

        @Override
        protected void operate() {
            boolean shouldNotify = changeEpoch();
            if (shouldNotify) {
                //Checkstyle: stop
                synchronized (Target_jdk_jfr_internal_JVM.FILE_DELTA_CHANGE) {
                    Target_jdk_jfr_internal_JVM.FILE_DELTA_CHANGE.notifyAll();
                }
                //Checkstyle: resume
            }
        }

        @Uninterruptible(reason = "Prevent pollution of the current thread's thread local JFR buffer.")
        private boolean changeEpoch() {
            // TODO: We need to ensure that all JFR events that are triggered by the current thread
            // are recorded for the next epoch. Otherwise, those JFR events could pollute the data
            // that we currently try to persist. To ensure that, we must execute the following steps
            // uninterruptibly:
            //
            // - Flush all buffers (native, Java and global) to disk
            // - Set all Java EventWriter.notified values
            // - Change the epoch
            boolean shouldNotify = false;
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                JfrBuffer b = JfrThreadLocal.getJavaBuffer(thread);
                if (b.isNonNull()) {
                    writeAtSafepoint(b);
                    JfrThreadLocal.notifyEventWriter(thread);
                }
                b = JfrThreadLocal.getNativeBuffer(thread);
                if (b.isNonNull()) {
                    writeAtSafepoint(b);
                }
            }

            JfrBuffers buffers = globalMemory.getBuffers();
            for (int i = 0; i < globalMemory.getBufferCount(); i++) {
                JfrBuffer buffer = buffers.addressOf(i).read();
                if (JfrBufferAccess.acquire(buffer)) {
                    shouldNotify = writeAtSafepoint(buffer);
                    JfrBufferAccess.reinitialize(buffer);
                    JfrBufferAccess.release(buffer);
                }
            }
            JfrTraceIdEpoch.getInstance().changeEpoch();
            return shouldNotify;
        }
    }
}
