package bdv.server;

import mpicbg.spim.data.SpimDataException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class ManagerHandler extends ContextHandler
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( ManagerHandler.class );
	private final String baseURL;
	private final Server server;
	private final HandlerCollection handlers;

	public ManagerHandler( String baseURL, Server server, HandlerCollection handlers )
	{
		this.baseURL = baseURL;
		this.server = server;
		this.handlers = handlers;
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
			String ds = request.getParameter( "ds" );
			String file = request.getParameter( "file" );
			deploy( ds, file, baseRequest, response );
		}
		else if ( op.equals( "undeploy" ) )
		{
			String ds = request.getParameter( "ds" );
			undeploy( ds, baseRequest, response );
		}
		else
		{
			return;
		}

	}

	private void list( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "text/html" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();

		for ( Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			CellHandler contextHandler = null;
			if ( handler instanceof CellHandler )
			{
				contextHandler = ( CellHandler ) handler;
				ow.write( contextHandler.getContextPath() );
			}
		}

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
		catch ( SpimDataException e )
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

		for ( Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
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
					catch ( Exception e )
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