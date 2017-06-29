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

import java.util.concurrent.TimeUnit;
import rsb.RSBException;

/**
 *
 * @author Patrick Holthaus
 */
public interface TimeAdjustable {
	
	public void shift(long amount, TimeUnit unit) throws RSBException;
	
	public void shiftTo(long timestamp, TimeUnit unit) throws RSBException;
	
	public void extend(long amount, TimeUnit unit) throws RSBException;
	
	public void extendTo(long timestamp, TimeUnit unit) throws RSBException;
	
}
