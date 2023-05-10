/*-
 * #%L
 * A web server for BigDataViewer datasets.
 * %%
 * Copyright (C) 2014 - 2023 BigDataViewer developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package bdv.server;

public class Constants
{
	public static final String DATASETLIST_CONTEXT_NAME = "json";

	public static final String MANAGER_CONTEXT_NAME = "manager";

	public static final String[] RESERVED_CONTEXT_NAMES = new String[]
	{
			DATASETLIST_CONTEXT_NAME,
			MANAGER_CONTEXT_NAME
	};

	public static final int THUMBNAIL_WIDTH = 100;

	public static final int THUMBNAIL_HEIGHT = 100;

	public static final boolean ENABLE_EXPERIMENTAL_FEATURES = false;
}
