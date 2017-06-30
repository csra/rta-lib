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

import java.util.logging.Level;
import java.util.logging.Logger;
import rsb.Factory;
import rsb.Handler;
import rsb.Informer;
import rsb.Listener;
import rsb.RSBException;
import rsb.converter.DefaultConverterRepository;
import rsb.converter.ProtocolBufferConverter;
import rsb.filter.OriginFilter;
import rst.communicationpatterns.ResourceAllocationType;
import rst.communicationpatterns.ResourceAllocationType.ResourceAllocation;

/**
 *
 * @author Patrick Holthaus
 * (<a href=mailto:patrick.holthaus@uni-bielefeld.de>patrick.holthaus@uni-bielefeld.de</a>)
 */
public class RemoteAllocationService {

	private final static String SCOPEVAR = "SCOPE_ALLOCATION";
	private final static String FALLBACK = "/coordination/allocation/";
	private static String scope;

	static {
		DefaultConverterRepository.getDefaultConverterRepository()
				.addConverter(new ProtocolBufferConverter<>(ResourceAllocationType.ResourceAllocation.getDefaultInstance()));
	}

	public static String getScope() {
		if (scope == null) {
			if (System.getenv().containsKey(SCOPEVAR)) {
				scope = System.getenv(SCOPEVAR);
			} else {
				LOG.log(Level.WARNING, "using fallback scope ''{0}'', consider exporting ${1}", new String[]{FALLBACK, SCOPEVAR});
				scope = FALLBACK;
			}
		}
		return scope;
	}

	private static RemoteAllocationService instance;
	private final static Logger LOG = Logger.getLogger(RemoteAllocationService.class.getName());

	private final Informer informer;
	private final Listener listener;

	public static RemoteAllocationService getInstance() throws RSBException {
		if (instance == null) {
			instance = new RemoteAllocationService();
		}
		return instance;
	}

	private RemoteAllocationService() throws RSBException {
		this.informer = Factory.getInstance().createInformer(getScope());
		this.listener = Factory.getInstance().createListener(getScope());
		this.listener.addFilter(new OriginFilter(this.informer.getId(), true));
		this.listener.activate();
		this.informer.activate();
	}

	public void update(ResourceAllocation allocation) throws RSBException {
		synchronized (this.informer) {
			this.informer.publish(allocation);
		}
	}

	public void addHandler(Handler handler, boolean wait) throws InterruptedException, RSBException {
		this.listener.addHandler(handler, wait);
	}

	public void removeHandler(Handler handler, boolean wait) throws InterruptedException, RSBException {
		this.listener.removeHandler(handler, wait);
	}

	public void shutdown() throws RSBException, InterruptedException {
		for (int i = 0; i < 100 && !this.listener.getHandlers().isEmpty(); i++) {
			Thread.sleep(10);
		}
		if (!this.listener.getHandlers().isEmpty()) {
			LOG.log(Level.WARNING, "Shutting down although there may still be active listener threads");
		}
		shutdownNow();
	}

	public void shutdownNow() throws RSBException, InterruptedException {
		this.informer.deactivate();
		this.listener.deactivate();
		instance = null;
	}

}
