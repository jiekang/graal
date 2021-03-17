package com.oracle.svm.jfr;

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.thread.VMOperation;

public class JfrEpoch {
    private static boolean epoch = false;
    private static boolean synchronizing = false;

    @Uninterruptible(reason = "Called by uninterruptible code.")
    public static void beginEpochShift() {
        assert VMOperation.isInProgressAtSafepoint();
        synchronizing = true;
    }

    @Uninterruptible(reason = "Called by uninterruptible code.")
    public static void endEpochShift() {
        assert VMOperation.isInProgressAtSafepoint();
        epoch = !epoch;
        synchronizing = false;
    }

    public static boolean getCurrentEpoch() {
        return epoch;
    }

    public static boolean getPreviousEpoch() {
        return !epoch;
    }
}
