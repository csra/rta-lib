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

import static de.citec.csra.rst.util.IntervalUtils.buildRst;
import static de.citec.csra.rst.util.IntervalUtils.currentTimeInMicros;
import static de.citec.csra.rst.util.StringRepresentation.shortString;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import java.util.logging.Level;
import java.util.logging.Logger;
import rsb.RSBException;
import rsb.util.QueueAdapter;
import rst.communicationpatterns.ResourceAllocationType.ResourceAllocation;
import rst.communicationpatterns.ResourceAllocationType.ResourceAllocation.State;
import static rst.communicationpatterns.ResourceAllocationType.ResourceAllocation.State.*;
import rst.timing.IntervalType.Interval;

/**
 *
 * @author Patrick Holthaus
 * (<a href=mailto:patrick.holthaus@uni-bielefeld.de>patrick.holthaus@uni-bielefeld.de</a>)
 */
public class RemoteAllocation implements Schedulable, Adjustable, TimeAdjustable, SchedulerListener, TimedState {

	private final static Logger LOG = Logger.getLogger(RemoteAllocation.class.getName());

	private final static long TIMEOUT_US = 1000000;
	private final QueueAdapter qa;
	private final BlockingQueue<ResourceAllocation> queue;
	private final HashSet<SchedulerListener> listeners;
	private final Object monitor = new Object();

	private ResourceAllocation allocation;
	private RemoteAllocationService remoteService;
	private boolean inc;

	public RemoteAllocation(ResourceAllocation allocation) {
		this(ResourceAllocation.newBuilder(allocation));
	}

	public RemoteAllocation(ResourceAllocation.Builder builder) {
		if (!builder.hasId()) {
			builder.setId(UUID.randomUUID().toString());
		}
		if (builder.hasState()) {
			LOG.log(Level.WARNING, "Invalid initial state ''{0}'', altering to ''{1}''.", new Object[]{builder.getState(), REQUESTED});
		}
		builder.setState(REQUESTED);
		this.allocation = builder.build();
		this.listeners = new HashSet<>();
		this.qa = new QueueAdapter();
		this.queue = qa.getQueue();
	}

	public boolean hasToken() {
		return allocation.getId().split("#").length == 2;
	}

	public void generateToken() {
		setToken(UUID.randomUUID().toString());
	}

	public String getToken() {
		if (!hasToken()) {
			generateToken();
		}
		return allocation.getId().split("#")[1];
	}

	public void setToken(String token) {
		String newId;
		if (hasToken()) {
			newId = allocation.getId().replaceAll(getToken(), token);
		} else {
			newId = allocation.getId() + "#" + token;
		}
		allocation = ResourceAllocation.newBuilder(allocation).setId(newId).build();
	}

	public void addSchedulerListener(SchedulerListener l) {
		synchronized (this.listeners) {
			this.listeners.add(l);
		}
	}

	public void removeSchedulerListener(SchedulerListener l) {
		synchronized (this.listeners) {
			this.listeners.remove(l);
		}
	}

	public void removeAllSchedulerListeners() {
		synchronized (this.listeners) {
			this.listeners.clear();
		}
	}

	public synchronized boolean isAlive() {
		switch (this.allocation.getState()) {
			case REJECTED:
			case CANCELLED:
			case ABORTED:
			case RELEASED:
				return false;
			case ALLOCATED:
			case REQUESTED:
			case SCHEDULED:
			default:
				return true;
		}
	}

	@Override
	public State getCurrentState() {
		return this.allocation.getState();
	}

	@Override
	public long getRemainingTime() {
		switch (this.allocation.getState()) {
			case REQUESTED:
			case SCHEDULED:
			case ALLOCATED:
				return Math.max(0, this.allocation.getSlot().getEnd().getTime() - currentTimeInMicros());
			case ABORTED:
			case CANCELLED:
			case REJECTED:
			case RELEASED:
			default:
				return 0;
		}
	}

	@Override
	public void schedule() throws RSBException {
		LOG.log(Level.FINE,
				"resource allocation scheduled by client: ''{0}''",
				allocation.toString().replaceAll("\n", " "));

		synchronized (this.monitor) {
			this.inc = false;
		}

		new Thread(() -> {
			LOG.log(Level.FINER, "starting allocation-dispatcher#{0}", allocation.getId());
			while (isAlive()) {
				try {
					ResourceAllocation update = queue.poll(TIMEOUT_US, TimeUnit.MICROSECONDS);
					if (update != null && update.getId().equals(allocation.getId())) {
						allocationUpdated(update);
					}
				} catch (InterruptedException ex) {
					LOG.log(Level.SEVERE, "Event dispatching interrupted", ex);
					Thread.currentThread().interrupt();
					return;
				}
			}
		}, "allocation-dispatcher#" + allocation.getId()).start();

		new Thread(() -> {
			LOG.log(Level.FINER, "allocation-request-timeout#{0}", this.allocation.getId());
			try {
				synchronized (this.monitor) {
					this.monitor.wait(TIMEOUT_US / 1000, (int) ((TIMEOUT_US % 1000) * 1000));
					if (!this.inc) {
						State newState = CANCELLED;
						ResourceAllocation shutdown = ResourceAllocation.newBuilder(this.allocation).setState(newState).build();
						LOG.log(Level.WARNING,
								"client allocation request timed out after {0}µs, shutting down ''{1}'' -> ''{2}'' ({3})",
								new Object[]{
									TIMEOUT_US,
									allocation.getState(),
									newState,
									shutdown.toString().replaceAll("\n", " ")});
						allocationUpdated(shutdown);
					}
				}
			} catch (InterruptedException ex) {
				LOG.log(Level.SEVERE, "Request timeout interrupted", ex);
				Thread.currentThread().interrupt();
			}
		}, "allocation-request-timeout#" + this.allocation.getId()).start();

		try {
			LOG.log(Level.FINE, "start listening to server updates");
			synchronized (this.monitor) {
				this.remoteService = RemoteAllocationService.getInstance();
				this.remoteService.addHandler(this.qa, true);
				this.remoteService.update(this.allocation);
			}
		} catch (InterruptedException ex) {
			LOG.log(Level.SEVERE, "Could not add handler, skipping remote update", ex);
		}
	}

	@Override
	public void abort() throws RSBException {
		requestState(ABORTED);
	}

	@Override
	public void release() throws RSBException {
		requestState(RELEASED);
	}

	@Override
	public void cancel() throws RSBException {
		requestState(CANCELLED);
	}

	private void requestSlot(Interval interval) throws RSBException {
		if (isAlive()) {
			ResourceAllocation request = ResourceAllocation.newBuilder(this.allocation).setSlot(interval).build();
			if (this.remoteService == null) {
				this.allocation = request;
			} else {
				synchronized (this.monitor) {
					this.inc = false;
				}
				new Thread(() -> {
					try {
						synchronized (this.monitor) {
							this.monitor.wait(TIMEOUT_US / 1000, (int) ((TIMEOUT_US % 1000) * 1000));
							if (isAlive() && !this.inc) {
								State newState;
								switch (this.allocation.getState()) {
									case REQUESTED:
										newState = State.CANCELLED;
										break;
									case SCHEDULED:
										newState = State.CANCELLED;
										break;
									case ALLOCATED:
									default:
										newState = State.ABORTED;
										break;
								}
								ResourceAllocation shutdown = ResourceAllocation.newBuilder(this.allocation).setState(newState).build();
								LOG.log(Level.WARNING,
										"client slot state change timed out after {0}ms, shutting down ''{1}'' -> ''{2}'' ({3})",
										new Object[]{
											TIMEOUT_US,
											allocation.getState(),
											newState,
											shortString(shutdown)});
								allocationUpdated(shutdown);
							}
						}
					} catch (InterruptedException ex) {
						LOG.log(Level.SEVERE, "Event dispatching interrupted", ex);
						Thread.currentThread().interrupt();
					}
				}, "allocation-slot-timeout#" + this.allocation.getId()).start();
				LOG.log(Level.FINE,
						"attempting client allocation slot change ''{0}'' -> ''{1}'' ({2})",
						new Object[]{
							shortString(allocation.getSlot()),
							shortString(interval),
							shortString(request)});
				this.remoteService.update(request);
			}
		} else {
			LOG.log(Level.FINE,
					"resource allocation not active anymore ({0}), skipping client allocation slot change ({1}) for: ''{2}''",
					new Object[]{allocation.getState(), shortString(interval), shortString(allocation)});
		}
	}

	private void requestState(State newState) throws RSBException {
		if (isAlive()) {
			ResourceAllocation request = ResourceAllocation.newBuilder(this.allocation).setState(newState).build();
			switch (newState) {
				case ABORTED:
				case CANCELLED:
				case RELEASED:
					synchronized (this.monitor) {
						this.inc = false;
					}
					new Thread(() -> {
						try {
							synchronized (this.monitor) {
								this.monitor.wait(TIMEOUT_US / 1000, (int) ((TIMEOUT_US % 1000) * 1000));
								if (!this.inc) {
									LOG.log(Level.WARNING,
											"client allocation state change timed out after {0}ms, forcing client update ''{1}'' -> ''{2}'' ({3})",
											new Object[]{
												TIMEOUT_US,
												allocation.getState(),
												newState,
												request.toString().replaceAll("\n", " ")});
									allocationUpdated(request);
								}
							}
						} catch (InterruptedException ex) {
							LOG.log(Level.SEVERE, "Event dispatching interrupted", ex);
							Thread.currentThread().interrupt();
						}
					}, "allocation-state-timeout#" + this.allocation.getId()).start();
					LOG.log(Level.FINE,
							"attempting client allocation state change ''{0}'' -> ''{1}'' ({2})",
							new Object[]{
								allocation.getState(),
								newState,
								shortString(request)});
					this.remoteService.update(request);
					break;
				case REJECTED:
				case ALLOCATED:
				case SCHEDULED:
				case REQUESTED:
					LOG.log(Level.WARNING,
							"Illegal state ({0}) , skipping remote update",
							newState);
					break;
			}
		} else {
			LOG.log(Level.FINE,
					"resource allocation not active anymore ({0}), skipping client allocation state change ({1}) for: ''{2}''",
					new Object[]{allocation.getState(), newState, shortString(allocation)});
		}
	}

	@Override
	public final void allocationUpdated(ResourceAllocation update) {
		LOG.log(Level.FINE,
				"resource allocation updated by server ''{0}'' -> ''{1}'' ({2})",
				new Object[]{
					this.allocation.getState(),
					update.getState(),
					shortString(update)});

		synchronized (this.monitor) {
			this.allocation = update;
			this.inc = true;
			this.monitor.notifyAll();
		}

		synchronized (this.listeners) {
			((HashSet<SchedulerListener>) listeners.clone()).forEach((l) -> {
				l.allocationUpdated(allocation);
			});
		}

		if (!isAlive()) {
			try {
				LOG.log(Level.FINE, "stop listening to server updates");
				this.remoteService.removeHandler(this.qa, true);
			} catch (InterruptedException | RSBException ex) {
				LOG.log(Level.SEVERE, "Could not remove handler", ex);
			}
		}
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + shortString(allocation) + "]";
	}

	@Override
	public void shift(long amount, TimeUnit unit) throws RSBException {
		amount = MICROSECONDS.convert(amount, unit);
		long newBegin = this.allocation.getSlot().getBegin().getTime() + amount;
		long newEnd = this.allocation.getSlot().getEnd().getTime() + amount;
		requestSlot(buildRst(newBegin, newEnd, MICROSECONDS));
	}

	@Override
	public void shiftTo(long timestamp, TimeUnit unit) throws RSBException {
		long newBegin = MICROSECONDS.convert(timestamp, unit);
		long newEnd = newBegin + this.allocation.getSlot().getEnd().getTime() - this.allocation.getSlot().getBegin().getTime();
		requestSlot(buildRst(newBegin, newEnd, MICROSECONDS));
	}

	@Override
	public void extend(long amount, TimeUnit unit) throws RSBException {
		long newBegin = this.allocation.getSlot().getBegin().getTime();
		long newEnd = this.allocation.getSlot().getEnd().getTime() + MICROSECONDS.convert(amount, unit);
		requestSlot(buildRst(newBegin, newEnd, MICROSECONDS));
	}

	@Override
	public void extendTo(long timestamp, TimeUnit unit) throws RSBException {
		long newBegin = this.allocation.getSlot().getBegin().getTime();
		long newEnd = MICROSECONDS.convert(timestamp, unit);
		requestSlot(buildRst(newBegin, newEnd, MICROSECONDS));
	}

	@Override
	@Deprecated
	public void shift(long amount) throws RSBException {
		shift(amount, MILLISECONDS);
	}

	@Override
	@Deprecated
	public void shiftTo(long timestamp) throws RSBException {
		shiftTo(timestamp, MILLISECONDS);
	}

	@Override
	@Deprecated
	public void extend(long amount) throws RSBException {
		extend(amount, MILLISECONDS);
	}

	@Override
	@Deprecated
	public void extendTo(long timestamp) throws RSBException {
		extendTo(timestamp, MILLISECONDS);
	}
}
