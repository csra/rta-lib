/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.citec.csra.task.srv;

import de.citec.csra.allocation.cli.ExecutableResource;
import static de.citec.csra.rst.util.IntervalUtils.currentTimeInMicros;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import rsb.RSBException;

/**
 *
 * @author pholthau
 */
public class ExecutableResourceTask implements LocalTask {

	private final static Logger LOG = Logger.getLogger(ExecutableResourceTask.class.getName());
	private final Set<ExecutableResource> actions;
	private final long TIMEOUT_US = 500000;

	public ExecutableResourceTask(Set<ExecutableResource> actions) throws InterruptedException, IllegalArgumentException, RuntimeException {
		this.actions = actions;
		if (this.actions.isEmpty()) {
			throw new IllegalArgumentException("No actions found.");
		}
	}

	public ExecutableResourceTask(Set<ExecutableResource> actions, boolean scheduleImmediately) throws InterruptedException, IllegalArgumentException, RuntimeException {
		this(actions);
		if (scheduleImmediately) {
			schedule(this.actions);
		}
	}

	@Override
	public void abort(Object description) throws Exception {
		for (ExecutableResource er : this.actions) {
			er.shutdown();
		}
	}

	@Override
	public Object call() throws Exception {
		Map<Future, ExecutableResource> fs = new HashMap<>();
		for (ExecutableResource er : this.actions) {
			LOG.log(Level.INFO, "Queuing action ''{0}''", er);
			er.startup();
			fs.put(er.getFuture(), er);
		}
		for (Future f : fs.keySet()) {
			f.get();
		}
		return null;
	}

	private void schedule(Set<ExecutableResource> actions) throws InterruptedException, IllegalArgumentException, RuntimeException {
		final Object monitor = new Object();
		Set<ExecutableResource> pending = new HashSet<>(actions);
		for (ExecutableResource r : actions) {
			r.getRemote().addSchedulerListener((a) -> {
				synchronized (monitor) {
					switch (a.getState()) {
						case REJECTED:
							throw new IllegalArgumentException("Resource unavailable");
						case SCHEDULED:
							pending.remove(r);
							monitor.notifyAll();
							break;
					}
				}
			});
			try {
				r.getRemote().schedule();
			} catch (RSBException ex) {
				throw new RuntimeException(ex);
			}
		}

		long start = currentTimeInMicros();
		long remaining = TIMEOUT_US;
		while (remaining > 0) {
			synchronized (monitor) {
				if (pending.isEmpty()) {
					return;
				} else {
					remaining = TIMEOUT_US - (currentTimeInMicros() - start);
					monitor.wait(remaining / 1000, (int) ((remaining % 1000) * 1000));
				}
			}
		}
		throw new IllegalArgumentException("Allocation service unreachable in given time.");
	}
}
