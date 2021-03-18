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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.jdk.Target_java_nio_DirectByteBuffer;
import com.oracle.svm.core.thread.VMOperationControl;

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
    private static final int JFR_VERSION_MAJOR = 2;
    private static final int JFR_VERSION_MINOR = 0;
    private static final int CHUNK_SIZE_OFFSET = 8;


    private final ReentrantLock lock;
    private final boolean compressedInts;
    private final JfrGlobalMemory globalMemory;
    private long notificationThreshold;

    private String filename;
    private RandomAccessFile file;
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
        return file != null;
    }

    public void setFilename(String filename) {
        assert lock.isHeldByCurrentThread();
        this.filename = filename;
    }

    public String getFilename() {
        return this.filename;
    }

    public void maybeOpenFile() {
        assert lock.isHeldByCurrentThread();
        if (filename != null) {
            openFile(filename);
        }
    }

    public boolean openFile(String outputFile) {
        assert lock.isHeldByCurrentThread();
        filename = outputFile;
        chunkStartNanos = JfrTicks.currentTimeNanos();
        chunkStartTicks = JfrTicks.elapsedTicks();
        try {
            file = new RandomAccessFile(outputFile, "rw");
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
        int capacity = NumUtil.safeToInt(unflushedSize.rawValue());
        Target_java_nio_DirectByteBuffer bb = new Target_java_nio_DirectByteBuffer(buffer.getTop().rawValue(), capacity);
        FileChannel fc = file.getChannel();
        try {
            fc.write(SubstrateUtil.cast(bb, ByteBuffer.class));
            JfrBufferAccess.increaseTop(buffer, unflushedSize);
            return file.getFilePointer() > notificationThreshold;
        } catch (IOException e) {
            Logger.log(JFR_SYSTEM, ERROR, "Error while writing file " + filename + ": " + e.getMessage());
            return false;
        }
    }



    private void writeFileHeader() throws IOException {
        // Write the header - some of the data gets patched later on.
        file.write(FILE_MAGIC);
        file.writeShort(JFR_VERSION_MAJOR);
        file.writeShort(JFR_VERSION_MINOR);
        assert file.getFilePointer() == CHUNK_SIZE_OFFSET;
        file.writeLong(0L); // chunk size
        file.writeLong(0L); // last checkpoint offset
        file.writeLong(0L); // metadata position
        file.writeLong(0L); // startNanos
        file.writeLong(0L); // durationNanos
        file.writeLong(chunkStartTicks);
        file.writeLong(JfrTicks.getTicksFrequency());
        file.writeInt(compressedInts ? 1 : 0);
    }

    public void patchFileHeader(long constantPoolPosition, long metadataPosition) throws IOException {
        long chunkSize = file.getFilePointer();
        long durationNanos = JfrTicks.currentTimeNanos() - chunkStartNanos;
        file.seek(CHUNK_SIZE_OFFSET);
        file.writeLong(chunkSize);
        file.writeLong(constantPoolPosition);
        file.writeLong(metadataPosition);
        file.writeLong(chunkStartNanos);
        file.writeLong(durationNanos);
    }

    public boolean shouldRotateDisk() {
        assert lock.isHeldByCurrentThread();
        try {
            return file != null && file.length() > notificationThreshold;
        } catch (IOException ex) {
            Logger.log(JFR_SYSTEM, ERROR, "Could not check file size to determine chunk rotation: " + ex.getMessage());
            return false;
        }
    }

    public long beginEvent() throws IOException {
        long start = file.getFilePointer();
        // Write a placeholder for the size. Will be patched by endEvent,
        file.writeInt(0);
        return start;
    }

    public void endEvent(long start) throws IOException {
        long end = file.getFilePointer();
        long writtenBytes = end - start;
        file.seek(start);
        file.writeInt(makePaddedInt(writtenBytes));
        file.seek(end);
    }

    public void writeBoolean(boolean value) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        writeCompressedInt(value ? 1 : 0);
    }

    public void writeByte(byte value) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        file.write(value);
    }

    public void writeBytes(byte[] values) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        file.write(values);
    }

    public void writeCompressedInt(int value) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        writeCompressedLong(value & 0xFFFFFFFFL);
    }

    public void writeCompressedLong(long value) throws IOException {
        assert lock.isHeldByCurrentThread() || VMOperationControl.isDedicatedVMOperationThread() && lock.isLocked();
        long v = value;
        if ((v & ~0x7FL) == 0L) {
            file.write((byte) v); // 0-6
            return;
        }
        file.write((byte) (v | 0x80L)); // 0-6
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            file.write((byte) v); // 7-13
            return;
        }
        file.write((byte) (v | 0x80L)); // 7-13
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            file.write((byte) v); // 14-20
            return;
        }
        file.write((byte) (v | 0x80L)); // 14-20
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            file.write((byte) v); // 21-27
            return;
        }
        file.write((byte) (v | 0x80L)); // 21-27
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            file.write((byte) v); // 28-34
            return;
        }
        file.write((byte) (v | 0x80L)); // 28-34
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            file.write((byte) v); // 35-41
            return;
        }
        file.write((byte) (v | 0x80L)); // 35-41
        v >>>= 7;
        if ((v & ~0x7FL) == 0L) {
            file.write((byte) v); // 42-48
            return;
        }
        file.write((byte) (v | 0x80L)); // 42-48
        v >>>= 7;

        if ((v & ~0x7FL) == 0L) {
            file.write((byte) v); // 49-55
            return;
        }
        file.write((byte) (v | 0x80L)); // 49-55
        file.write((byte) (v >>> 7)); // 56-63, last byte as is.
    }

    public void close() throws IOException {
        try {
            file.close();
        } finally {
            filename = null;
            file = null;
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
            file.writeByte(StringEncoding.EMPTY_STRING.byteValue);
        } else {
            file.writeByte(StringEncoding.UTF8_BYTE_ARRAY.byteValue);
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(str);
            writeCompressedInt(bytes.limit() - bytes.position());
            file.write(bytes.array(), bytes.position(), bytes.limit());
        }
    }

    private static int makePaddedInt(long sizeWritten) {
        return JfrNativeEventWriter.makePaddedInt(NumUtil.safeToInt(sizeWritten));
    }
}
