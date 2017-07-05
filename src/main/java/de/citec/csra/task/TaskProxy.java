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
package de.citec.csra.task;

import com.google.protobuf.ByteString;
import de.citec.csra.rst.util.SerializationService;
import static de.citec.csra.rst.util.SerializationService.EMPTY;
import static de.citec.csra.rst.util.SerializationService.UTF8;
import static de.citec.csra.rst.util.StringRepresentation.shortString;
import de.citec.csra.task.cli.TaskListener;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import rsb.Event;
import rsb.EventId;
import rsb.Factory;
import rsb.FilteringHandler;
import rsb.Informer;
import rsb.InitializeException;
import rsb.Listener;
import rsb.RSBException;
import rsb.Scope;
import rsb.converter.DefaultConverterRepository;
import rsb.converter.ProtocolBufferConverter;
import rsb.filter.OriginFilter;
import rsb.util.QueueAdapter;
import rst.communicationpatterns.TaskStateType.TaskState;
import static rst.communicationpatterns.TaskStateType.TaskState.Origin.HANDLER;
import static rst.communicationpatterns.TaskStateType.TaskState.Origin.SUBMITTER;
import rst.communicationpatterns.TaskStateType.TaskState.State;

/**
 *
 * @author Patrick Holthaus
 * (<a href=mailto:patrick.holthaus@uni-bielefeld.de>patrick.holthaus@uni-bielefeld.de</a>)
 */
public class TaskProxy {

	static {
		DefaultConverterRepository.getDefaultConverterRepository().addConverter(new ProtocolBufferConverter<>(TaskState.getDefaultInstance()));
	}

	private final static Logger LOG = Logger.getLogger(TaskProxy.class.getName());
	private final Informer informer;
	private final Listener listener;
	private BlockingQueue<TaskState> queue;
	private boolean active;
	private final TaskState.Builder task;
	private final Set<TaskListener> listeners = new HashSet<>();
	private EventId causeId;
	private final Scope scope;
	private SerializationService sservice;
	private final boolean foreignInformer;

	public TaskProxy(String scope) throws InitializeException {
		this.causeId = null;
		this.scope = new Scope(scope);
		this.informer = Factory.getInstance().createInformer(scope);
		this.listener = Factory.getInstance().createListener(scope);
		this.foreignInformer = false;
		this.task = TaskState.newBuilder().setOrigin(SUBMITTER).setSerial(-1);
	}

	public TaskProxy(TaskState original, Event cause) throws InitializeException {
		this.causeId = cause.getId();
		this.scope = cause.getScope();
		this.informer = Factory.getInstance().createInformer(cause.getScope());
		this.listener = Factory.getInstance().createListener(cause.getScope());
		this.foreignInformer = false;
		this.task = TaskState.newBuilder(original).setOrigin(HANDLER);
	}

	public TaskProxy(TaskState original, Event cause, Informer informer) throws InitializeException {
		this.causeId = cause.getId();
		this.informer = informer;
		this.scope = cause.getScope();
		this.listener = Factory.getInstance().createListener(cause.getScope());
		this.foreignInformer = true;
		this.task = TaskState.newBuilder(original).setOrigin(HANDLER);
	}

	public void activate() throws RSBException, InterruptedException {
		activate(false);
	}

	public void activate(boolean compat) throws RSBException, InterruptedException {
		synchronized (this.listener) {
			QueueAdapter<TaskState> qa = new QueueAdapter<>();
			this.queue = qa.getQueue();
			if (compat) {
				this.listener.addHandler(qa, true);
			} else {
				this.listener.addHandler(new FilteringHandler(qa, (e) -> {
					boolean related;
					synchronized (this.listener) {
						related = e.getCauses().contains(this.causeId);
					}
					LOG.log(Level.FINE, "SKIPPED task update at ''{0}'' with ''{1}'' (unrelated)", new String[]{this.scope.toString(), shortString(e.getData())});
					return related;
				}), true);
			}
			this.listener.addFilter(new OriginFilter(informer.getId(), true));
			if (!this.informer.isActive()) {
				this.informer.activate();
			}

			this.listener.activate();
			this.active = true;
			LOG.log(Level.FINE, "Activated listener/informer pair at ''{0}''", listener.getScope());

			new Thread(() -> {
				while (this.active) {
					try {
						TaskState update = this.queue.poll(2000, TimeUnit.MILLISECONDS);
						if (update != null) {
							LOG.log(Level.INFO, "RECEIVED task update at ''{0}'' with ''{1}''", new String[]{this.scope.toString(), shortString(update)});
							this.task.mergeFrom(update);
							this.listeners.forEach((ts) -> {
								ts.updated(update);
							});
							checkState();
						}
					} catch (InterruptedException e) {
						LOG.log(Level.FINE, "Event dispatching interrupted", e);
						Thread.currentThread().interrupt();
					}
				}
			}, "task-dispatcher@" + scope).start();
		}
	}

	public void deactivate() {
		synchronized (this.listener) {
			this.listeners.clear();
			try {
				if (this.listener.isActive()) {
					this.listener.deactivate();
				}
				if (!this.foreignInformer && this.informer.isActive()) {
					this.informer.deactivate();
				}
				LOG.log(Level.INFO, "Deactivated listener/informer pair at ''{0}''", listener.getScope());
			} catch (RSBException ex) {
				LOG.log(Level.WARNING, "Could not deactivate listener/informer pair at '" + listener.getScope() + "'", ex);
			} catch (InterruptedException ex) {
				LOG.log(Level.WARNING, "Could not deactivate listener/informer pair at '" + listener.getScope() + "'", ex);
				Thread.currentThread().interrupt();
			}
			this.active = false;
		}
	}

	public void addTaskListener(TaskListener l) {
		this.listeners.add(l);
	}

	public void removeTaskListener(TaskListener l) {
		this.listeners.remove(l);
	}

	public void removeAllTaskListeners() {
		this.listeners.clear();
	}

	public State getState() {
		return this.task.getState();
	}

	private void checkState() {
		switch (this.task.getState()) {
			case ABORTED:
			case FAILED:
			case REJECTED:
			case COMPLETED:
				deactivate();
			default:
				break;
		}
	}

	private synchronized void publish() {
		try {
			TaskState toSend = this.task.setSerial(this.task.getSerial() + 1).build();
			LOG.log(Level.INFO, "SENDING task update to ''{0}'' with ''{1}''", new String[]{this.scope.toString(), shortString(toSend)});
			Event e = new Event(this.scope, TaskState.class, toSend);
			if (this.causeId != null) {
				e.addCause(this.causeId);
			}
			synchronized (this.listener) {
				this.informer.publish(e);
				if (this.causeId == null) {
					this.causeId = e.getId();
				}
			}
		} catch (RSBException ex) {
			LOG.log(Level.SEVERE, "Could not publish new task state, discarding.", ex);
		}
	}

	public void update(Object payload) {
		setPayload(payload);
		publish();
	}

	public void update(State state) {
		setState(state);
		publish();
	}

	public void update(State state, Object payload) {
		setState(state);
		setPayload(payload);
		publish();
	}

	public void udpate(TaskState task) {
		this.task.mergeFrom(task);
		publish();
	}

	private void setState(State state) {
		this.task.setState(state);
		checkState();
	}

	private void setPayload(Object payload) {
		ByteString pl;
		ByteString ws;
		if (payload == null) {
			pl = EMPTY;
			ws = UTF8;
		} else {
			try {
				if (sservice == null || !payload.getClass().equals(sservice.getClass())) {
					sservice = new SerializationService<>(payload.getClass());
				}
				pl = sservice.serialize(payload);
				ws = sservice.getSchema();
			} catch (InitializeException ex) {
				LOG.log(Level.WARNING, "Could not initialize serialization service for type '"+ payload.getClass() + "', falling back to empty payload.", ex);
				pl = EMPTY;
				ws = UTF8;
			}
		}
		this.task.setPayload(pl).setWireSchema(ws);
	}

	public Object getPayload() {
		if (sservice == null || !this.task.getPayload().getClass().equals(sservice.getClass())) {
			sservice = new SerializationService(this.task.getWireSchema().toStringUtf8());
		}
		return sservice.deserialize(this.task.getPayload());
	}
}
