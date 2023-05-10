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

import com.google.gson.stream.JsonWriter;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;

/**
 * Provides a list of available datasets on this {@link BigDataServer}
 *
 * @author HongKee Moon &lt;moon@mpi-cbg.de&gt;
 */
public class JsonDatasetListHandler extends ContextHandler
{
	private final Server server;

	public JsonDatasetListHandler( final Server server, final ContextHandlerCollection handlers ) throws IOException, URISyntaxException
	{
		this.server = server;
		setContextPath( "/" + Constants.DATASETLIST_CONTEXT_NAME );
	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
	{
		list( baseRequest, response );
	}

	private void list( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "application/json" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();
		getJsonDatasetList( ow );
		ow.close();
	}

	private void getJsonDatasetList( final PrintWriter out ) throws IOException
	{
		final JsonWriter writer = new JsonWriter( out );

		writer.setIndent( "\t" );

		writer.beginObject();

		getContexts( writer );

		writer.endObject();

		writer.flush();

		writer.close();
	}

	private String getContexts( final JsonWriter writer ) throws IOException
	{
		final StringBuilder sb = new StringBuilder();
		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			CellHandler contextHandler = null;
			if ( handler instanceof CellHandler )
			{
				contextHandler = ( CellHandler ) handler;

				final String datasetName = contextHandler.getContextPath().replaceFirst( "/", "" );

				writer.name( datasetName ).beginObject();

				writer.name( "id" ).value( datasetName );

				//writer.name( "desc" ).value( contextHandler.getDescription() );
				writer.name( "description" ).value( "NotImplemented" );

				writer.name( "thumbnailUrl" ).value( contextHandler.getThumbnailUrl() );

				writer.name( "datasetUrl" ).value( contextHandler.getDataSetURL() );

				writer.endObject();
			}
		}
		return sb.toString();
	}
}
