/*
 * Copyright (C) 2017 pholthau
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
package de.citec.csra.task.srv;

import de.citec.csra.task.cli.TaskListener;
import de.citec.csra.task.TaskProxy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import rsb.RSBException;
import rst.communicationpatterns.TaskStateType.TaskState;
import static rst.communicationpatterns.TaskStateType.TaskState.State.ACCEPTED;
import static rst.communicationpatterns.TaskStateType.TaskState.State.COMPLETED;
import static rst.communicationpatterns.TaskStateType.TaskState.State.FAILED;
import static rst.communicationpatterns.TaskStateType.TaskState.State.REJECTED;

/**
 *
 * @author pholthau
 */
public class TaskExecutionMonitor implements Callable<Void>, TaskListener {

	private final static Logger LOG = Logger.getLogger(TaskExecutionMonitor.class.getName());
	private final TaskProxy proxy;
	private LocalTask executor;
	private final LocalTaskFactory factory;
	private final ExecutorService service = Executors.newSingleThreadExecutor();

	public TaskExecutionMonitor(TaskProxy proxy, LocalTaskFactory factory) {
		this.proxy = proxy;
		this.factory = factory;
	}

	@Override
	public Void call() {
		
		try {
			proxy.activate();
		} catch (RSBException | InterruptedException ex) {
			LOG.log(Level.SEVERE, "Unable to activate task proxy, refusing execution.", ex);
			return null;
		}

		try {
			this.executor = factory.newLocalTask(proxy.getPayload());
		} catch (IllegalArgumentException ex) {
			LOG.log(Level.SEVERE, "Unable to generate task executor, rejecting task.", ex);
			proxy.update(REJECTED, ex.getMessage());
			proxy.deactivate();
			return null;
		}
		
		if(this.executor == null){
			LOG.log(Level.SEVERE, "Unable to generate task executor, rejecting task.");
			proxy.update(REJECTED);
			proxy.deactivate();
			return null;
		}
		
		proxy.update(ACCEPTED);
		
		try {

			Future future = service.submit(this.executor);
			Object result = future.get();

//			update also implies deactivation
			proxy.update(COMPLETED, result);
		} catch (ExecutionException ex) {
			proxy.update(FAILED, ex.getCause().getMessage());
		} catch (InterruptedException ex) {
			proxy.update(FAILED, ex.getMessage());
			Thread.currentThread().interrupt();
		}
		return null;
	}

	@Override
	public void updated(TaskState state) {
		switch (state.getState()) {
			case ABORT:
			case ABORTED:
			case FAILED:
			case UPDATE_REJECTED:
				try {
					executor.abort(proxy.getPayload());
				} catch (Exception ex) {
					LOG.log(Level.WARNING, "Task could not be aborted at initiator", ex);
				}
				service.shutdownNow();
				break;
			default:
				break;
		}
	}
}
