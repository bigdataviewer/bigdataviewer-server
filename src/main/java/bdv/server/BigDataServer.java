package bdv.server;

import java.util.HashMap;

import mpicbg.spim.data.SpimDataException;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;

public class BigDataServer
{
	static HashMap< String, String > dataSet = new HashMap<>();

	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( BigDataServer.class );

	public static void main( final String[] args ) throws Exception
	{
		final String fn = args.length > 0 ? args[ 0 ] : "/Users/moon/Projects/git-projects/BigDataViewer/data/HisYFP-SPIM.xml";

		dataSet.put( "HisYFP-SPIM", fn );

		final int port = args.length > 1 ? Integer.parseInt( args[ 1 ] ) : 8080;
		System.setProperty( "org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog" );
		final Server server = new Server( port );

		final String baseURL = "http://" + server.getURI().getHost() + ":" + port;

		final HandlerCollection handlers = createHandlers( baseURL, dataSet );
		handlers.addHandler( new ManagerHandler( baseURL, server, handlers ) );

		LOG.info( "Set handler: " + handlers );
		server.setHandler( handlers );
		LOG.info( "BigDataServer starting" );
		server.start();
		server.join();
	}

	static private HandlerCollection createHandlers( final String baseURL, final HashMap< String, String > dataSet ) throws SpimDataException
	{
		final HandlerCollection handlers = new HandlerCollection( true );

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
