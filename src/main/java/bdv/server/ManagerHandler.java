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
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.text.DecimalFormat;

public class ManagerHandler extends ContextHandler
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( ManagerHandler.class );

	private final String baseURL;

	private final Server server;
	
	private final ContextHandlerCollection handlers;
	
	private final StatisticsHandler statHandler;
	
	private final ConnectorStatistics connectorStats;

	public ManagerHandler( final String baseURL, final Server server, final ConnectorStatistics connectorStats, final StatisticsHandler statHandler, ContextHandlerCollection handlers ) throws IOException, URISyntaxException
	{
		this.baseURL = baseURL;
		this.server = server;
		this.handlers = handlers;
		this.statHandler = statHandler;
		this.connectorStats = connectorStats;
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

		ow.write( getHtml() );
		ow.close();
	}

	private String getHtml()
	{
		// manager.st should be under {WorkingFolder}/templates/
		StringTemplateGroup templates =
				new StringTemplateGroup( "manager", "templates" );

		StringTemplate t = templates.getInstanceOf( "manager" );

		t.setAttribute( "bytesSent", getByteSizeString( statHandler.getResponsesBytesTotal() ) );
		t.setAttribute( "msgPerSec", connectorStats.getMessagesOutPerSecond() );
		t.setAttribute( "openConnections", connectorStats.getConnectionsOpen() );
		t.setAttribute( "maxOpenConnections", connectorStats.getConnectionsOpenMax() );

		t.setAttribute( "contexts", getContexts() );

		t.setAttribute( "statHtml", statHandler.toStatsHTML() );

		return t.toString();
	}

	private String getContexts()
	{
		StringBuilder sb = new StringBuilder();
		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			CellHandler contextHandler = null;
			if ( handler instanceof CellHandler )
			{
				sb.append( "<tr>\n<th>" );
				contextHandler = ( CellHandler ) handler;
				sb.append( contextHandler.getContextPath() + "</th>\n<td>" );
				sb.append( contextHandler.getXmlFile() + "</td>\n</tr>\n" );
			}
		}
		return sb.toString();
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
