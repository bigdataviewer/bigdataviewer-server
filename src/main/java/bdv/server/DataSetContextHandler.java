package bdv.server;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * DataSet Handler handles /dataset context
 */
public class DataSetContextHandler extends ServletContextHandler
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( DataSetContextHandler.class );

	private final ContextHandlerCollection datasetHandlers;

	public DataSetContextHandler( final ContextHandlerCollection datasetHandlers )
	{
		this.datasetHandlers = datasetHandlers;
		setContextPath( "/" + Constants.DATASET_CONTEXT_NAME );

		final ServletHandler servletHandler = new ServletHandler();
		Servlet servlet = new DefaultServlet();
		ServletHolder servletHolder = new ServletHolder( servlet );
		servletHandler.addServletWithMapping( servletHolder, "/*.xml" );
		servletHandler.addServletWithMapping( servletHolder, "/*.bdv" );

		setHandler( servletHandler );
	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
	{
		String datasetName = target;

		if ( datasetName.lastIndexOf( ".xml" ) != -1 )
		{
			datasetName = datasetName.substring( 0, datasetName.lastIndexOf( ".xml" ) );
			findCellHandler( datasetName ).handleXml( baseRequest, response );
		}
		else if ( datasetName.lastIndexOf( ".bdv" ) != -1 )
		{
			datasetName = datasetName.substring( 0, datasetName.lastIndexOf( ".bdv" ) );
			findCellHandler( datasetName ).handleBdv( baseRequest, response );
		}
		else
			super.doHandle( Constants.DATASET_CONTEXT_NAME + "/" + target, baseRequest, request, response );
	}

	private CellHandler findCellHandler( final String datasetName )
	{
		CellHandler found = null;
		for ( final Handler handler : datasetHandlers.getChildHandlersByClass( CellHandler.class ) )
		{
			final CellHandler contextHandler = ( CellHandler ) handler;

			if ( contextHandler.getContextPath().equals( getContextPath() + datasetName ) )
			{
				found = contextHandler;
				break;
			}
		}

		return found;
	}
}
