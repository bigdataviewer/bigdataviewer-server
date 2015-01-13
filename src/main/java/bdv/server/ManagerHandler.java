package bdv.server;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import mpicbg.spim.data.SpimDataException;

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
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DecimalFormat;

public class ManagerHandler extends ContextHandler
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( ManagerHandler.class );

	private final String baseURL;

	private final Server server;
	
	private final ContextHandlerCollection handlers;
	
	private final StatisticsHandler statHandler;

	public ManagerHandler( String baseURL, Server server, StatisticsHandler statHandler, ContextHandlerCollection handlers )
	{
		this.baseURL = baseURL;
		this.server = server;
		this.handlers = handlers;
		this.statHandler = statHandler;
		setContextPath( "/manager" );
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
		else
		{
			return;
		}

	}

	public String getByteSizeString( long size )
	{
		if ( size <= 0 )
			return "0";
		final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
		int digitGroups = ( int ) ( Math.log10( size ) / Math.log10( 1024 ) );
		return new DecimalFormat( "#,##0.#" ).format( size / Math.pow( 1024, digitGroups ) ) + " " + units[ digitGroups ];
	}

	private void list( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "text/html" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();

		ow.write( "<HTML>\n<HEAD><META HTTP-EQUIV=\"refresh\" CONTENT=\"5\"></HEAD>\n<BODY>" );

		ow.write( "This page is refreshed in every 5 secs.<br/>\n" );
		ow.write( "<br/>\n" );
		ow.write( "Bytes sent total: " + getByteSizeString( statHandler.getResponsesBytesTotal() ) + "<br/>\n" );

		ow.write( "<H1> Datasets: </H1>\n" );

		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			CellHandler contextHandler = null;
			if ( handler instanceof CellHandler )
			{
				contextHandler = ( CellHandler ) handler;
				ow.write( contextHandler.getContextPath() + "<BR/>" );
			}
		}

		ow.write( statHandler.toStatsHTML() );

		ow.write( "</BODY>" );
		ow.close();
	}

	private void deploy( final String datasetName, final String fileLocation, final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		LOG.info( "Add new context: " + datasetName );
		CellHandler ctx = null;
		try
		{
			ctx = new CellHandler( baseURL + datasetName + "/", fileLocation );
		}
		catch ( final SpimDataException e )
		{
			LOG.warn( "Failed to create a CellHandler", e );
			e.printStackTrace();
		}
		ctx.setContextPath( "/" + datasetName );
		handlers.addHandler( ctx );

		response.setContentType( "text/html" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();
		ow.write( datasetName + " registered." );
		ow.close();
	}

	private void undeploy( final String datasetName, final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		LOG.info( "Remove the context: " + datasetName );
		boolean ret = false;

		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			CellHandler contextHandler = null;
			if ( handler instanceof CellHandler )
			{
				contextHandler = ( CellHandler ) handler;
				if ( datasetName.equals( contextHandler.getContextPath() ) )
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
