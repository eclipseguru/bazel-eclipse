/*-
 *
 */
package com.salesforce.bazel.eclipse.core.model;

import static com.salesforce.bazel.eclipse.core.util.trace.Trace.setCurrentTrace;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CountDownLatch;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;

import com.salesforce.bazel.eclipse.core.model.cache.BazelElementInfoCache;
import com.salesforce.bazel.eclipse.core.util.trace.Trace;

/**
 * A special job for opening a {@link BazelElement} preventing concurrent load attempts.
 * <p>
 * The job can only be used once! It is intended to be used from a single thread only.
 * </p>
 */
class BazelElementOpenJob<I extends BazelElementInfo> extends Job implements ISchedulingRule {

    final IPath location;
    final BazelElement<I, ?> bazelElement;
    final CountDownLatch opened = new CountDownLatch(1);
    private volatile I info;
    private volatile CoreException openException;
    private volatile OperationCanceledException cancelException;
    private final BazelElementInfoCache infoCache;
    private final Trace trace;

    BazelElementOpenJob(IPath location, BazelElement<I, ?> bazelElement, BazelElementInfoCache infoCache) {
        super(format("Opening '%s'...", location.toOSString()));
        this.location = location;
        this.bazelElement = bazelElement;
        this.infoCache = infoCache;

        // use the trace of the calling thread
        trace = Trace.getCurrentTrace();

        setSystem(true);
        setUser(false);
        setRule(this);
        setPriority(SHORT);
    }

    @Override
    public boolean contains(ISchedulingRule rule) {
        if (rule == this) {
            return true;
        }

        if (rule instanceof BazelElementOpenJob otherJob) {
            var otherLocation = otherJob.location;
            return location.isPrefixOf(otherLocation);
        }

        return false;
    }

    private String getOpenErrorStatusMessage(Throwable e) {
        return format("Opening element at '%s' failed with: '%s'", location, e.getMessage());
    }

    @Override
    public boolean isConflicting(ISchedulingRule rule) {
        if (rule == this) {
            return true;
        }

        if (rule instanceof BazelElementOpenJob otherJob) {
            var otherLocation = otherJob.location;
            return location.isPrefixOf(otherLocation) || otherLocation.isPrefixOf(location);
        }

        return false;
    }

    public I open() throws InterruptedException, CoreException {
        if (opened.getCount() != 1) {
            throw new IllegalStateException(
                    "Attempt to call the same open job twice. This is a programming error! Change the code.");
        }

        schedule();
        opened.await();

        if (cancelException != null) {
            throw new OperationCanceledException("Opening canceled");
        }
        if (openException != null) {
            throw new CoreException(Status.error(getOpenErrorStatusMessage(openException), openException));
        }

        return requireNonNull(info, "Programming error: the info is expected to be set at this point. Check the code!");
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        // we only set the active trace but do not create a span
        // a span will only be created when a command is executed
        var oldTrace = setCurrentTrace(trace);
        try {
            // check check within scheduling rule to ensure we don't load twice
            info = infoCache.getIfPresent(bazelElement);
            if (info != null) {
                return Status.OK_STATUS;
            }

            // load
            info = requireNonNull(
                bazelElement.createInfo(),
                () -> format(
                    "invalid implementation of #createInfo in %s; must not return null!",
                    bazelElement.getClass()));

            // store in cache
            infoCache.putOrGetCached(bazelElement, info);
        } catch (OperationCanceledException e) {
            cancelException = e;
            return Status.CANCEL_STATUS;
        } catch (CoreException e) {
            openException = e;
            return Status.error(getOpenErrorStatusMessage(e), e);
        } finally {
            opened.countDown();

            // restore old trace
            setCurrentTrace(oldTrace);
        }

        return Status.OK_STATUS;
    }
}
