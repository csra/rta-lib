/* 
 * Copyright (C) 2016 Bielefeld University, Patrick Holthaus
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.citec.csra.allocation.cli;

import static de.citec.csra.allocation.cli.ExecutableResource.Completion.EXPIRE;
import de.citec.csra.rst.util.IntervalUtils;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import rsb.RSBException;
import rst.communicationpatterns.ResourceAllocationType.ResourceAllocation;
import rst.communicationpatterns.ResourceAllocationType.ResourceAllocation.*;
import static rst.communicationpatterns.ResourceAllocationType.ResourceAllocation.State.*;

/**
 *
 * @author Patrick Holthaus
 * (<a href=mailto:patrick.holthaus@uni-bielefeld.de>patrick.holthaus@uni-bielefeld.de</a>)
 */
public abstract class ExecutableResource<T> implements SchedulerListener, Executable, Callable<T> {

	public enum Completion {
		EXPIRE,
		RETAIN,
		MONITOR
	}

	private final static Logger LOG = Logger.getLogger(ExecutableResource.class.getName());
	private final ExecutorService executor;
	private boolean externalExecutor = true;
	private final Completion completion;
	private final RemoteAllocation remote;
	private Future<T> result;

	public ExecutableResource(ResourceAllocation allocation) {
		this(allocation, EXPIRE);
	}

	public ExecutableResource(ResourceAllocation allocation, Completion completion) {
		this(allocation, completion, Executors.newSingleThreadExecutor());
		this.externalExecutor = false;
	}

	public ExecutableResource(ResourceAllocation allocation, Completion completion, ExecutorService executor) {
		this.remote = new RemoteAllocation(ResourceAllocation.newBuilder(allocation));
		this.completion = completion;
		this.executor = executor;
	}
		
	public ExecutableResource(String description, Policy policy, Priority priority, Initiator initiator, long delay, long duration, TimeUnit unit, Completion completion, String... resources) {
		this(description, policy, priority, initiator, delay, duration, unit, completion, Executors.newSingleThreadExecutor(), resources);
		this.externalExecutor = false;
	}

	public ExecutableResource(String description, Policy policy, Priority priority, Initiator initiator, long delay, long duration, TimeUnit unit, Completion completion, ExecutorService executor, String... resources) {
		this.remote = new RemoteAllocation(ResourceAllocation.newBuilder().
				setInitiator(initiator).
				setPolicy(policy).
				setPriority(priority).
				setDescription(description).
				setSlot(IntervalUtils.buildRelativeRst(delay, duration, unit)).
				addAllResourceIds(Arrays.asList(resources)));
		this.completion = completion;
		this.executor = executor;
	}

	public ExecutableResource(String description, Policy policy, Priority priority, Initiator initiator, long delay, long duration, TimeUnit unit, String... resources) {
		this(description, policy, priority, initiator, delay, duration, unit, EXPIRE, resources);
	}
	
	@Deprecated
	public ExecutableResource(String description, Policy policy, Priority priority, Initiator initiator, long delay, long duration, Completion completion, String... resources) {
		this(description, policy, priority, initiator, delay, duration, completion, Executors.newSingleThreadExecutor(), resources);
		this.externalExecutor = false;
	}

	@Deprecated
	public ExecutableResource(String description, Policy policy, Priority priority, Initiator initiator, long delay, long duration, Completion completion, ExecutorService executor, String... resources) {
		this.remote = new RemoteAllocation(ResourceAllocation.newBuilder().
				setInitiator(initiator).
				setPolicy(policy).
				setPriority(priority).
				setDescription(description).
				setSlot(IntervalUtils.buildRelativeRst(delay, duration)).
				addAllResourceIds(Arrays.asList(resources)));
		this.completion = completion;
		this.executor = executor;
	}

	@Deprecated
	public ExecutableResource(String description, Policy policy, Priority priority, Initiator initiator, long delay, long duration, String... resources) {
		this(description, policy, priority, initiator, delay, duration, EXPIRE, resources);
	}

	private void terminateExecution(boolean interrupt) {
		if (result != null && !result.isDone()) {
			LOG.log(Level.FINE, "Cancelling user code execution {0}", interrupt ? "using an interrupt signal" : "");
			result.cancel(interrupt);
		}
		try {
			remote.removeSchedulerListener(this);
			if (!externalExecutor) {
				executor.shutdown();
				executor.awaitTermination(5000, TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException x) {
			LOG.log(Level.SEVERE, "Interrupted during executor shutdown", x);
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void startup() throws RSBException {
		this.result = executor.submit(this);
		this.remote.addSchedulerListener(this);
		this.remote.schedule();
	}

	@Override
	public void shutdown() throws RSBException {
		switch (this.remote.getCurrentState()) {
			case REQUESTED:
			case SCHEDULED:
				remote.cancel();
				break;
			case ALLOCATED:
				remote.abort();
				break;
			default:
				LOG.log(Level.WARNING, "Shutdown called in inactive state, interrupting execution.");
				terminateExecution(true);
				break;
		}
	}

	@Override
	public T call() throws ExecutionException, InterruptedException {
		synchronized (this) {
			try {
				awaitStart:
				while (!Thread.interrupted()) {
					this.wait();
					switch (this.remote.getCurrentState()) {
						case REQUESTED:
						case SCHEDULED:
							break;
						case ALLOCATED:
							break awaitStart;
						case ABORTED:
						case CANCELLED:
						case REJECTED:
						case RELEASED:
							return null;
					}
				}
			} catch (InterruptedException ex) {
				LOG.log(Level.FINE, "Startup interrupted in state " + this.remote.getCurrentState(), ex);
				throw ex;
			}
		}

		T res = null;
		try {
			LOG.log(Level.FINE, "Starting user code execution for {0} µs.", this.remote.getRemainingTime());
			res = execute();
			LOG.log(Level.FINE, "User code execution returned with ''{0}''", res);
			synchronized (this) {
				switch (completion) {
					case MONITOR:
						long time;
						while ((time = this.remote.getRemainingTime()) > 0 && !Thread.interrupted()) {
							LOG.log(Level.FINER, "Blocking resource for {0} µs.", time);
							this.wait(time / 1000, (int) ((time % 1000) * 1000));
						}
//					no break -> release resource after waiting
					case EXPIRE:
						try {
							LOG.log(Level.FINER, "Releasing resource now.");
							this.remote.release();
						} catch (RSBException ex) {
							LOG.log(Level.WARNING, "Could not release resources at server", ex);
						}
						break;
					case RETAIN:
						break;
				}
			}
		} catch (ExecutionException ex) {
			LOG.log(Level.FINER, "User code execution failed ({0}), aborting allocation at server", ex.getMessage());
			try {
				this.remote.abort();
			} catch (RSBException ex1) {
				LOG.log(Level.WARNING, "Could not abort resource allocation at server", ex1);
			}
			throw ex;
		} catch (InterruptedException ex) {
			LOG.log(Level.FINER, "User code interrupted, aborting allocation at server");
			try {
				this.remote.abort();
			} catch (RSBException ex1) {
				LOG.log(Level.WARNING, "Could not abort resource allocation at server", ex1);
			}
			throw ex;
		}
		return res;

	}

	public Future<T> getFuture() {
		return this.result;
	}

	public RemoteAllocation getRemote() {
		return this.remote;
	}

	@Override
	public void allocationUpdated(ResourceAllocation allocation) {
		synchronized (this) {
			this.notifyAll();
			switch (allocation.getState()) {
				case SCHEDULED:
				case ALLOCATED:
					break;
				case REJECTED:
				case CANCELLED:
					terminateExecution(false);
					break;
				case ABORTED:
				case RELEASED:
					terminateExecution(true);
					break;
			}
		}
	}

	public abstract T execute() throws ExecutionException, InterruptedException;

}
