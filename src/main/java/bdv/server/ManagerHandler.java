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

import mpicbg.spim.data.SpimDataException;

import org.antlr.stringtemplate.StringTemplate;
import org.antlr.stringtemplate.StringTemplateGroup;
import org.eclipse.jetty.server.ConnectorStatistics;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.log.Log;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.text.DecimalFormat;

/**
 * @author HongKee Moon &lt;moon@mpi-cbg.de&gt;
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
public class ManagerHandler extends ContextHandler
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( ManagerHandler.class );

	private final String baseURL;

	private final Server server;

	private final ContextHandlerCollection handlers;

	private final StatisticsHandler statHandler;

	private final ConnectorStatistics connectorStats;

	private String contexts = null;

	private int noDataSets = 0;

	private long sizeDataSets = 0;

	private final String thumbnailsDirectoryName;

	public ManagerHandler(
			final String baseURL,
			final Server server,
			final ConnectorStatistics connectorStats,
			final StatisticsHandler statHandler,
			final ContextHandlerCollection handlers,
			final String thumbnailsDirectoryName )
					throws IOException, URISyntaxException
	{
		this.baseURL = baseURL;
		this.server = server;
		this.handlers = handlers;
		this.statHandler = statHandler;
		this.connectorStats = connectorStats;
		this.thumbnailsDirectoryName = thumbnailsDirectoryName;
		setContextPath( "/" + Constants.MANAGER_CONTEXT_NAME );
	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
	{
		final String op = request.getParameter( "op" );

		if ( op == null )
		{
			list( baseRequest, response );
		}
		else if ( op.equals( "deploy" ) )
		{
			final String ds = request.getParameter( "ds" );
			final String file = request.getParameter( "file" );
			deploy( ds, file, baseRequest, response );
		}
		else if ( op.equals( "undeploy" ) )
		{
			final String ds = request.getParameter( "ds" );
			undeploy( ds, baseRequest, response );
		}
	}

	public String getByteSizeString( final long size )
	{
		if ( size <= 0 )
			return "0";
		final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
		final int digitGroups = ( int ) ( Math.log10( size ) / Math.log10( 1024 ) );
		return new DecimalFormat( "#,##0.#" ).format( size / Math.pow( 1024, digitGroups ) ) + " " + units[ digitGroups ];
	}

	private void list( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "text/html" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();

		ow.write( getHtml() );
		ow.close();
	}

	private String getHtml()
	{
		final StringTemplateGroup templates = new StringTemplateGroup( "manager" );
		final StringTemplate t = templates.getInstanceOf( "templates/manager" );

		t.setAttribute( "bytesSent", getByteSizeString( statHandler.getResponsesBytesTotal() ) );
		t.setAttribute( "msgPerSec", connectorStats.getMessagesOutPerSecond() );
		t.setAttribute( "openConnections", connectorStats.getConnectionsOpen() );
		t.setAttribute( "maxOpenConnections", connectorStats.getConnectionsOpenMax() );

		getContexts();

		t.setAttribute( "contexts", contexts );

		t.setAttribute( "noDataSets", noDataSets );
		t.setAttribute( "sizeDataSets", getByteSizeString( sizeDataSets ) );

		t.setAttribute( "statHtml", statHandler.toStatsHTML() );

		return t.toString();
	}

	private void getContexts()
	{
		if ( contexts == null )
		{
			noDataSets = 0;
			sizeDataSets = 0;

			final StringBuilder sb = new StringBuilder();
			for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
			{
				sb.append( "<tr>\n<th>" );
				final CellHandler contextHandler = ( CellHandler ) handler;
				sb.append( contextHandler.getContextPath() + "</th>\n<td>" );
				sb.append( contextHandler.getXmlFile() + "</td>\n</tr>\n" );
				noDataSets++;
				sizeDataSets += new File( contextHandler.getXmlFile().replace( ".xml", ".h5" ) ).length();
			}
			contexts = sb.toString();
		}
	}

	private void deploy( final String datasetName, final String fileLocation, final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		LOG.info( "Add new context: " + datasetName );
		final String context = "/" + datasetName;

		boolean alreadyExists = false;
		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			final CellHandler contextHandler = ( CellHandler ) handler;
			if ( context.equals( contextHandler.getContextPath() ) )
			{
				LOG.info( "Context " + datasetName + " already exists.");
				alreadyExists = true;
				break;
			}
		}

		if ( ! alreadyExists )
		{
			CellHandler ctx = null;
			try
			{
				ctx = new CellHandler( baseURL + context + "/", fileLocation, datasetName, thumbnailsDirectoryName );
			}
			catch ( final SpimDataException e )
			{
				LOG.warn( "Failed to create a CellHandler", e );
				e.printStackTrace();
			}
			ctx.setContextPath( context );
			handlers.addHandler( ctx );
		}

		response.setContentType( "text/html" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();
		if ( alreadyExists )
			ow.write( datasetName + " already exists. Not registered." );
		else
			ow.write( datasetName + " registered." );
		ow.close();
	}

	private void undeploy( final String datasetName, final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		LOG.info( "Remove the context: " + datasetName );
		boolean ret = false;

		final String context = "/" + datasetName;
		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			final CellHandler contextHandler = ( CellHandler ) handler;
			if ( context.equals( contextHandler.getContextPath() ) )
			{
				try
				{
					contextHandler.stop();
				}
				catch ( final Exception e )
				{
					e.printStackTrace();
				}
				contextHandler.destroy();
				handlers.removeHandler( contextHandler );
				ret = true;
				break;
			}
		}

		if ( ret )
		{
			response.setContentType( "text/html" );
			response.setStatus( HttpServletResponse.SC_OK );
			baseRequest.setHandled( true );

			final PrintWriter ow = response.getWriter();
			ow.write( datasetName + " removed." );
			ow.close();
		}
	}
}
