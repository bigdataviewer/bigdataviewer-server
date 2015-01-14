package bdv.server;

import mpicbg.spim.data.SpimDataException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ConnectorStatistics;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.util.HashMap;

public class BigDataServer
{
	static HashMap< String, String > dataSet = new HashMap<>();

	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( BigDataServer.class );

	public static void main( final String[] args ) throws Exception
	{
		final String fn = args.length > 0 ? args[ 0 ] : "/Users/moon/Projects/git-projects/BigDataViewer/data/HisYFP-SPIM.xml";

		dataSet.put( "HisYFP-SPIM", fn );
		dataSet.put( "t1-head", "/Users/moon/Projects/git-projects/BigDataViewer/data/t1-head.xml" );

		final int port = args.length > 1 ? Integer.parseInt( args[ 1 ] ) : 8080;
		System.setProperty( "org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog" );

		// Threadpool for multiple connections
		final Server server = new Server( new QueuedThreadPool( 1000, 10 ) );

		// ServerConnector configuration
		final ServerConnector connector = new ServerConnector( server );
		connector.setHost( "localhost" );
		connector.setPort( port );
		LOG.info( "Set connectors: " + connector );
		server.setConnectors( new Connector[] { connector } );

		// Add Statistics bean to the connector
		final ConnectorStatistics connectorStats = new ConnectorStatistics();
		connector.addBean( connectorStats );

		final String baseURL = "http://" + server.getURI().getHost() + ":" + port;
		LOG.info( "Server Base URL: " + baseURL );

		// Handler initialization
		final StatisticsHandler statHandler = new StatisticsHandler();

		final HandlerCollection handlers = new HandlerCollection();

		final ContextHandlerCollection datasetHandlers = createHandlers( baseURL, dataSet );
		handlers.addHandler( datasetHandlers );
		handlers.addHandler( new ManagerHandler( baseURL, server, connectorStats, statHandler, datasetHandlers ) );
		handlers.addHandler( new RequestLogHandler() );

		statHandler.setHandler( handlers );

		LOG.info( "Set handler: " + statHandler );
		server.setHandler( statHandler );
		LOG.info( "BigDataServer starting" );
		server.start();
		server.join();
	}

	static private ContextHandlerCollection createHandlers( final String baseURL, final HashMap< String, String > dataSet ) throws SpimDataException
	{
		final ContextHandlerCollection handlers = new ContextHandlerCollection();

		for ( final String key : dataSet.keySet() )
		{
			final String context = "/" + key;
			final CellHandler ctx = new CellHandler( baseURL + context + "/", dataSet.get( key ) );
			ctx.setContextPath( context );
			handlers.addHandler( ctx );
		}

		return handlers;
	}
}
