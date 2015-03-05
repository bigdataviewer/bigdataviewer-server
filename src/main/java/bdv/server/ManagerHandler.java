package bdv.server;

import com.google.gson.stream.JsonWriter;
import mpicbg.spim.data.SpimDataException;

import org.antlr.stringtemplate.StringTemplate;
import org.antlr.stringtemplate.StringTemplateGroup;
import org.apache.commons.collections.Buffer;
import org.apache.commons.collections.BufferUtils;
import org.apache.commons.collections.buffer.CircularFifoBuffer;
import org.eclipse.jetty.server.ConnectorStatistics;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author HongKee Moon <moon@mpi-cbg.de>
 * @author Tobias Pietzsch <tobias.pietzsch@gmail.com>
 */
public class ManagerHandler extends ContextHandler
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( ManagerHandler.class );

	private final String baseURL;

	private final Server server;

	private final ContextHandlerCollection handlers;

	private final ConnectorStatistics connectorStats;

	private int noDataSets = 0;

	private long sizeDataSets = 0;

	private final String thumbnailsDirectoryName;

	private long totalSentBytes = 0;

	// Buffer holds 1-hour period bandwidth information
	private Buffer fifo = BufferUtils.synchronizedBuffer( new CircularFifoBuffer( 12 * 60 ) );

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
		this.connectorStats = connectorStats;
		this.thumbnailsDirectoryName = thumbnailsDirectoryName;
		setContextPath( "/" + Constants.MANAGER_CONTEXT_NAME );

		ResourceHandler resHandler = new ResourceHandler();
		resHandler.setBaseResource( Resource.newClassPathResource( "webapp" ) );
		setHandler( resHandler );
		setWelcomeFiles( new String[] { "index.html" } );

		// Setup the statCollector for collecting statistics in every 5 seconds
		// Adding the data formed as byte/second
		final ScheduledExecutorService statCollector = Executors.newSingleThreadScheduledExecutor();
		statCollector.scheduleAtFixedRate( new Runnable()
		{
			@Override public void run()
			{
				totalSentBytes += statHandler.getResponsesBytesTotal();
				fifo.add( new Long( statHandler.getResponsesBytesTotal() / 5 ) );
				statHandler.statsReset();
			}
		}, 0, 5, TimeUnit.SECONDS );

	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
	{
		final String op = request.getParameter( "op" );

		if ( null != op )
		{
			if ( op.equals( "deploy" ) )
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
			else if ( op.equals( "getTrafficData" ) )
			{
				// Provide json type of one hour traffic information
				final String tf = request.getParameter( "tf" );
				final int timeFrame = Integer.parseInt( tf );
				getTraffic( timeFrame, baseRequest, response );
			}
			else if ( op.equals( "getDatasets" ) )
			{
				// Provide json type of datasets
				getDatasets( baseRequest, response );
			}
			else if ( op.equals( "getServerInfo" ) )
			{
				// Provide html type of server information
				getServerInfo( baseRequest, response );
			}
		}
		else
		{
			super.doHandle( target, baseRequest, request, response );
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

	/**
	 * Compute Dataset Statistics including the total size of datasets and the number of datasets
	 * When the new dataset is inserted or deleted, this function should be called in order to keep the statistics
	 * consistent.
	 */
	public void computeDatasetStat()
	{
		noDataSets = 0;
		sizeDataSets = 0;

		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			final CellHandler contextHandler = ( CellHandler ) handler;
			noDataSets++;
			sizeDataSets += contextHandler.getDataSetSize();
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

	private void getTraffic( final int tf, final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "application/json" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();
		getJsonTrafficData( tf, ow );
		ow.close();
	}

	private void getJsonTrafficData( final int tf, final PrintWriter out ) throws IOException
	{
		final JsonWriter writer = new JsonWriter( out );

		writer.setIndent( "\t" );

		writer.beginArray();

		Long[] dest = new Long[ tf ];

		Long[] src = ( Long[] ) fifo.toArray( new Long[ 1 ] );

		if ( dest.length > src.length )
		{
			System.arraycopy( src, 0, dest, dest.length - src.length, src.length );
		}
		else
		{
			System.arraycopy( src, src.length - dest.length, dest, 0, dest.length );
		}

		for ( int i = 0; i < dest.length; i++ )
		{
			if ( null == dest[ i ] )
				writer.value( 0 );
			else
				writer.value( dest[ i ] );
		}

		writer.endArray();

		writer.flush();

		writer.close();
	}

	private void getDatasets( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "application/json" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();
		getJsonDatasets( ow );
		ow.close();
	}

	private void getJsonDatasets( final PrintWriter out ) throws IOException
	{
		final JsonWriter writer = new JsonWriter( out );

		writer.setIndent( "\t" );

		writer.beginObject();

		writer.name( "data" );

		writer.beginArray();

		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			final CellHandler contextHandler = ( CellHandler ) handler;
			writer.beginObject();
			writer.name( "name" ).value( contextHandler.getContextPath().replaceFirst( "/", "" ) );
			writer.name( "path" ).value( contextHandler.getXmlFile() );
			writer.endObject();
		}

		writer.endArray();

		writer.endObject();

		writer.flush();

		writer.close();
	}

	private void getServerInfo( Request baseRequest, HttpServletResponse response ) throws IOException
	{
		// Calculate the size and the number of the datasets
		computeDatasetStat();

		response.setContentType( "text/html" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();

		final StringTemplateGroup templates = new StringTemplateGroup( "serverInfo" );
		final StringTemplate t = templates.getInstanceOf( "templates/serverInfo" );

		t.setAttribute( "bytesSent", getByteSizeString( totalSentBytes ) );
		t.setAttribute( "msgPerSec", connectorStats.getMessagesOutPerSecond() );
		t.setAttribute( "openConnections", connectorStats.getConnectionsOpen() );
		t.setAttribute( "maxOpenConnections", connectorStats.getConnectionsOpenMax() );
		t.setAttribute( "noDataSets", noDataSets );
		t.setAttribute( "sizeDataSets", getByteSizeString( sizeDataSets ) );

		ow.write( t.toString() );
		ow.close();
	}
}
