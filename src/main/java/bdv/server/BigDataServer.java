package bdv.server;

import java.util.HashMap;

import mpicbg.spim.data.SpimDataException;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.log.Log;

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
		final Server server = new Server( port );

		final String baseURL = "http://" + server.getURI().getHost() + ":" + port;

		final StatisticsHandler statHandler = new StatisticsHandler();

		final HandlerCollection handlers = new HandlerCollection();

		final ContextHandlerCollection datasetHandlers = createHandlers( baseURL, dataSet );
		handlers.addHandler( datasetHandlers );
		handlers.addHandler( new ManagerHandler( baseURL, server, statHandler, datasetHandlers ) );
		handlers.addHandler( new RequestLogHandler() );

		statHandler.setHandler( handlers );

		LOG.info( "Set handler: " + statHandler );
		server.setHandler( statHandler );
		LOG.info( "BigDataServer starting" );
		server.start();
		server.join();
	}

	static private ContextHandlerCollection createHandlers( String baseURL, HashMap< String, String > dataSet ) throws SpimDataException
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
