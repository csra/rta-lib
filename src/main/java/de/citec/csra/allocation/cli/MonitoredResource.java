/*
 * Copyright (C) 2017 Patrick Holthaus
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

import static de.citec.csra.rst.util.IntervalUtils.currentTimeInMicros;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import rsb.RSBException;
import rsb.util.QueueAdapter;
import rst.communicationpatterns.ResourceAllocationType.ResourceAllocation;
import rst.communicationpatterns.ResourceAllocationType.ResourceAllocation.State;

/**
 *
 * @author Patrick Holthaus
 */
public class MonitoredResource implements SchedulerListener, Executable {

	private final static Logger LOG = Logger.getLogger(MonitoredResource.class.getName());

	private final BlockingQueue<ResourceAllocation> incoming;
	private final QueueAdapter qa;
	private final LinkedBlockingDeque<State> queue = new LinkedBlockingDeque<>();
	private final Object monitor = new Object();
	private final String[] resources;
	private boolean alive;

	public MonitoredResource(String... resources) {
		this.qa = new QueueAdapter();
		this.incoming = qa.getQueue();
		this.resources = resources;
	}

	@Override
	public void startup() throws RSBException {
		String printStr = Arrays.toString(resources);
		LOG.log(Level.FINE, "activating resource listener for: ''{0}''", printStr);
		try {
			RemoteAllocationService.getInstance().addHandler(qa, true);
			alive = true;
		} catch (InterruptedException ex) {
			LOG.log(Level.SEVERE, "Interrupted during handler addition, shutting down", ex);
			return;
		}

		new Thread(() -> {
			while (alive) {
				try {
					ResourceAllocation update = incoming.poll(2000, TimeUnit.MILLISECONDS);
					if (update != null) {
						boolean match = false;
						search:
						for (String r : resources) {
							for (String in : update.getResourceIdsList()) {
								if (r.equals(in)) {
									match = true;
									break search;
								}
							}
						}
						if (match) {
							allocationUpdated(update);
						}
					}
				} catch (InterruptedException ex) {
					LOG.log(Level.SEVERE, "Event dispatching interrupted", ex);
					Thread.currentThread().interrupt();
					return;
				}
			}
		}, "resource-listener@" + printStr).start();
	}

	@Override
	public void shutdown() throws RSBException {
		alive = false;
		try {
			RemoteAllocationService.getInstance().removeHandler(qa, true);
		} catch (InterruptedException ex) {
			LOG.log(Level.SEVERE, "Interrupted during handler removal, ignoring.", ex);
		}
	}

	@Override
	public void allocationUpdated(ResourceAllocation allocation) {
		synchronized (this.monitor) {
			this.queue.add(allocation.getState());
			this.monitor.notifyAll();
		}
	}

	public State getState() {
		return this.queue.peekLast();
	}

	private boolean containsAny(State... states) {
		for (State state : states) {
			if (this.queue.contains(state)) {
				return true;
			}
		}
		return false;
	}
	
	@Deprecated
	public void await(State state, long timeout) throws InterruptedException, TimeoutException {
		await(timeout, MILLISECONDS, state);
	}
	

	public void await(State... state) throws InterruptedException {
		synchronized (this.monitor) {
			while (!containsAny(state)) {
				this.monitor.wait();
			}
		}
	}

	public void await(long timeout, TimeUnit unit, State... states) throws InterruptedException, TimeoutException {
		timeout = MICROSECONDS.convert(timeout, unit);
		synchronized (this.monitor) {
			if (containsAny(states)) {
				return;
			}
			long start = currentTimeInMicros();
			long remaining = timeout;
			while (remaining > 0) {
				this.monitor.wait(remaining / 1000, (int) ((remaining % 1000) * 1000));
				if (containsAny(states)) {
					return;
				} else {
					remaining = timeout - (currentTimeInMicros() - start);
				}
			}
			throw new TimeoutException("Waiting for state '" + Arrays.toString(states) + "' timed out after " + timeout + "µs.");
		}
	}

}
