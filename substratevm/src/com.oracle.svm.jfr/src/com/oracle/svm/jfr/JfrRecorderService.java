package com.oracle.svm.jfr;

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.VMThreads;
import jdk.jfr.internal.Logger;
import org.graalvm.nativeimage.IsolateThread;

import java.io.IOException;

import static jdk.jfr.internal.LogLevel.ERROR;
import static jdk.jfr.internal.LogTag.JFR_SYSTEM;

public class JfrRecorderService {
    private final JfrGlobalMemory globalMemory;
    private final JfrThreadLocal threadLocal;

    public JfrRecorderService(JfrGlobalMemory globalMemory, JfrThreadLocal threadLocal) {
        this.globalMemory = globalMemory;
        this.threadLocal = threadLocal;
    }

    public void rotate(JfrChunkWriter chunkWriter, byte[] metadataDescriptor, JfrRepository[] repositories) {
        write(chunkWriter, metadataDescriptor, repositories);
    }

    private void write(JfrChunkWriter chunkWriter, byte[] metadataDescriptor, JfrRepository[] repositories) {
        preSafepointWrite(chunkWriter);
        safepointWrite(chunkWriter, metadataDescriptor, repositories);
        postSafepointWrite(chunkWriter);
    }

    private void preSafepointWrite(JfrChunkWriter chunkWriter) {
    }

    /**
     * We are writing all the in-memory data to the file. However, even though we are at a
     * safepoint, further JFR events can still be triggered by the current thread at any time. This
     * includes allocation and GC events. Therefore, it is necessary that our whole JFR
     * infrastructure is epoch-based. So, we can uninterruptibly switch to a new epoch before we
     * start writing out the data of the old epoch.
     */
    // TODO: add more logic to all JfrRepositories so that it is possible to switch the epoch. The
    // global JFR memory must also support different epochs.1
    private void safepointWrite(JfrChunkWriter chunkWriter, byte[] metadataDescriptor, JfrRepository[] repositories) {
        JfrCloseFileOperation op = new JfrCloseFileOperation(metadataDescriptor, repositories, chunkWriter);
        op.enqueue();
    }

    private void postSafepointWrite(JfrChunkWriter chunkWriter) {
    }

    private class JfrCloseFileOperation extends JavaVMOperation {
        private final byte[] metadataDescriptor;
        private final JfrRepository[] repositories;
        private final JfrChunkWriter chunkWriter;

        protected JfrCloseFileOperation(byte[] metadataDescriptor, JfrRepository[] repositories, JfrChunkWriter chunkWriter) {
            // Some of the JDK code that deals with files uses Java synchronization. So, we need to
            // allow Java synchronization for this VM operation.
            super("JFR close file", SystemEffect.SAFEPOINT, true);
            this.metadataDescriptor = metadataDescriptor;
            this.repositories = repositories;
            this.chunkWriter = chunkWriter;
        }

        @Override
        protected void operate() {
            changeEpoch();
            chunkWriter.closeChunk(repositories, metadataDescriptor);
        }

        @Uninterruptible(reason = "Prevent pollution of the current thread's thread local JFR buffer.")
        private void changeEpoch() {
            // TODO: We need to ensure that all JFR events that are triggered by the current thread
            // are recorded for the next epoch. Otherwise, those JFR events could pollute the data
            // that we currently try to persist. To ensure that, we must execute the following steps
            // uninterruptibly:
            //
            // - Flush all thread-local buffers (native & Java) to global JFR memory.
            // - Set all Java EventWriter.notified values
            // - Change the epoch.
            JfrEpoch.beginEpochShift();

            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                JfrBuffer jb = SubstrateJVM.get().getThreadLocalBuffer(thread);
                if (jb.isNonNull()) {
                    chunkWriter.writeAtSafepoint(jb);
                }
                Target_jdk_jfr_internal_EventWriter eventWriter = SubstrateJVM.get().getEventWriter(thread);
                if (eventWriter != null) {
                    eventWriter.notified = true;

                }
            }

            JfrEpoch.endEpochShift();
        }
    }
}
